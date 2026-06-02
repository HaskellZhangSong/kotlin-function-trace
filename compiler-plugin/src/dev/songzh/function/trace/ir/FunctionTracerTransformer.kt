package dev.songzh.function.trace.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
// Wildcard import is intentional: it brings in top-level factory extensions from BuildersKt
// (IrCallImpl.fromSymbolOwner, IrBlockImpl, IrReturnImpl, etc.) that are not available through
// individual class imports in Kotlin 2.1.
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private enum class EntryOrExit { ENTRY, EXIT }

/** FQ name of the @Trace annotation defined in plugin-annotations. */
private const val TRACE_ANNOTATION_FQ_NAME = "dev.songzh.function.trace.Trace"

/**
 * IR transformer that wraps function bodies with entry/exit trace calls.
 *
 * For every function that should be traced it:
 *  1. Prepends `_funcTraceEnter("<fqName>")` at the very start of the body.
 *  2. Transforms every `IrReturn` that targets this function into a block
 *     that calls `_funcTraceExit("<fqName>")` just before the return value
 *     is produced.
 *
 * A function is selected for tracing when either:
 *  - `traceAll == true`, or
 *  - the function carries the `@Trace` annotation.
 *
 * Inline and external functions are always skipped.
 */
class FunctionTracerTransformer(
    private val pluginContext: IrPluginContext,
    private val traceAll: Boolean,
    private val functionPackage: String = "dev.songzh.function.trace",
    private val messageCollector: MessageCollector = MessageCollector.NONE,
) : IrElementTransformerVoid() {

    private val irBuiltIns = pluginContext.irBuiltIns

    /**
     * Lazily resolved reference to `_funcTraceEnter(functionName)` in [functionPackage].
     */
    @Suppress("DEPRECATION")
    private val funcTraceEnterSymbol: IrSimpleFunctionSymbol by lazy {
        resolveTracingSymbol("_funcTraceEnter")
    }

    /**
     * Lazily resolved reference to `_funcTraceExit(functionName)` in [functionPackage].
     */
    @Suppress("DEPRECATION")
    private val funcTraceExitSymbol: IrSimpleFunctionSymbol by lazy {
        resolveTracingSymbol("_funcTraceExit")
    }

    @Suppress("DEPRECATION")
    private fun resolveTracingSymbol(name: String): IrSimpleFunctionSymbol {
        val candidates = pluginContext.referenceFunctions(
            CallableId(FqName(functionPackage), Name.identifier(name))
        )
        if (candidates.isNotEmpty()) return candidates.first()

        val message = buildString {
            appendLine("Function tracer plugin: cannot find '$name' in package '$functionPackage'.")
            appendLine("Make sure that:")
            appendLine("  1. The plugin-annotations artifact is on the compile classpath.")
            appendLine("  2. The 'packagePath' option matches the package that declares '$name'.")
            append("     Current value: packagePath = \"$functionPackage\"")
        }
        messageCollector.report(CompilerMessageSeverity.ERROR, message)
        error(message)
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        // Process nested functions / lambdas depth-first.
        declaration.transformChildrenVoid(this)

        if (declaration.isInline) return declaration
        if (declaration.isExternal) return declaration
        if (declaration.origin == IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA) return declaration
        // Never instrument the trace hooks themselves — doing so would cause infinite
        // recursion when traceAll = true and the user supplies their own
        // _funcTraceEnter / _funcTraceExit implementations.
        if (declaration.name.asString() == "_funcTraceEnter" ||
            declaration.name.asString() == "_funcTraceExit") return declaration

        val shouldTrace = traceAll ||
                declaration.hasAnnotation(FqName(TRACE_ANNOTATION_FQ_NAME))
        if (!shouldTrace) return declaration

        val functionName = buildFunctionName(declaration)

        when (val body = declaration.body) {
            is IrBlockBody -> {
                // Step 1 – wrap every IrReturn so exit trace fires just before the return.
                val wrapper = ReturnWrapper(declaration.symbol, declaration, functionName)
                body.transformChildrenVoid(wrapper)
                // Step 2 – prepend the entry trace.
                body.statements.add(0, buildTraceCall(functionName, EntryOrExit.ENTRY))
                // Step 3 – fallback for Unit functions that have no IrReturn to wrap
                // (e.g. native entry-point main on Kotlin/Native).
                if (!wrapper.wrappedAnyReturn && declaration.returnType == irBuiltIns.unitType) {
                    body.statements.add(buildTraceCall(functionName, EntryOrExit.EXIT))
                }
            }
            is IrExpressionBody -> {
                declaration.body = buildInstrumentedBlockFromExpression(
                    declaration, functionName, body.expression
                )
            }
            else -> return declaration
        }

        return declaration
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildFunctionName(function: IrSimpleFunction): String = buildString {
        val parent = function.parent
        if (parent is IrClass) {
            val pkg = (parent.parent as? IrPackageFragment)?.packageFqName?.asString()
            if (!pkg.isNullOrEmpty()) { append(pkg); append(".") }
            append(parent.name.asString())
            append(".")
        } else {
            val pkg = (function.parent as? IrPackageFragment)?.packageFqName?.asString()
            if (!pkg.isNullOrEmpty()) { append(pkg); append(".") }
        }
        append(function.name.asString())
    }

    /**
     * Builds a call to `_funcTraceEnter` or `_funcTraceExit` with [functionName]
     * as its single String argument.
     */
    private fun buildTraceCall(functionName: String, entryOrExit: EntryOrExit): IrCall {
        val symbol = if (entryOrExit == EntryOrExit.ENTRY) funcTraceEnterSymbol else funcTraceExitSymbol
        val call = IrCallImpl.fromSymbolOwner(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            symbol = symbol,
        )
        call.putValueArgument(0, irString(functionName))
        return call
    }

    private fun irString(value: String): IrConst =
        IrConstImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.stringType, IrConstKind.String, value)

    /**
     * Converts an expression-body function into an instrumented block body.
     *
     * Non-Unit:
     *   _funcTraceEnter("name")
     *   val _traceResult = <expr>
     *   _funcTraceExit("name")
     *   return _traceResult
     *
     * Unit:
     *   _funcTraceEnter("name")
     *   <expr>
     *   _funcTraceExit("name")
     *   return Unit
     */
    private fun buildInstrumentedBlockFromExpression(
        function: IrSimpleFunction,
        functionName: String,
        expression: IrExpression,
    ): IrBlockBody {
        val newBody = pluginContext.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        newBody.statements.add(buildTraceCall(functionName, EntryOrExit.ENTRY))

        if (expression.type == irBuiltIns.unitType) {
            newBody.statements.add(expression)
            newBody.statements.add(buildTraceCall(functionName, EntryOrExit.EXIT))
            newBody.statements.add(
                IrReturnImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    irBuiltIns.nothingType,
                    function.symbol,
                    IrGetObjectValueImpl(
                        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                        irBuiltIns.unitType,
                        irBuiltIns.unitClass,
                    ),
                )
            )
        } else {
            val tempVar = buildVariable(
                parent = function,
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier("_traceResult"),
                type = expression.type,
            ).also { it.initializer = expression }

            newBody.statements.add(tempVar)
            newBody.statements.add(buildTraceCall(functionName, EntryOrExit.EXIT))
            newBody.statements.add(
                IrReturnImpl(
                    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                    irBuiltIns.nothingType,
                    function.symbol,
                    IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, tempVar.type, tempVar.symbol),
                )
            )
        }
        return newBody
    }

    // -------------------------------------------------------------------------
    // Inner transformer – wraps IrReturn nodes for a specific function symbol.
    // -------------------------------------------------------------------------

    private inner class ReturnWrapper(
        private val targetSymbol: IrReturnTargetSymbol,
        private val targetFunction: IrSimpleFunction,
        private val functionName: String,
    ) : IrElementTransformerVoid() {

        /** `true` once we have successfully wrapped at least one [IrReturn]. */
        var wrappedAnyReturn = false

        // Do NOT recurse into nested function / constructor bodies.
        override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement = declaration
        override fun visitConstructor(declaration: IrConstructor): IrStatement = declaration

        override fun visitReturn(expression: IrReturn): IrExpression {
            if (expression.returnTargetSymbol != targetSymbol) {
                return super.visitReturn(expression)
            }

            val originalValue = expression.value
            wrappedAnyReturn = true

            // Unit return: emit the exit trace BEFORE the return, keep return untouched.
            //
            //   IrBlock<Nothing> {
            //       _funcTraceExit("name")
            //       IrReturn(Unit)
            //   }
            if (originalValue.type == irBuiltIns.unitType) {
                val block = IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.nothingType)
                block.statements.add(buildTraceCall(functionName, EntryOrExit.EXIT))
                block.statements.add(expression)
                return block
            }

            // Non-Unit return: capture result into a temp, emit exit trace, then return.
            //
            //   val _traceResult = <expression>
            //   _funcTraceExit("name")
            //   return _traceResult
            val tempVar = buildVariable(
                parent = targetFunction,
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = IrDeclarationOrigin.DEFINED,
                name = Name.identifier("_traceResult"),
                type = originalValue.type,
            ).also { it.initializer = originalValue }

            val getTemp = IrGetValueImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                tempVar.type,
                tempVar.symbol,
            )

            val block = IrBlockImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltIns.nothingType)
            block.statements.add(tempVar)
            block.statements.add(buildTraceCall(functionName, EntryOrExit.EXIT))
            block.statements.add(
                IrReturnImpl(
                    startOffset = expression.startOffset,
                    endOffset = expression.endOffset,
                    type = expression.type,
                    returnTargetSymbol = expression.returnTargetSymbol,
                    value = getTemp,
                )
            )
            return block
        }
    }
}


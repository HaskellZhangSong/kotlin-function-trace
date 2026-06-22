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
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrReturnableBlock
// Wildcard import is intentional: it brings in top-level factory extensions from BuildersKt
// (IrCallImpl.fromSymbolOwner, IrBlockImpl, IrReturnImpl, etc.) that are not available through
// individual class imports in Kotlin 2.1.
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private enum class EntryOrExit { ENTRY, EXIT }

/**
 * IR transformer that wraps function bodies with entry/exit trace calls.
 *
 * For every function that should be traced it:
 *  1. Prepends `_funcTraceEnter("<fqName>")` at the very start of the body.
 *  2. Transforms every `IrReturn` that targets this function into a block
 *     that calls `_funcTraceExit("<fqName>")` just before the return value
 *     is produced.
 *
 * A function is selected for tracing when `traceAll == true` (the default).
 * Inline, external, and lambda functions are always skipped.
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

        val shouldTrace = traceAll
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
                // Run ReturnWrapper on the expression BEFORE building the instrumented
                // block.  This ensures that any IrReturn nodes that target this function
                // and are buried inside an inlined lambda (e.g. `fun foo() = run { return 42 }`)
                // already have _funcTraceExit embedded in them.  Without this step the
                // return would fire before the exit-trace call that
                // buildInstrumentedBlockFromExpression would add after the expression.
                val wrapper = ReturnWrapper(declaration.symbol, declaration, functionName)
                body.transformChildrenVoid(wrapper)
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
     * Returns `true` when this expression is an [IrContainerExpression] whose
     * last element is **not** an [IrExpression] (e.g. an [IrVariable] declaration).
     *
     * Kotlin/Native's LLVM codegen asserts `value.type.isUnit()` inside
     * `evaluateContainerExpression` when the last element is a statement rather
     * than an expression.  Placing such a container in *value position* (e.g. as
     * the initialiser of a `val _traceResult = …`) therefore crashes the compiler.
     */
    private fun IrExpression.endsWithNonExpressionStatement(): Boolean =
        this is IrContainerExpression &&
            statements.isNotEmpty() &&
            statements.last() !is IrExpression

    /**
     * Converts an expression-body function into an instrumented block body.
     *
     * Nothing (diverges — throw / TODO()):
     *   _funcTraceEnter("name")
     *   <expr>          ← exits via exception; everything below is unreachable
     *
     * Unit  —OR—  container ending with a non-expression statement:
     *   _funcTraceEnter("name")
     *   <expr>
     *   _funcTraceExit("name")
     *   return Unit
     *
     * Normal non-Unit:
     *   _funcTraceEnter("name")
     *   val _traceResult = <expr>
     *   _funcTraceExit("name")
     *   return _traceResult
     */
    private fun buildInstrumentedBlockFromExpression(
        function: IrSimpleFunction,
        functionName: String,
        expression: IrExpression,
    ): IrBlockBody {
        val newBody = pluginContext.irFactory.createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET)
        newBody.statements.add(buildTraceCall(functionName, EntryOrExit.ENTRY))

        when {
            // Diverging expression (throw, TODO(), call to a Nothing-returning fun).
            // Everything after it is unreachable — don't emit exit or return.
            expression.type == irBuiltIns.nothingType -> {
                newBody.statements.add(expression)
            }

            // Unit expression, OR a container expression whose last element is a
            // declaration (IrVariable etc.) rather than an IrExpression.
            // The latter cannot be placed in value position on Kotlin/Native without
            // triggering `assert(value.type.isUnit())` in evaluateContainerExpression.
            expression.type == irBuiltIns.unitType || expression.endsWithNonExpressionStatement() -> {
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
            }

            // Normal non-Unit expression: capture the result, emit exit, return.
            else -> {
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

        // Do NOT recurse into constructor bodies — their IrReturn nodes target
        // the constructor symbol, not our function.
        override fun visitConstructor(declaration: IrConstructor): IrStatement = declaration

        // ── IrReturnableBlock handling ────────────────────────────────────────
        //
        // K2 inlines `run { return 42 }` into an IrReturnableBlock *before*
        // our plugin runs.  The IrReturn inside the block targets the *block*
        // symbol, not the function symbol, so the normal visitReturn check in
        // ReturnWrapper misses it.
        //
        // When the block can NEVER fall through to normal completion (every
        // path ends with a diverging expression, such as an IrReturn), K2's
        // JVM codegen emits a plain `ireturn` for the IrReturn — making any
        // code inserted AFTER the block (e.g. our `exit_outer`) unreachable.
        //
        // In that case we inject the trace exit *inside* the block, just
        // before the IrReturn that exits it, via a sub-wrapper whose
        // targetSymbol is the block's own symbol.
        //
        // When the block CAN fall through (e.g. `run { if(cond) return 10; 20 }`),
        // K2 uses a flag mechanism that does not bypass post-block code, so
        // the outer exit call added by buildInstrumentedBlockFromExpression /
        // the outer IrReturn wrapper is reachable and we leave the block alone.
        //
        // Override BOTH visitReturnableBlock and visitBlock to stay safe across
        // K2 minor versions that may or may not delegate one to the other.

        private fun IrReturnableBlock.couldFallThrough(): Boolean {
            // Nothing-typed block by declaration → never falls through.
            if (type == irBuiltIns.nothingType) return false
            val last = statements.lastOrNull() ?: return true
            // If the last element is a diverging expression (IrReturn, throw,
            // TODO() etc.), the block has no fall-through path.
            if (last is IrExpression && last.type == irBuiltIns.nothingType) return false
            return true
        }

        private fun handleReturnableBlock(expression: IrReturnableBlock): IrExpression {
            if (!expression.couldFallThrough()) {
                // Block never falls through — exiting it IS exiting the function.
                // Inject exit before the IrReturn(s) that exit the block.
                val subWrapper = ReturnWrapper(expression.symbol, targetFunction, functionName)
                expression.transformChildrenVoid(subWrapper)
                if (subWrapper.wrappedAnyReturn) wrappedAnyReturn = true
                return expression
            }
            // Block could fall through: recurse normally so inner IrReturn nodes
            // are still visited (they target the block symbol, not our function,
            // so visitReturn will leave them alone, but they still need to be
            // traversed in case there are nested ReturnableBlocks).
            expression.transformChildrenVoid(this)
            return expression
        }

        override fun visitReturnableBlock(expression: IrReturnableBlock): IrExpression {
            System.err.println(
                "[FunctionTracer] visitReturnableBlock fn=$functionName " +
                "type=${expression.type} couldFallThrough=${expression.couldFallThrough()} " +
                "lastStmt=${expression.statements.lastOrNull()?.let { s -> s::class.simpleName + " type=" + ((s as? IrExpression)?.type ?: "n/a") }}"
            )
            return handleReturnableBlock(expression)
        }

        override fun visitBlock(expression: IrBlock): IrExpression {
            if (expression is IrReturnableBlock) return handleReturnableBlock(expression)
            return super.visitBlock(expression)
        }

        override fun visitReturn(expression: IrReturn): IrExpression {
            if (expression.returnTargetSymbol != targetSymbol) {
                return super.visitReturn(expression)
            }

            val originalValue = expression.value
            wrappedAnyReturn = true

            // Unit return, OR a container whose last element is a declaration
            // (not an expression): both must stay in statement position.
            // Emit exit BEFORE the return; do NOT try to capture the value
            // into a temp variable (that would place the container in value
            // position and crash Kotlin/Native's LLVM codegen).
            if (originalValue.type == irBuiltIns.unitType ||
                originalValue.endsWithNonExpressionStatement()
            ) {
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


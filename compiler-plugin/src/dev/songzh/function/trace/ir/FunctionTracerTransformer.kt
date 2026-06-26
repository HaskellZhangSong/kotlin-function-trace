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
                // Step 1 – insert exit trace before every IrReturn, working directly
                // in the statement list (flat expansion) rather than wrapping returns
                // in new IrBlock(Nothing) nodes.
                //
                // WHY FLAT:  our plugin runs before the Compose compiler.  When Compose
                // later processes a @Composable function it "hoists" every IrReturn it
                // finds: it replaces the IrReturn *wherever it is* with a temp-var
                // assignment, then emits cleanup + RETURN as siblings at the outer
                // IrBlockBody level.  If the IrReturn was buried inside our
                // IrBlock(Nothing) wrapper, Compose moves it out but leaves the block
                // ending with the extracted VAR declaration.  A Nothing-typed
                // IrContainerExpression whose last element is not an IrExpression then
                // triggers `assert(value.type.isUnit())` in
                // Kotlin/Native's evaluateContainerExpression.
                //
                // Flat expansion avoids the IrBlock wrapper entirely:
                //   val _traceResult = <value>   <- plain statement
                //   _funcTraceExit(...)           <- plain statement
                //   return _traceResult           <- plain statement, Compose can hoist it
                val wrappedAny = flatInstrumentReturns(
                    body.statements, declaration.symbol, declaration, functionName
                )
                // Step 2 – prepend the entry trace.
                body.statements.add(0, buildTraceCall(functionName, EntryOrExit.ENTRY))
                // Step 3 – fallback for Unit functions with no IrReturn at all.
                if (!wrappedAny && declaration.returnType == irBuiltIns.unitType) {
                    body.statements.add(buildTraceCall(functionName, EntryOrExit.EXIT))
                }
            }
            is IrExpressionBody -> {
                // When the expression body is an IrReturnableBlock (the common case for
                // `fun foo() = run { … }`, `fun foo() = kotlin.run { … }`, etc.), we
                // "unpack" the retBlock into a flat IrBlockBody and apply the same
                // flatInstrumentReturns logic used for ordinary block-body functions.
                //
                // WHY NOT ReturnWrapper + buildInstrumentedBlockFromExpression:
                // That approach puts the IrReturnableBlock as the *initialiser* of a
                // `_traceResult` variable.  When a non-local IrReturn(fn.symbol) fires
                // *inside* the retBlock (e.g. `if (x > 0) return 1`), the K2 JVM backend
                // emits a direct `ireturn` and bypasses the exit call and the return of
                // `_traceResult`.  The IrBlock(Nothing) wrapper we injected via
                // ReturnWrapper is present in the IR but the JVM codegen optimises it away
                // when the retBlock is in value position.
                //
                // By moving the retBlock's statements out into a real IrBlockBody:
                //  1. IrReturn(retBlock.symbol) nodes (the fallthrough path) are rebased
                //     to IrReturn(fn.symbol) so that flatInstrumentReturns can treat them
                //     like ordinary function returns.
                //  2. IrReturn(fn.symbol) nodes (non-local returns from inlined lambdas)
                //     are already targeting the function and are handled directly.
                //  3. The IrBlock(Nothing) wrappers created by ReturnWrapper are now at
                //     the statement level of an IrBlockBody (not inside a retBlock in
                //     value position), so the JVM backend generates them correctly.
                val expr = body.expression
                if (expr is IrReturnableBlock) {
                    // Rebase local block-returns to function-returns.
                    rebaseBlockReturns(expr.statements, expr.symbol, declaration.symbol)
                    // Build a new block body from the retBlock's statements.
                    val newBody = pluginContext.irFactory
                        .createBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET)
                    newBody.statements.addAll(expr.statements)
                    // Instrument with the same flat approach as IrBlockBody.
                    val wrappedAny = flatInstrumentReturns(
                        newBody.statements, declaration.symbol, declaration, functionName
                    )
                    newBody.statements.add(0, buildTraceCall(functionName, EntryOrExit.ENTRY))
                    if (!wrappedAny && declaration.returnType == irBuiltIns.unitType) {
                        newBody.statements.add(buildTraceCall(functionName, EntryOrExit.EXIT))
                    }
                    declaration.body = newBody
                } else {
                    // Non-retBlock expression body (plain constant, call, when, etc.):
                    // use the original ReturnWrapper approach, which is safe here because
                    // such expressions do not place IrReturnableBlock in value position.
                    val wrapper = ReturnWrapper(declaration.symbol, declaration, functionName)
                    body.transformChildrenVoid(wrapper)
                    declaration.body = buildInstrumentedBlockFromExpression(
                        declaration, functionName, body.expression
                    )
                }
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
            // Class member function
            val pkg = (parent.parent as? IrPackageFragment)?.packageFqName?.asString()
            if (!pkg.isNullOrEmpty()) { append(pkg); append(".") }
            append(parent.name.asString())
            append(".")
        } else {
            // Global function
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

    /**
     * Instruments every [IrReturn] targeting [target] that appears in a **statement
     * position** inside [stmts] (and recursively inside nested
     * [IrContainerExpression] children that are themselves in statement position).
     *
     * Unlike [ReturnWrapper], this helper expands each return *in-place* — inserting
     * the temp-var and exit call as sibling statements — instead of wrapping them in
     * a new `IrBlock(Nothing)` node.  This is safe for Compose because Compose's own
     * IR transformer can then hoist `RETURN _traceResult` from the flat statement
     * list without creating an orphaned variable reference (scope escape).
     *
     * For returns in *expression* positions (e.g. inside [IrWhen] branch results or
     * [IrTry] bodies) we fall back to [ReturnWrapper], which wraps them in an
     * `IrBlock(Nothing)`.  Those positions are handled correctly by Compose.
     *
     * @return `true` if at least one return was instrumented.
     */
    private fun flatInstrumentReturns(
        stmts: MutableList<IrStatement>,
        target: IrReturnTargetSymbol,
        fn: IrSimpleFunction,
        name: String,
    ): Boolean {
        var wrapped = false
        var i = 0
        while (i < stmts.size) {
            val s = stmts[i]
            when {
                // ── IrReturn targeting our function ──────────────────────────
                s is IrReturn && s.returnTargetSymbol == target -> {
                    val v = s.value

                    // If the return value is itself an IrReturnableBlock, unpack its
                    // statements into the current list instead of capturing the retBlock
                    // as a _traceResult initialiser.
                    //
                    // K2 converts expression-body functions to IrBlockBody with a single
                    // IrReturn(fn, IrReturnableBlock{…}).  If the retBlock is placed in
                    // VALUE position (as `val _traceResult = <retBlock>`), any non-local
                    // IrReturn(fn) inside it causes the K2 JVM backend to emit a direct
                    // `ireturn`, bypassing every subsequent statement — including our
                    // exit call.
                    //
                    // By unpacking we avoid the value-position issue entirely.  After
                    // rebasing `IrReturn(retBlock) → IrReturn(fn)`, the retBlock's
                    // statements are ordinary block-body statements and flatInstrumentReturns
                    // handles them on subsequent iterations.
                    if (v is IrReturnableBlock) {
                        rebaseBlockReturns(v.statements, v.symbol, target)
                        stmts.removeAt(i)
                        stmts.addAll(i, v.statements)
                        // Don't advance i — let subsequent iterations process the
                        // inserted statements and set `wrapped` accordingly.
                        continue
                    }

                    // Wrap any non-local IrReturn(fn) nodes inside the return value
                    // expression (e.g. inside an un-inlined `run { if (x > 0) return 1; 0 }`
                    // lambda body).  After K2 inlines `run`, the IrBlock(Nothing) wrappers
                    // created by ReturnWrapper are at statement level in the inlined body —
                    // so the JVM backend executes the exit call before the non-local return.
                    val rw = ReturnWrapper(target, fn, name)
                    v.transformChildrenVoid(rw)

                    when {
                        // Diverging expression: just emit it as a statement; the function
                        // never returns normally from this point, so no exit is needed here.
                        v.type == irBuiltIns.nothingType -> {
                            stmts[i] = v
                            i += 1
                        }
                        v.type == irBuiltIns.unitType || v.endsWithNonExpressionStatement() -> {
                            // Unit / non-expr-container: just insert exit before the return.
                            stmts.add(i, buildTraceCall(name, EntryOrExit.EXIT))
                            i += 2   // advance past [exit, return]
                        }
                        else -> {
                            // Non-Unit: expand to [tempVar, exit, return(tempVar)] inline.
                            val tmp = buildVariable(
                                parent = fn,
                                startOffset = UNDEFINED_OFFSET,
                                endOffset = UNDEFINED_OFFSET,
                                origin = IrDeclarationOrigin.DEFINED,
                                name = Name.identifier("_traceResult"),
                                type = v.type,
                            ).also { it.initializer = v }

                            // Replace the original return with one that reads the temp.
                            stmts[i] = IrReturnImpl(
                                s.startOffset, s.endOffset, s.type,
                                s.returnTargetSymbol,
                                IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, tmp.type, tmp.symbol),
                            )
                            // Insert [tempVar, exit] before the now-modified return.
                            stmts.add(i, buildTraceCall(name, EntryOrExit.EXIT))
                            stmts.add(i, tmp)
                            i += 3   // advance past [tmp, exit, return]
                        }
                    }
                    wrapped = true
                }

                // ── IrReturnableBlock in statement position ───────────────────
                // K2 represents `run { … }` / other inline-lambda calls as an
                // IrReturnableBlock *before* the inliner runs.  Returns inside the
                // block target the block's own symbol, not the outer function.
                // For non-fallthrough blocks (Nothing type), exiting the block IS
                // exiting the function, so recurse with the block's symbol.
                s is IrReturnableBlock -> {
                    val neverFallsThrough =
                        s.type == irBuiltIns.nothingType ||
                        s.statements.lastOrNull().let { last ->
                            last is IrExpression && last.type == irBuiltIns.nothingType
                        }
                    val innerTarget = if (neverFallsThrough) s.symbol else target
                    if (flatInstrumentReturns(s.statements, innerTarget, fn, name)) wrapped = true
                    i++
                }

                // ── Plain IrBlock / IrComposite in statement position ─────────
                s is IrContainerExpression -> {
                    if (flatInstrumentReturns(s.statements, target, fn, name)) wrapped = true
                    i++
                }

                // ── IrWhen, IrTry, loops, etc. ────────────────────────────────
                // Returns inside these constructs are in *expression* positions
                // (branch results, catch bodies).  Use ReturnWrapper for those —
                // the IrBlock(Nothing) wrapper it creates is safe in expression
                // position and Compose does not hoist returns from there.
                else -> {
                    val rw = ReturnWrapper(target, fn, name)
                    s.transformChildrenVoid(rw)
                    if (rw.wrappedAnyReturn) wrapped = true
                    i++
                }
            }
        }
        return wrapped
    }

    /**
     * Walks [stmts] and replaces every [IrReturn] whose [IrReturn.returnTargetSymbol]
     * equals [from] with a new [IrReturn] targeting [to], preserving start/end offsets,
     * type, and value.
     *
     * Used to rebase the *fallthrough* return of an [IrReturnableBlock] (which targets the
     * block's own symbol) to the enclosing function symbol before the block's statements
     * are moved into a flat [IrBlockBody].  Non-local returns (`fn.symbol`) and returns
     * inside nested functions/constructors are left untouched.
     */
    private fun rebaseBlockReturns(
        stmts: MutableList<IrStatement>,
        from: IrReturnTargetSymbol,
        to: IrReturnTargetSymbol,
    ) {
        val rebaser = object : IrElementTransformerVoid() {
            // Do not cross nested-function or constructor boundaries.
            override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement = declaration
            override fun visitConstructor(declaration: IrConstructor): IrStatement = declaration

            override fun visitReturn(expression: IrReturn): IrExpression {
                // Recurse first so that nested IrReturn values are visited.
                super.visitReturn(expression)
                return if (expression.returnTargetSymbol == from)
                    IrReturnImpl(
                        expression.startOffset, expression.endOffset,
                        expression.type, to, expression.value,
                    )
                else expression
            }
        }
        stmts.replaceAll { it.transform(rebaser, null) as IrStatement }
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


package dev.songzh.function.trace.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Registers [dev.songzh.function.trace.ir.FunctionTracerTransformer] as an IR generation extension.
 *
 * @param traceAll    When `true`, every non-inline, non-external function in the
 *                    module is instrumented.  When `false` (default), only functions
 *                    carrying the `@Trace` annotation are instrumented.
 * @param packagePath Fully-qualified package name that contains `_funcTraceEnter`
 *                    and `_funcTraceExit`.
 */
class FunctionTracerIrGenerationExtension(
    private val traceAll: Boolean,
    private val packagePath: String,
) : IrGenerationExtension {

    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.transformChildrenVoid(
            _root_ide_package_.dev.songzh.function.trace.ir.FunctionTracerTransformer(
                pluginContext = pluginContext,
                traceAll = traceAll,
                functionPackage = packagePath,
                messageCollector = MessageCollector.NONE,
            )
        )
    }
}


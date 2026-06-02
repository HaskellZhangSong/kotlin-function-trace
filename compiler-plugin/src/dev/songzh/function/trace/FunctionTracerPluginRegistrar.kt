package dev.songzh.function.trace

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import dev.songzh.function.trace.ir.FunctionTracerIrGenerationExtension

/** Default package shipped with plugin-annotations. */
private const val DEFAULT_PACKAGE_PATH = "dev.songzh.function.trace"

@Suppress("unused") // Used via reflection.
class FunctionTracerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean
        get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val traceAll = configuration.get(FunctionTracerConfigurationKeys.TRACE_ALL, false)
        val packagePath = configuration.get(FunctionTracerConfigurationKeys.PACKAGE_PATH, DEFAULT_PACKAGE_PATH)

        IrGenerationExtension.registerExtension(
            FunctionTracerIrGenerationExtension(
                traceAll = traceAll,
                packagePath = packagePath
            )
        )
    }
}

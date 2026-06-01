package dev.songzh.function.trace

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import dev.songzh.function.trace.ir.FunctionTracerIrGenerationExtension
import dev.songzh.function.trace.ir.SimpleIrGenerationExtension

/** Default package shipped with plugin-annotations. */
private const val DEFAULT_PACKAGE_PATH = "dev.songzh.function.trace"

class SimplePluginComponentRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean
        get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val traceAll = configuration.get(_root_ide_package_.dev.songzh.function.trace.FunctionTracerConfigurationKeys.TRACE_ALL, false)
        val packagePath = configuration.get(
            _root_ide_package_.dev.songzh.function.trace.FunctionTracerConfigurationKeys.PACKAGE_PATH,
            _root_ide_package_.dev.songzh.function.trace.DEFAULT_PACKAGE_PATH
        )

        FirExtensionRegistrarAdapter.registerExtension(_root_ide_package_.dev.songzh.function.trace.SimplePluginRegistrar())
        IrGenerationExtension.registerExtension(_root_ide_package_.dev.songzh.function.trace.ir.SimpleIrGenerationExtension())
        IrGenerationExtension.registerExtension(
            _root_ide_package_.dev.songzh.function.trace.ir.FunctionTracerIrGenerationExtension(
                traceAll = traceAll,
                packagePath = packagePath
            )
        )
    }
}

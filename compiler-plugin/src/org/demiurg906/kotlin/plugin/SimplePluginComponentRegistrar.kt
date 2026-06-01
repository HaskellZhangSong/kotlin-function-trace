package org.demiurg906.kotlin.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.demiurg906.kotlin.plugin.ir.FunctionTracerIrGenerationExtension
import org.demiurg906.kotlin.plugin.ir.SimpleIrGenerationExtension

/** Default package shipped with plugin-annotations. */
private const val DEFAULT_PACKAGE_PATH = "org.demiurg906.kotlin.plugin"

class SimplePluginComponentRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean
        get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val traceAll = configuration.get(FunctionTracerConfigurationKeys.TRACE_ALL, false)
        val packagePath = configuration.get(FunctionTracerConfigurationKeys.PACKAGE_PATH, DEFAULT_PACKAGE_PATH)

        FirExtensionRegistrarAdapter.registerExtension(SimplePluginRegistrar())
        IrGenerationExtension.registerExtension(SimpleIrGenerationExtension())
        IrGenerationExtension.registerExtension(
            FunctionTracerIrGenerationExtension(traceAll = traceAll, packagePath = packagePath)
        )
    }
}

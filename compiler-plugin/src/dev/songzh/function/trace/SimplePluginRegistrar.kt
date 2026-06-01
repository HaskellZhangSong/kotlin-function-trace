package dev.songzh.function.trace

import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import dev.songzh.function.trace.fir.SimpleClassGenerator

class SimplePluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::SimpleClassGenerator
    }
}

package org.demiurg906.kotlin.plugin

import org.jetbrains.kotlin.config.CompilerConfigurationKey

object FunctionTracerConfigurationKeys {
    /** When `true` every non-inline, non-external function is traced. */
    val TRACE_ALL: CompilerConfigurationKey<Boolean> =
        CompilerConfigurationKey("functionTracer.traceAll")

    /**
     * Fully-qualified package name that contains `_funcTraceEnter` / `_funcTraceExit`.
     * Defaults to the package shipped with plugin-annotations.
     */
    val PACKAGE_PATH: CompilerConfigurationKey<String> =
        CompilerConfigurationKey("functionTracer.packagePath")
}


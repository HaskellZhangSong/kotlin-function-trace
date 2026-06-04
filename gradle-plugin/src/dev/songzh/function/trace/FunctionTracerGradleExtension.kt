package dev.songzh.function.trace

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/** Default package for the user-supplied trace hook functions. */
private const val DEFAULT_PACKAGE_PATH = "dev.songzh.function.trace"

/**
 * Gradle DSL extension exposed as `functionTracer { … }` in build scripts.
 *
 * ```kotlin
 * functionTracer {
 *     // true (default)  → every non-inline, non-external function is instrumented
 *     // false           → tracing is disabled entirely
 *     traceAll = true
 *
 *     // package that contains _funcTraceEnter / _funcTraceExit
 *     packagePath = "dev.songzh.function.trace"
 * }
 * ```
 */
open class FunctionTracerGradleExtension(objectFactory: ObjectFactory) {

    /**
     * When `true` (default), all non-inline, non-external functions are traced.
     * Set to `false` to disable tracing for an entire compilation.
     */
    val traceAll: Property<Boolean> =
        objectFactory.property(Boolean::class.java).convention(true)

    /**
     * Fully-qualified package name that contains `_funcTraceEnter` and
     * `_funcTraceExit`.  Defaults to `"dev.songzh.function.trace"`.
     */
    val packagePath: Property<String> =
        objectFactory.property(String::class.java).convention(DEFAULT_PACKAGE_PATH)
}




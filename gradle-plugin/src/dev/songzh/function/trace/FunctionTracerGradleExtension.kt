package dev.songzh.function.trace

/** Default package for the user-supplied trace hook functions. */
private const val DEFAULT_PACKAGE_PATH = "dev.songzh.function.trace"

/**
 * Gradle DSL extension exposed as `functionTracer { … }` in build scripts.
 *
 * Compatible with Gradle 7.x and 8.x:
 * ```kotlin
 * functionTracer {
 *     traceAll = true
 *     packagePath = "dev.songzh.function.trace"
 * }
 * ```
 *
 * Plain `var` properties are used instead of `Property<T>` so that the
 * simple `= value` assignment syntax works in Gradle 7.x Kotlin DSL.
 * (Gradle 8.x introduced direct `Property<T>` assignment via `=`; Gradle 7.x
 * requires `.set(value)` for `Property<T>` which is less ergonomic.)
 */
open class FunctionTracerGradleExtension {

    /**
     * When `true` (default), all non-inline, non-external functions are traced.
     * Set to `false` to disable tracing for an entire compilation.
     */
    var traceAll: Boolean = true

    /**
     * Fully-qualified package name that contains `_funcTraceEnter` and
     * `_funcTraceExit`.  Defaults to `"dev.songzh.function.trace"`.
     */
    var packagePath: String = DEFAULT_PACKAGE_PATH
}

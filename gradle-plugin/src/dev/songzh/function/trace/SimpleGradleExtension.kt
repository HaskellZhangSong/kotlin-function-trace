package dev.songzh.function.trace

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/** Default package that ships the trace runtime in plugin-annotations. */
private const val DEFAULT_PACKAGE_PATH = "dev.songzh.function.trace"

/**
 * Gradle DSL extension exposed as `functionTracer { … }` in build scripts.
 *
 * ```kotlin
 * functionTracer {
 *     // false  → only functions annotated with @Trace are instrumented (default)
 *     // true   → every non-inline function in the module is instrumented
 *     traceAll = true
 *
 *     // package that contains _funcTraceEnter / _funcTraceExit
 *     packagePath = "songzh.dev.function.trace"
 * }
 * ```
 */
open class SimpleGradleExtension(objectFactory: ObjectFactory) {

    /**
     * When `true`, all non-inline, non-external functions are traced.
     * When `false` (default), only functions annotated with `@Trace` are traced.
     */
    val traceAll: Property<Boolean> =
        objectFactory.property(Boolean::class.java).convention(false)

    /**
     * Fully-qualified package name that contains `_funcTraceEnter` and
     * `_funcTraceExit`.  Defaults to the package shipped with plugin-annotations.
     */
    val packagePath: Property<String> =
        objectFactory.property(String::class.java).convention(DEFAULT_PACKAGE_PATH)
}



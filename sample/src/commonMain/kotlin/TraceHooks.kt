package dev.songzh.function.trace

/**
 * Sample implementation of the function-tracer runtime hooks.
 *
 * The compiler plugin calls these two functions at every traced function's
 * entry and exit point.  Because [TraceRuntime] was removed from the
 * plugin-annotations artifact in v0.2.0, every consumer must supply its
 * own implementations in the package declared by `packagePath`
 * (defaults to `dev.songzh.function.trace`).
 *
 * Replace the bodies with whatever logging / telemetry system you use
 * in your project (Timber, SLF4J, OpenTelemetry, etc.).
 */
fun _funcTraceEnter(functionName: String) {
    println(">>> [TRACE] Entering $functionName")
}

fun _funcTraceExit(functionName: String) {
    println("<<< [TRACE] Exiting $functionName")
}


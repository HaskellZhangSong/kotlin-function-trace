package dev.songzh.function.trace

/**
 * Default runtime implementation of the entry trace hook.
 * Called by the compiler plugin at the start of every traced function.
 *
 * Override / replace this in your project and set `packagePath` to point
 * at your own package if you need a custom sink (e.g. structured logging).
 */
public fun _funcTraceEnter(functionName: String) {
    println(">>> [TRACE] Entering $functionName")
}

/**
 * Default runtime implementation of the exit trace hook.
 * Called by the compiler plugin just before every traced function returns.
 */
public fun _funcTraceExit(functionName: String) {
    println("<<< [TRACE] Exiting $functionName")
}


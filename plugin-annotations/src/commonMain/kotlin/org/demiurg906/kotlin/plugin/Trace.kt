package org.demiurg906.kotlin.plugin

/**
 * Mark a function with this annotation to have entry/exit trace calls injected
 * when the compiler plugin runs with `traceAll = false` (the default).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Trace


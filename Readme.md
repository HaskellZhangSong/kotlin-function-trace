# Function Tracer — Kotlin Compiler Plugin

A **K2 Kotlin compiler plugin** that automatically injects entry/exit trace calls into function bodies at compile time, giving you a zero-boilerplate call-trace log for your Kotlin code.

---

## How it works

For every traced function the plugin rewrites its IR body so that:

1. `_funcTraceEnter("<package.ClassName.functionName>")` is called at the very start.
2. `_funcTraceExit("<package.ClassName.functionName>")` is called just before every return path.

You supply the implementations of these two functions — the plugin is fully platform-agnostic and places no constraints on what the hooks do (print, log to a file, send to a telemetry backend, etc.).

```
>>> [TRACE] Entering com.example.Greeter.greet
<<< [TRACE] Exiting com.example.Greeter.greet
```

---

## Project modules

| Module | Purpose |
|---|---|
| `compiler-plugin` | K2 compiler plugin (IR pass) |
| `gradle-plugin` | Gradle plugin that wires the compiler plugin into any Kotlin build |

---

## Quick start

### 1. Apply the Gradle plugin

```kotlin
// settings.gradle.kts — add the plugin repository (local or published)
pluginManagement {
    repositories {
        mavenLocal()        // if installed locally via `./gradlew publishToMavenLocal`
        gradlePluginPortal()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.20"
    id("dev.songzh.function.trace") version "0.2.0"
}
```

### 2. Supply the trace hook functions

Define `_funcTraceEnter` and `_funcTraceExit` in the package declared by `packagePath` (defaults to `dev.songzh.function.trace`):

```kotlin
// e.g. src/main/kotlin/dev/songzh/function/trace/TraceHooks.kt
package dev.songzh.function.trace

fun _funcTraceEnter(functionName: String) {
    println(">>> [TRACE] Entering $functionName")
}

fun _funcTraceExit(functionName: String) {
    println("<<< [TRACE] Exiting $functionName")
}
```

> The plugin does **not** bundle any runtime hooks, keeping it platform-neutral. Wire in any logging backend (Timber, SLF4J, OpenTelemetry, `NSLog`, etc.) without pulling in unwanted dependencies.

### 3. Configure the plugin (optional)

```kotlin
// build.gradle.kts
functionTracer {
    // true (default) → every non-inline, non-external function is instrumented
    // false          → tracing is disabled entirely
    traceAll = true

    // Package that contains your _funcTraceEnter / _funcTraceExit implementations.
    // Defaults to "dev.songzh.function.trace".
    packagePath = "dev.songzh.function.trace"
}
```

---

## Custom trace runtime

Point the plugin at any package that contains `_funcTraceEnter` / `_funcTraceExit`:

```kotlin
functionTracer {
    packagePath = "com.example.mytrace"
}
```

```kotlin
// com/example/mytrace/TraceHooks.kt
package com.example.mytrace

fun _funcTraceEnter(functionName: String) {
    MyLogger.debug("ENTER $functionName")
}

fun _funcTraceExit(functionName: String) {
    MyLogger.debug("EXIT  $functionName")
}
```

The two functions **must not** themselves be traced (the plugin automatically skips functions named `_funcTraceEnter` / `_funcTraceExit` to prevent infinite recursion).

---

## What gets traced

| Scenario | Traced? |
|---|---|
| Regular function (`traceAll = true`, the default) | ✅ |
| `inline` function | ❌ always skipped |
| `external` function | ❌ always skipped |
| Lambda / anonymous function | ❌ always skipped |
| Constructor | ❌ always skipped |
| `_funcTraceEnter` / `_funcTraceExit` themselves | ❌ always skipped |

---

## Plugin extension points

| File | Role |
|---|---|
| `ir/FunctionTracerTransformer.kt` | Core IR transformer — injects `_funcTraceEnter` / `_funcTraceExit` |
| `ir/FunctionTracerIrGenerationExtension.kt` | Registers the transformer as an IR generation extension |
| `FunctionTracerPluginRegistrar.kt` | Reads `traceAll` / `packagePath` from compiler config and registers extensions |
| `FunctionTracerCommandLineProcessor.kt` | Exposes `traceAll` and `packagePath` as `-P plugin:…` compiler options |

---

## Publishing

To publish all artifacts to your local Maven repository for testing:

```bash
./gradlew publishToMavenLocal
```

To publish to Maven Central, configure your credentials in `~/.gradle/gradle.properties` and run:

```bash
./gradlew publish
```

---

## License

Copyright 2024 Song Zheng. Licensed under the [Apache License 2.0](LICENSE).

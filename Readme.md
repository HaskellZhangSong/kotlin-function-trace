# Function Tracer — Kotlin Compiler Plugin

A **K2 Kotlin compiler plugin** that automatically injects entry/exit trace calls into function bodies at compile time, giving you a zero-boilerplate call-trace log for your Kotlin code.

---

## How it works

For every traced function the plugin rewrites its IR body so that:

1. `_funcTraceEnter("<package.ClassName.functionName>")` is called at the very start.
2. `_funcTraceExit("<package.ClassName.functionName>")` is called just before every return path.

The default implementations (shipped in `plugin-annotations`) print to stdout:

```
>>> [TRACE] Entering com.example.Greeter.greet
<<< [TRACE] Exiting com.example.Greeter.greet
```

---

## Project modules

| Module | Purpose |
|---|---|
| `compiler-plugin` | K2 compiler plugin (FIR + IR passes) |
| `gradle-plugin` | Gradle plugin that wires the compiler plugin into any Kotlin build |
| `plugin-annotations` | Multiplatform library with the `@Trace` annotation and default `_funcTraceEnter`/`_funcTraceExit` implementations |

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
    id("dev.songzh.function.trace") version "0.1"
}
```

The plugin automatically adds `plugin-annotations` to your compile classpath, so no extra `dependencies` entry is needed.

### 2. Configure the plugin

```kotlin
// build.gradle.kts
functionTracer {
    // false (default) → only functions annotated with @Trace are instrumented
    // true            → every non-inline, non-external function is instrumented
    traceAll = true

    // Optional: point at your own runtime package (must contain _funcTraceEnter / _funcTraceExit)
    // Defaults to the implementations shipped with plugin-annotations.
    packagePath = "dev.songzh.function.trace"
}
```

### 3. Annotate selectively (when `traceAll = false`)

```kotlin
import dev.songzh.function.trace.Trace

class Greeter {
    @Trace
    fun greet(name: String): String {
        return "Hello, $name!"
    }

    // Not traced (no annotation, traceAll is false)
    fun helper() { }
}
```

With `traceAll = true` every non-inline function is traced automatically — no annotation required.

---

## Custom trace runtime

By default the plugin calls the `println`-based hooks that ship with `plugin-annotations`:

```kotlin
// dev.songzh.function.trace (plugin-annotations)
public fun _funcTraceEnter(functionName: String) {
    println(">>> [TRACE] Entering $functionName")
}

public fun _funcTraceExit(functionName: String) {
    println("<<< [TRACE] Exiting $functionName")
}
```

To use a custom sink (e.g. structured logging, file output, Android `Log`):

1. Define your own `_funcTraceEnter` / `_funcTraceExit` in a package of your choice.
2. Set `packagePath` in the Gradle configuration to that package:

```kotlin
functionTracer {
    traceAll = true
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

---

## What gets traced

| Scenario | Traced? |
|---|---|
| Regular function (`traceAll = true`) | ✅ |
| Function annotated with `@Trace` (`traceAll = false`) | ✅ |
| `inline` function | ❌ always skipped |
| `external` function | ❌ always skipped |
| Lambda / anonymous function | ❌ always skipped |
| Constructor | ❌ always skipped |

---

## Plugin extension points

| File | Role |
|---|---|
| `ir/FunctionTracerTransformer.kt` | Core IR transformer — injects `_funcTraceEnter` / `_funcTraceExit` |
| `ir/FunctionTracerIrGenerationExtension.kt` | Registers the transformer as an IR generation extension |
| `SimplePluginComponentRegistrar.kt` | Reads `traceAll` / `packagePath` from compiler config and registers extensions |
| `SimpleCommandLineProcessor.kt` | Exposes `traceAll` and `packagePath` as `-P plugin:…` compiler options |

---

## Tests

The Kotlin compiler test framework is set up under `compiler-plugin/testData`.

- **Box tests** (`testData/box/`) — compile and run a snippet; must return `"OK"`.
- **Diagnostic tests** (`testData/diagnostics/`) — verify FIR diagnostics against golden `.fir.txt` files.

To add a new test, place a `.kt` file in the appropriate directory and run:

```bash
./gradlew :compiler-plugin:generateTests
```

This regenerates the JUnit 5 test-class files under `test-gen/`. Run all tests with:

```bash
./gradlew :compiler-plugin:test
```

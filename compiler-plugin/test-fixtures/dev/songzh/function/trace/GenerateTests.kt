package dev.songzh.function.trace

import org.jetbrains.kotlin.generators.generateTestGroupSuiteWithJUnit5
import dev.songzh.function.trace.runners.AbstractBoxTest
import dev.songzh.function.trace.runners.AbstractDiagnosticTest

fun main() {
    generateTestGroupSuiteWithJUnit5 {
        testGroup(testDataRoot = "compiler-plugin/testData", testsRoot = "compiler-plugin/test-gen") {
            testClass<AbstractDiagnosticTest> {
                model("diagnostics")
            }

            testClass<AbstractBoxTest> {
                model("box")
            }
        }
    }
}

package dev.songzh.sample

open class Animal {

}

class Dog: Animal() {

}

class Cat: Animal() {

}

class Imp {
    private var x: Animal = Dog()

    fun swap(): Animal {
        when (x) {
            is Dog -> x = Cat()
            is Cat -> x = Dog()
            else -> {}
        }
        return x
    }
}

var x = 10
fun nonExp() {
    x = 15
    val y = 10
}

/**
 * Entry point for the Function Tracer sample.
 *
 * Run with:
 *   ./gradlew :sample:run
 *
 * All non-inline functions are traced automatically (traceAll = true by default).
 */
fun main() {
    val a = Imp()
    nonExp()
    println(a.swap())
    println("=== Factorial ===")
    computeFactorial2(5)
    println("=== Greeter demo ===")
    val greeter = Greeter()
    println(greeter.greet("Alice"))
    println()

    println("=== Calculator demo (nested calls) ===")
    val calc = Calculator()
    val result = calc.sumOfSquares(3, 4)
    println("sumOfSquares(3, 4) = $result")
    println()

    println("=== Order processor demo ===")
    val processor = OrderProcessor()
    val items = listOf(
        OrderItem("Widget", 9.99, 3),
        OrderItem("Gadget", 24.99, 1),
        OrderItem("Doohickey", 14.99, 2),
    )
    val total = processor.processOrder(items)
    println("Order total: $${"%.2f".format(total)}")
    exprBodyWithInlinedReturn()
    blockBodyWithConditionalInlinedReturn()

    // ── Additional test cases ────────────────────────────────────────────────
    println("\n=== Additional test cases ===")
    println("1.  multipleEarlyReturns(-5)  = ${multipleEarlyReturns(-5)}")
    println("1.  multipleEarlyReturns(0)   = ${multipleEarlyReturns(0)}")
    println("1.  multipleEarlyReturns(50)  = ${multipleEarlyReturns(50)}")
    println("2.  firstPositive([-1,-2,3])  = ${firstPositive(listOf(-1, -2, 3))}")
    println("2.  firstPositive([])         = ${firstPositive(emptyList())}")
    println("3.  countDown(10)             = ${countDown(10)}")
    println("3.  countDown(3)              = ${countDown(3)}")   // never hits x==5
    println("4.  safeDivide(10,2)          = ${safeDivide(10, 2)}")
    println("4.  safeDivide(10,0)          = ${safeDivide(10, 0)}")
    println("5.  riskyCompute(10,2)        = ${riskyCompute(10, 2)}")
    println("5.  riskyCompute(10,0)        = ${riskyCompute(10, 0)}")
    println("6.  letWithReturn(5)          = ${letWithReturn(5)}")
    println("6.  letWithReturn(-1)         = ${letWithReturn(-1)}")
    println("7.  alsoWithReturn(\"hello\") = ${alsoWithReturn("hello")}")
    println("7.  alsoWithReturn(\"hi\")   = ${alsoWithReturn("hi")}")
    println("8.  nestedRunReturns()        = ${nestedRunReturns()}")   // ← missing Exiting?
    println("9.  runFallthrough(5)         = ${runFallthrough(5)}")
    println("9.  runFallthrough(-3)        = ${runFallthrough(-3)}")
    println("10. multipleRunBlocks(5)      = ${multipleRunBlocks(5)}")
    println("10. multipleRunBlocks(-3)     = ${multipleRunBlocks(-3)}")
    println("10. multipleRunBlocks(0)      = ${multipleRunBlocks(0)}")
    println("11. grade(95)                 = ${grade(95)}")
    println("11. grade(75)                 = ${grade(75)}")
    println("12. categorize(-1)            = ${categorize(-1)}")
    println("12. categorize(0)             = ${categorize(0)}")
    println("13. deeplyNested(150)         = ${deeplyNested(150)}")
    println("13. deeplyNested(50)          = ${deeplyNested(50)}")
    println("13. deeplyNested(5)           = ${deeplyNested(5)}")
    println("14. factorialTailrec(5)       = ${factorialTailrec(5)}")
    println("15. firstOrDefault([1,2], 0)  = ${firstOrDefault(listOf(1, 2), 0)}")
    println("15. firstOrDefault([], 0)     = ${firstOrDefault(emptyList(), 0)}")
    println("16. \"ab\".repeatTwice()      = ${"ab".repeatTwice()}")
    println("17. alwaysThrows()            = (should throw)")
    try { alwaysThrows() } catch (e: IllegalStateException) { println("    caught: ${e.message}") }
    println("18. whenReturn(1)             = ${whenReturn(1)}")
    println("18. whenReturn(99)            = ${whenReturn(99)}")
    println("19. findItem([1,2,3], 2)      = ${findItem(listOf(1, 2, 3), 2)}")
    println("19. findItem([1,2,3], 9)      = ${findItem(listOf(1, 2, 3), 9)}")
    println("20. exprBodyRunFallthrough(5) = ${exprBodyRunFallthrough(5)}")
    println("20. exprBodyRunFallthrough(-1)= ${exprBodyRunFallthrough(-1)}")
}

// ── Patterns that exercise the IrExpressionBody / IrContainerExpression fixes ──

/** Expression body with a non-local return inside an inlined lambda. */
fun exprBodyWithInlinedReturn(): Int = run { return 42 }

/** Block body where an inlined lambda contains a conditional non-local return. */
fun blockBodyWithConditionalInlinedReturn(): Int {
    val x = run {
        if (true) return 10
        20
    }
    return x
}


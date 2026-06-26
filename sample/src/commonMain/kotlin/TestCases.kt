package dev.songzh.sample

// ── Test cases exercising every IR transformation path ──────────────────────

// 1. Multiple early returns (IrWhen with expression-position returns, each
//    branch result is an IrReturn → wrapped by ReturnWrapper in `else` branch;
//    final return → flat-expanded).
fun multipleEarlyReturns(n: Int): String {
    if (n < 0) return "negative"
    if (n == 0) return "zero"
    if (n > 100) return "large"
    return "normal($n)"
}

// 2. Return inside a for-each loop body (loop falls to `else` branch of
//    flatInstrumentReturns; ReturnWrapper wraps the IrReturn inside the loop).
fun firstPositive(items: List<Int>): Int {
    for (item in items) {
        if (item > 0) return item
    }
    return -1
}

// 3. Return inside a while loop.
fun countDown(start: Int): Int {
    var x = start
    while (x > 0) {
        x--
        if (x == 5) return x   // early exit from loop
    }
    return x
}

// 4. `return try { … } catch { … }` — IrTry is the VALUE of the IrReturn.
//    flatInstrumentReturns flat-expands: `_traceResult = IrTry(…); exit; return`.
fun safeDivide(a: Int, b: Int): String {
    return try {
        "ok: ${a / b}"
    } catch (e: ArithmeticException) {
        "err: division by zero"
    }
}

// 5. Try body with multiple early returns.  IrTry is a statement; its interior
//    IrReturn nodes are in expression position inside the try-result IrBlock and
//    catch IrBlock → processed by ReturnWrapper via `else` branch.
fun riskyCompute(a: Int, b: Int): String {
    try {
        if (b == 0) return "zero-divisor"
        return "ok: ${a / b}"
    } catch (e: ArithmeticException) {
        return "exception"
    }
}

// 6. Non-local return inside `let` (inline, can fall through).
//    IrReturnableBlock is in statement position with fallthrough → recurse with
//    target = fn.symbol; IrWhen branch wraps the return via ReturnWrapper.
fun letWithReturn(x: Int): Int {
    x.let {
        if (it > 0) return it * 2
    }
    return -1
}

// 7. Non-local return inside `also` (inline, can fall through).
fun alsoWithReturn(s: String): String {
    val sb = StringBuilder()
    sb.also {
        it.append(s)
        if (it.length > 3) return it.toString()
    }
    return "short"
}

// 8. Nested Nothing-typed IrReturnableBlocks — both outer and inner `run` have
//    Nothing type (all paths non-locally return to the function).
//    flatInstrumentReturns recurses into the outer block with innerTarget=outer.symbol,
//    then into the inner block with innerTarget=inner.symbol, but the actual
//    IrReturn targets fn.symbol → MISSED → no Exiting expected (known bug).
fun nestedRunReturns(): Int {
    run {
        run {
            return 99
        }
    }
    return 0  // unreachable
}

// 9. `run` that CAN fall through — non-Nothing type.
//    IrReturnableBlock in statement position with innerTarget = fn.symbol.
fun runFallthrough(x: Int): Int {
    val a = run {
        if (x > 0) return x      // non-local — targets fn.symbol
        x * -1
    }
    return a
}

// 10. Multiple `run` blocks in the same function, each with a conditional
//     non-local return.
fun multipleRunBlocks(x: Int): Int {
    val a = run { if (x > 0) return x; x * -1 }
    val b = run { if (x < 0) return x; x }
    return a + b
}

// 11. Expression body: `when` expression (IrExpressionBody / non-Unit non-Nothing).
//     buildInstrumentedBlockFromExpression captures result in _traceResult, calls exit.
fun grade(score: Int): String = when {
    score >= 90 -> "A"
    score >= 80 -> "B"
    score >= 70 -> "C"
    score >= 60 -> "D"
    else -> "F"
}

// 12. when expression where every branch is a non-local return —
//     the overall expression type is Nothing so the function's type
//     is satisfied, but the return target is fn, not a retBlock.
fun categorize(x: Int): String {
    when {
        x < 0  -> return "negative"
        x == 0 -> return "zero"
        else   -> return "positive"
    }
}

// 13. Deeply nested if-returns (multiple IrWhen at statement level).
fun deeplyNested(x: Int): Int {
    if (x > 0) {
        if (x > 10) {
            if (x > 100) return x / 100
            return x / 10
        }
        return x
    }
    return 0
}

// 14. Tail-recursive function — the Kotlin compiler rewrites this into a loop
//     before or after our plugin runs; tests interaction with tailrec lowering.
tailrec fun factorialTailrec(n: Int, acc: Int = 1): Int {
    if (n <= 1) return acc
    return factorialTailrec(n - 1, acc * n)
}

// 15. Generic function (type parameter should not affect instrumentation).
fun <T> firstOrDefault(list: List<T>, default: T): T {
    for (item in list) return item
    return default
}

// 16. Extension function.
fun String.repeatTwice(): String = this + this

// 17. Function that always throws (Nothing return type).
//     Should show Entering but NOT Exiting (exception escapes the function).
fun alwaysThrows(): Nothing = throw IllegalStateException("intentional")

// 18. Function with return inside a `when` subject expression.
fun whenReturn(n: Int): String {
    return when (n) {
        1 -> "one"
        2 -> "two"
        else -> "other"
    }
}

// 19. Function returning nullable type.
fun findItem(list: List<Int>, target: Int): Int? {
    for (item in list) {
        if (item == target) return item
    }
    return null
}

// 20. Single-expression body with inline lambda that can fall through
//     (`run { if (cond) return 42; 0 }` — IrReturnableBlock is the expression body).
fun exprBodyRunFallthrough(x: Int): Int = run { if (x > 0) return 1; 0 }


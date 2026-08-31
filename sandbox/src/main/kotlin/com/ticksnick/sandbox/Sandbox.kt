package com.ticksnick.sandbox

import com.ticksnick.Profiler
import com.ticksnick.op

fun main() {
    val Request = Profiler.registerCoarse("request")
    val Work = Profiler.registerFine("work")

    Profiler.start(stepMillis = 1.0)

//    val deadline = System.nanoTime() + 3_000_000_000L
    var sink = 0L

    op(Request) {
        sink += op(Work) { work() }
    }

//    while (System.nanoTime() < deadline) {
//        op(request) {
//            sink += op(work) { placeholder() }
//        }
//    }

    println(Profiler.stop().render())
    println("(sink: $sink)")
}

private fun work(): Long {
    var s = 0L
    for (i in 0 until 2_000_000) s = s * 31 + i
    return s
}

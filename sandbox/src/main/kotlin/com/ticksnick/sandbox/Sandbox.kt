package com.ticksnick.sandbox

import com.ticksnick.Profiler
import com.ticksnick.op
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

fun main() {
    val Request = Profiler.registerCoarse("request")
    val Work1 = Profiler.registerCoarse("work1")
//    val Work2 = Profiler.registerFine("work2")

    Profiler.start(stepMillis = 1.0)

//    val deadline = System.nanoTime() + 3_000_000_000L
    var sink = 0L

    val pool = Executors.newFixedThreadPool(10)

    op(Request) {
        repeat(10) {
            val threads = mutableListOf<Future<*>>()

//            threads.addAll((1..3).map {
//                pool.submit {
//                    op(Work2) { sink += large1() }
//                }
//            })

            for (i in 0..<10_000) {
                val threads2 = (1..3).map {
                    pool.submit {
                        op(Work1) { sink += tiny1() }
                    }
                }
                threads2.forEach { it.get() }
            }

            threads.forEach { it.get() }
        }
    }

    pool.shutdown()
    pool.awaitTermination(10, TimeUnit.SECONDS)
//
//    (1..2)
//        .map {
//            thread {
//                op(Request) {
//                    sink += op(Work1) {
//                        work1()
//                    }
//
//                    sink += op(Work2) {
//                        work2()
//                    }
//                }
//            }
//        }
//        .forEach { it.join() }

//    while (System.nanoTime() < deadline) {
//        op(request) {
//            sink += op(work) { placeholder() }
//        }
//    }

    println(Profiler.stop().render())
    println("(sink: $sink)")
}

private fun tiny1(): Long {
    var s = 0L

    for (i in 0 until 1000) s = s * 31 + i

    return s
}

private fun tiny2(): Long {
    var s = 0L
    for (i in 0 until 1000) s = s * 31 + i
    return s
}

private fun large1(): Long {
    var s = 0L

    for (i in 0 until 1_000_000_000) s = s * 31 + i

    return s
}
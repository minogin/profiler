package com.minogin.profiler

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.roundToInt

/**
 * Value sink. Whatever the busy loop churns out drains here at the end of every timed
 * section. Without it the JIT is free to drop the loop as dead code.
 */
object Sink {
    @Volatile
    @JvmField
    var value: Long = 0

    fun consume(v: Long) {
        value = value xor v
    }
}

/**
 * Busy loop. One step is an xor-shift: a dependent chain of shifts and xors.
 *
 * This used to be an LCG (`s * A + B`), which does not work: C2 unrolls the loop and folds the
 * constants, `(s*A+B)*A+B == s*A^2 + (BA+B)`, collapsing sixteen iterations into a single
 * multiply. The measured cost dropped to 0.05 ns per iteration — a fifth of a cycle, which
 * cannot happen. An xor-shift is a linear map over GF(2); C2 does not compose such matrices,
 * so there is nothing to fold. Its cost is also data-independent, unlike a multiply.
 *
 * The function is pure on purpose: the value must flow out and eventually reach a field,
 * or the whole loop is dead code.
 */
fun burn(state: Long, iters: Int): Long {
    var s = state
    var i = 0
    while (i < iters) {
        s = s xor (s shl 7)
        s = s xor (s ushr 9)
        i++
    }
    return s
}

/**
 * Below this cost per iteration the busy loop cannot be real: shift, xor, shift, xor is four
 * cycles of dependency. Anything less means the loop got optimised away and the bench lies.
 */
const val MIN_PLAUSIBLE_NS_PER_ITER = 0.3

/**
 * Runtime-measured cost of a busy-loop iteration. [nsPerCall] is the fixed per-call surcharge
 * (prologue, loop setup), [nsPerIter] is the slope.
 */
class Calibration(val nsPerIter: Double, val nsPerCall: Double, val r2: Double) {
    val minNanos: Double get() = nsPerCall + nsPerIter

    fun itersFor(nanos: Double): Int = max(1.0, ((nanos - nsPerCall) / nsPerIter).roundToInt().toDouble()).toInt()

    override fun toString(): String = String.format(
        Locale.ROOT, "%.4f ns/iteration + %.2f ns/call (R2 = %.5f)", nsPerIter, nsPerCall, r2
    )
}

/**
 * Calibration points cover the range the bench actually works in: from the shortest operation
 * (20 ns — a couple dozen iterations) to the longest (2 us). Extrapolating past the edge of the
 * range is exactly where calibration goes most wrong.
 */
private val CALIBRATION_POINTS = intArrayOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048)

/** Warms up the busy loop itself: without this the first calibration measures the interpreter. */
fun warmUpBurn(millis: Long) {
    var s = 1L
    val end = System.nanoTime() + millis * 1_000_000
    var n = 16
    while (System.nanoTime() < end) {
        var r = 0
        while (r < 4000) {
            s = burn(s, n)
            r++
        }
        n = if (n >= 4096) 16 else n * 2
    }
    Sink.consume(s)
}

/**
 * Calibration. For every n we time a batch of reps calls (nanoTime around the batch, not around
 * the call), take the median over several trials, then least-squares fit the points
 * (n, ns per call). The slope is the cost of an iteration, the intercept the cost of a call.
 */
fun calibrate(trials: Int = 9): Calibration {
    val xs = CALIBRATION_POINTS
    val ys = DoubleArray(xs.size)
    for (i in xs.indices) {
        val samples = DoubleArray(trials) { timeBurnPerCall(xs[i]) }
        samples.sort()
        ys[i] = samples[trials / 2]
    }

    val n = xs.size
    var sx = 0.0; var sy = 0.0
    for (i in 0 until n) { sx += xs[i]; sy += ys[i] }
    val mx = sx / n; val my = sy / n
    var sxy = 0.0; var sxx = 0.0
    for (i in 0 until n) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
    val slope = sxy / sxx
    val intercept = my - slope * mx

    var ssRes = 0.0; var ssTot = 0.0
    for (i in 0 until n) {
        val fit = intercept + slope * xs[i]
        ssRes += (ys[i] - fit) * (ys[i] - fit)
        ssTot += (ys[i] - my) * (ys[i] - my)
    }
    return Calibration(slope, intercept, 1.0 - ssRes / ssTot)
}

/** A fitted iteration count and the duration it actually achieves on a single thread. */
class Fit(val iters: Int, val nanos: Double)

/**
 * Fits the iteration count to a target duration.
 *
 * The linear model from [calibrate] is only a seed and cannot be trusted on its own: the loop
 * gets unrolled, and an iteration does not cost the same at twenty iterations as at two
 * thousand. A single line across the whole range comes out with a negative intercept and misses
 * short operations by tens of percent. So the result is settled by measuring at the very point
 * where the operation lives, in proportional steps.
 */
fun refineIters(targetNanos: Double, seed: Int, rounds: Int = 10): Fit {
    var iters = seed
    var best = Fit(iters, Double.NaN)
    var bestErr = Double.MAX_VALUE
    repeat(rounds) {
        val actual = measureBurn(iters)
        val err = abs(actual / targetNanos - 1.0)
        if (err < bestErr) {
            bestErr = err
            best = Fit(iters, actual)
        }
        if (err < 0.005) return best
        val next = max(1L, (iters * targetNanos / actual).roundToLong()).toInt()
        if (next == iters) return best
        iters = next
    }
    return best
}

/** Median over several batches: nanoTime around the batch, not around the call. */
fun measureBurn(iters: Int, trials: Int = 5): Double {
    val a = DoubleArray(trials) { timeBurnPerCall(iters) }
    a.sort()
    return a[trials / 2]
}

private fun timeBurnPerCall(iters: Int): Double {
    val reps = repsForIters(iters)
    var s = 1L
    val t0 = System.nanoTime()
    var r = 0
    while (r < reps) {
        s = burn(s, iters)
        r++
    }
    val t1 = System.nanoTime()
    Sink.consume(s)
    return (t1 - t0).toDouble() / reps
}

/** Enough repetitions for a batch of about 2 ms, so the cost of nanoTime smears out to nothing. */
private fun repsForIters(iters: Int): Int {
    val estNanos = 1.5 * iters + 5.0
    return max(200.0, 2_000_000.0 / estNanos).toInt()
}

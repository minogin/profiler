package com.minogin.profiler

/**
 * The two kinds of thing you can label, and the one difference between them.
 *
 * **Fine and coarse is a property of the instrument, not of your program.** A fine label is a slot
 * write and a counter, about 2 ns, and it gives a share of time and a call count. A coarse label
 * allocates a context and takes two timestamps, about 40 ns, and it gives everything the fine one
 * does *plus* the measured duration of every execution, percentiles over them, and a breakdown of
 * what ran inside. Coarse is strictly more, so the only question is whether the operation is long
 * enough to afford it — `d >= max(800 ns, 4 us x share)`, and the report tells you when it is not.
 *
 * That is why the choice is made **once, at registration**, and never mentioned again. Everywhere
 * else there is one verb:
 *
 * ```
 * val parse   = Profiler.registerFine("parse")     // FineOp
 * val request = Profiler.registerCoarse("request") // CoarseOp
 *
 * op(parse)   { ... }
 * op(request) { ... }
 * ```
 *
 * **What that buys, beyond reading better.** The report says, in as many words, *"the operation
 * wants a coarse label for its per-execution statistics"*. Acting on that advice used to mean
 * changing the registration *and* every call site, because the wrapper's name encoded the tier. Now
 * it is one word in one place, and reversible if the cost turns out not to be worth it.
 *
 * **And it is what makes the two id spaces safe.** Both count from zero, so before this the first
 * fine operation and the first coarse one were both the integer `0` — `op(request)` compiled, ran,
 * and reported a plausible wrong answer. Two distinct types cannot be confused, and both erase to a
 * bare `int`, so nothing is paid for it at run time. The remaining hole is a caller from Java, where
 * value classes erase and the mangling makes these uncallable anyway: see `ideas.md` item 26.
 */

/**
 * A registered fine operation: something short and hot, measured by sampling a slot.
 *
 * Obtained from [Profiler.registerFine] and nowhere else — the constructor is internal, so a handle
 * always names something that was registered. [id] is readable because a caller may reasonably key
 * their own array by it.
 */
@JvmInline
value class FineOp internal constructor(val id: Int) {
    /** `fine#3`, so a number that has left the type system is still unambiguous in a log. */
    override fun toString(): String = "fine#$id"
}

/**
 * A registered coarse operation: a whole logical unit of work, timed exactly at both ends.
 *
 * Obtained from [Profiler.registerCoarse] and nowhere else, for the reason [FineOp] gives.
 */
@JvmInline
value class CoarseOp internal constructor(val id: Int) {
    /** `coarse#0`, and deliberately distinguishable from `fine#0`, which is a different operation. */
    override fun toString(): String = "coarse#$id"
}

# profiler

A profiler for operations too short to profile — finding out which of twenty nanosecond-scale
operations eats your time, by sampling labels instead of measuring durations.

The goal is a library you drop into an application. What is here today is the method and the
evidence that it works: a synthetic workload whose true answer is known independently, so the
sampler's answer can be checked against it rather than believed.

## The problem

Your application runs a few dozen distinct operations, millions of times each, and every one of
them takes tens to hundreds of nanoseconds. Which is the bottleneck?

An in-memory graph engine is the example this started from — expanding a frontier, probing a hash
map, scoring a node — but nothing here is specific to graphs. Any system with hot, short, repeated
operations has the same problem, and ordinary profilers cannot answer it.

- **Sampling stack traces at millisecond intervals** cannot see a 200 ns operation as a distinct
  thing. After inlining it may not exist as a frame at all.
- **Instrumenting with a timer does not work either.** `System.nanoTime()` costs twenty to thirty
  nanoseconds. Timing a 200 ns operation with it distorts the answer by tens of percent.
- **Coroutines break the attribution.** The thread stack does not correspond to the logical task;
  work is spread across dispatcher threads and suspensions cut the trail.

So the usual fallback is to count operations rather than time them — which tells you *how often*,
never *how long*.

## The idea

Don't measure the operation. **Count how often a random glance catches you inside it.**

Every worker thread has a slot holding the id of the operation it is currently in. Entering writes
the id; leaving restores whatever was there before. A separate thread wakes every millisecond,
reads all the slots, and increments a counter per id.

```kotlin
inline fun <T> op(id: Int, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.getOpaque()
    slot.setOpaque(id)
    slot.count(id)
    try { return body() } finally { slot.setOpaque(prev) }
}
```

The probability that a sample catches a thread inside operation 7 *is* the share of time that
operation takes. You never need to catch an individual call — accuracy comes from the number of
samples, not from the resolution of a clock. A minute at 8 threads gives about half a million
samples, so an operation holding 3% of the time collects fifteen thousand hits.

Restoring the previous value rather than clearing it is what lets operations nest.

## Does it work?

That is what most of this repository is: a synthetic workload whose true answer is known
independently, so the sampler's answer can be checked against it.

Twenty operations spanning 20 ns to 2 µs, nested four levels deep, driven by a shuffled schedule
across N threads. The truth is computed two independent ways and cross-checked before the sampler
is allowed anywhere near it — once by configuration, once by measuring the operations in batches
afterwards. If those two disagree the bench declares itself broken and refuses to continue.

**Result at 476,000 samples, 8 threads, 1 ms sampling step:**

| operation | true share | sampler | error |
|---|---|---|---|
| popFrontier | 18.745% | 18.699% | −0.25% |
| pushFrontier | 12.256% | 12.133% | −1.00% |
| scoreNode | 8.940% | 9.075% | +1.51% |
| filterNode | 8.331% | 8.331% | −0.00% |
| markVisited | 7.931% | 8.127% | +2.48% |
| … | | | |
| tinyStep (20 ns) | 3.204% | 2.706% | **−15.5%** |

The **top ten of twenty operations come out in exactly the same order as the truth**, and the
largest consumers — the ones you would actually act on — are accurate to within a percent of
themselves. Errors concentrate on the shortest operations and on parents, and cannot reorder
anything.

**The cost of measuring:** the hook is 1.7–2.3 ns per call, roughly 2% of a call-weighted mean
operation. Most of that is the thread-local lookup; the slot writes are ordinary stores.

Getting there took removing a `volatile` — on x86 a volatile store needs a memory barrier, and two
of those per call cost 16% of throughput. Opaque access emits no barrier while still preventing
the JIT from optimising the write away. It compiles to the same instruction a relaxed store in C
or Rust would, which is why a native implementation would not help.

## Running it

Needs a JDK; Gradle provisions the toolchain itself.

```bash
./gradlew run                                  # 60 s, half your cores, sampler on
./gradlew run --args="--verify"                # sampler against the truth, four sample sizes
./gradlew run --args="--sweep=1,2,4,8,16"      # the bench across thread counts
./gradlew run --args="--hook"                  # what the instrumentation costs
```

Useful flags: `--threads`, `--seconds`, `--step` (sampling interval, ms), `--active` (starve some
threads), `--labels=off`, `--sampler=off`, `--jitter=off`.

Everything prints to the console. There is no UI, no output file and no configuration.

## What is here

```
src/main/kotlin/com/minogin/profiler/
  Workload.kt   the twenty operations, the call graph, the schedule
  Burn.kt       the busy loop, calibration, iteration fitting
  Bench.kt      worker threads and the stages they are driven through
  Sampler.kt    slots, the hook, the sampling thread
  Main.kt       orchestration, the two truths, the verdicts
docs/
  profiler.md   the idea, and what was known about prior art
  plan.md       phases and where each one stands
  findings.md   what we learned, with the measurement behind every claim
```

`findings.md` is the interesting one if you have ever tried to measure something this small. It
records, among other things: how C2 silently ate the first busy loop by folding constants through
an unrolled loop; three separate places where measuring things sequentially aliased clock drift
onto the comparison; why 8 threads is the worst case for measurement stability on a hybrid CPU
while 16 is fine; and why percentage points cannot tell noise from bias while standard errors can.

## Status

The method is validated for **self** time: the bench, the sampler and the verification are done,
and the numbers above are what came out.

Still between here and a usable library:

- **Inclusive time.** The slot holds the innermost operation, so a parent's share is only the work
  it does outside its children — `frontierStep` is 5.8% self against 51.9% inclusive, and those
  point at completely different fixes.
- **Thread state and an empirical parallelism coefficient.** The sampler snapshots every thread at
  one instant, so "how many were busy at once" is already in the data. Ordinary profilers destroy
  that by summing per thread.
- **Coroutines.** Without a bridge the method does not merely lose data, it invents it: a coroutine
  that suspends without clearing its slot leaves its label on a thread that goes on to do something
  else entirely.
- **The library surface.** Two ways of placing labels, because operations do not always coincide
  with method boundaries: `@Profiled("expand")` on a method transformed by a bytecode agent, and
  explicit calls for a loop body or half a method. Plus operations registered at runtime rather
  than from a fixed array, and results read through an API instead of `println`.
- **JFR output.** As the transport, not the mechanism — an event per operation is hopeless at
  these costs, but one aggregated event per second lands in a format people already have tooling
  for.

Constraint carried throughout: it must not require you to hand over your thread creation. That
ruled out an otherwise attractive optimisation, and it is why the sampler is built the way it is.

See `docs/plan.md` for where things stand, and `docs/profiler.md` for the design and the prior art.

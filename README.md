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

## Using it

Everything a caller needs, and there is nothing else:

```kotlin
val parse = Profiler.register("parseRecord")     // once, at startup — keep the id

Profiler.start(stepMillis = 1.0)

s = op(parse) { parseRecord(input) }             // wrap the work; nesting is fine

println(Profiler.stop().render())
```

`./gradlew run --args="--demo"` runs exactly that against a toy workload and prints the report, so
there is a working example to copy. Up to 256 operations, registered by name at runtime. Call
`Profiler.release()` when a thread exits, or dead threads keep reading as idle ones.

**When the boundary is not a block** — a listener, a before/after callback, a span across several
methods — there is an explicit form. Reach for it second, because it has no `finally` and so it can
leak:

```kotlin
Profiler.enter(rule); … ; Profiler.exit()        // nests with op { } in either order

Profiler.expectBalanced()                        // at a point the thread should be quiescent
```

A leaked label bills every later sample on that thread to the leaked operation — silently, and the
number still looks plausible. `expectBalanced()` is how that surfaces where it happened rather than
as a finding at the end; the report counts what it found either way.

**For an operation under ~50 ns**, do not label it individually — the hook is a visible fraction of
it, the sampler reads it low, and the compiler can move work across the boundaries of adjacent short
labels. Label the loop and say how many units it covers:

```kotlin
op(probe, times = keys.size) { for (k in keys) table.find(k) }
```

**One trap worth knowing.** A label is only a boundary if the compiler cannot see through it. Three
adjacent tiny operations whose work has compile-time-constant bounds get unrolled and interleaved,
and the shares come out wrong — we measured 0.46% where 8.7% was correct. Opaque slot writes stop
the labels being reordered against *each other*; they do not fence anything else. See
`docs/findings.md`.

## Running the bench

Needs a JDK; Gradle provisions the toolchain itself.

```bash
./gradlew run                                  # 60 s, half your cores, sampler on
./gradlew run --args="--verify"                # sampler against the truth, four sample sizes
./gradlew run --args="--sweep=1,2,4,8,16"      # the bench across thread counts
./gradlew run --args="--hook"                  # what the instrumentation costs
```

`--demo` uses only the public API and touches none of the bench machinery.

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
  StackCost.kt  what a cross-thread stack costs, which decides whether we may ever take one
  Main.kt       orchestration, the two truths, the verdicts
trial/          the fine tier pointed at Apache Calcite, and the harness that did it
trial-lucene/   the same pointed at Apache Lucene: the corpus, the wrappers, four configurations
trial-common/   shared by both trials, JDK only: the JFR recording and stack analysis
docs/
  index.md      what each document is for — start here
  tldr.md       the whole project in plain words, for a five-minute read
  profiler.md   the idea, and what was known about prior art
  plan.md       phases and where each one stands
  findings.md   what we learned, with the measurement behind every claim
  trial.md      the trial on Apache Calcite: the finding, and where the tool got in the way
  trial-lucene.md  the trial on Apache Lucene: concurrent, and against a timed-wrapper profiler
  case.md       where existing tools fall short, and therefore what this one is for
  ideas.md      things worth doing that are not yet decided
```

`findings.md` is the interesting one if you have ever tried to measure something this small. It
records, among other things: how C2 silently ate the first busy loop by folding constants through
an unrolled loop; three separate places where measuring things sequentially aliased clock drift
onto the comparison; why 8 threads is the worst case for measurement stability on a hybrid CPU
while 16 is fine; and why percentage points cannot tell noise from bias while standard errors can.

## Where it is going

What works today is the **fine tier** — physical operations, identified by an integer in a
thread-local slot and measured by sampling. That answers *which operation is hot*.

The tool being built has a second tier above it. **Coarse operations** are logical units of work —
expanding a frontier, applying a filter set, serving a query — thousands of executions rather than
billions, and crossing thread boundaries freely. Those get a real context object: allocated,
propagated across hand-offs, timestamped at both ends.

**The boundary is roughly one microsecond**, and it comes from what that context costs — about
40 ns per execution against 2 ns for a fine label. Below a microsecond the instrument would be a
visible fraction of the operation, so fine is the only tier that works; that is its reason to exist.
Above a microsecond both tiers work and it becomes a choice. Neither has an upper limit — Calcite's
planner rules are hundreds of microseconds each and the fine tier measured them correctly. The
derivation and the cases the rule cannot cover are in
[docs/profiler.md](docs/profiler.md#where-the-boundary-is).

The two meet at the slot, which carries the current fine operation *and* the current coarse
context. So every sample records the pair, and the result is not just a list of hot operations:

> Of the 400 ms of CPU under "apply filters", 180 ms was `matchCondition`, and it ran at 3.7×
> parallelism.

Neither tier gives you that alone. Fine sampling says what is hot but not what it was for; coarse
spans say where the time went but not why. The parallelism figure needs both — CPU comes from
samples across every thread, the span from the context, and their ratio is how well that operation
actually spread out.

**Still to build:** the coarse tier, propagation across thread boundaries (executors, coroutines,
futures), thread state and a whole-application parallelism coefficient, the library surface
(annotations plus a bytecode agent, and explicit calls where operations do not align with methods),
and JFR as an output format.

**Constraint carried throughout:** it must not require you to hand over your thread creation. That
ruled out an otherwise attractive optimisation, and it is why the sampler is built the way it is.

See `docs/plan.md` for where things stand, `docs/trial.md` and `docs/trial-lucene.md` for the tool
used in anger on somebody else's code, `docs/case.md` for the case against the existing tools built out of observed failures,
and `docs/profiler.md` for the design and the prior art.

# profiler

A profiler for operations too short to profile — finding out which of twenty nanosecond-scale
operations eats your time, by sampling labels instead of measuring durations.

**v0.1.0** — Apache-2.0, one dependency (the Kotlin stdlib), verified on a synthetic workload whose
true answer is known independently and on three foreign codebases: Apache Calcite, Apache Lucene and
Netty.

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.minogin:profiler:v0.1.0") }
```

### What it does, and what it does not

It is **0.1** because the method is measured and the public API has not yet met anybody else's code.
The API may move; the numbers should not.

**It answers:** of the operations I labelled, which is eating the time — as a ranked list of shares
over thread-time, with call counts, an implied per-call duration, how much of each was spent waiting
rather than working, its wall-clock footprint, how many threads were inside it at once, and **a
measured bound on how wrong the shares can be**. It reaches operations of tens of nanoseconds, which
is the regime stack profilers cannot see into, and it has no upper limit — Calcite's labels were
millisecond-sized and it handled them to within a percentage point of JFR.

**And for operations big enough to afford it — a request, a query, a plan — a second kind of label
answers what the first cannot:** how long *one execution* took. `coarse(type) { }` timestamps both
ends, so you get executions, mean, p50/p90/p99 and max **measured rather than sampled**, the fine
breakdown of what ran underneath it, and `mean − busy/exec` as the time it spent not running. On
Calcite that reads *of the 9.08 ms a plan takes, `FilterIntoJoinRule` is 25.2%* — a sentence neither
tier can produce alone.

**It does not answer:**

- **How long did one execution of a *fine* operation take.** No per-execution durations there and so
  no percentiles, ever: sampling gives a share of time, not a distribution. An operation that is
  50 ns normally and 100 µs when it hits a lock reports the average and no execution was ever that.
  Above about a microsecond, a coarse label answers this properly; below it, nothing can.
- **Where a request went across a thread hand-off.** Propagation is not built. A coarse context stays
  on the thread that created it, so if your operation fans out to a pool, the work on those threads
  is *missing from its CPU and shows up as waiting* — measured on Lucene at 24.5% against 0.0% for
  the same code single-threaded. The report's own documentation says so; read `waiting` as *"the
  thread holding this context was not running"* until this lands.
- **What would I save by removing it.** A share is where time went, not what deleting it buys. In
  the Calcite trial an operation holding 46% was worth **275×** when removed, because it was creating
  work for everything else. The report says so at the foot of every run.

**Untested, and so unclaimed:** coroutines and virtual threads. The design argues the fine tier is
structurally safe with coroutines and the registry is now bounded for virtual threads, but no trial
here creates either, so neither is a measured claim. Treat them as unknown.

**Two sharp edges**, both of which the report now tells you about rather than leaving you to find:
call `Profiler.release()` when a thread exits (there is a safety net, but it waits on a garbage
collection), and on event loops whose waiting is native — Netty, Vert.x, anything reactive — the
error bound is unavailable, and the report says so instead of printing a number it cannot justify.

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

`./gradlew :run --args="--demo"` runs exactly that against a toy workload and prints the report, so
there is a working example to copy. Up to 256 operations, registered by name at runtime.

`Profiler.start()` also takes `strict` (below), `sampleState = false` to switch off the per-sample
thread-state read, and `stepMillis` — a smaller step is more samples and more sampler CPU.

**For a whole request, query or plan, add a coarse label as well:**

```kotlin
val request = Profiler.registerCoarse("request")   // a separate id space

coarse(request) {                                  // timestamps both ends
    handle(input)                                  // your op(...) labels nest inside
}
```

That gives you the second table: how long each execution took, with percentiles, and what ran inside
it. `enterCoarse(id)` / `exitCoarse()` exist for a boundary that is two callbacks rather than a block
— they have no `finally`, so pair them with `Profiler.expectBalanced()` at a point the thread should
be quiescent, which for a coarse label means *between* requests and not inside one.

**If the work is handed to a pool, carry the context with it.** A context lives in the slot of the
thread that made it, so a request that fans out leaves it behind: the helper threads run under no
context, and the caller's span reports the time they spent as *waiting*. Wrap the pool once —

```kotlin
val pool = Executors.newFixedThreadPool(8).propagating()

coarse(request) {
    pool.invokeAll(shards.map { Callable { search(it) } })   // each shard runs inside `request`
}
```

`Runnable.propagating()` and `Callable.propagating()` wrap a single task, and `captureCoarse()` /
`withCoarse(ctx) { }` are the two calls everything here is built from — for a thread you created
yourself, which nothing can wrap for you. Capture happens where you **wrap**, not where the task
runs, so wrap on the thread that is forking.

`schedule()` on a scheduled pool deliberately does *not* propagate: a task that runs in five minutes
will usually outlive the execution that scheduled it, and crediting it there invents attribution
rather than losing it. A hand-off you simply forget fails the recoverable way instead — the work
loses its attribution and turns up in the report's *"inside NO coarse span"* line, which names the
operations it caught.

**Do not put one on something small.** A context costs about 40 ns; the rule is
`d ≥ max(800 ns, 4 µs × share)`, and the report tells you when a label breaks it — exactly, because
unlike the fine floor it is comparing a duration it measured rather than one it inferred.

Call `Profiler.release()` when a thread exits. There is a safety net — the sampler reclaims the
slots of threads that have died — but it waits on a garbage collection, and until then a dead
thread reads as an idle one and inflates the denominator every share is taken over.

### What comes out

```
86,597 labelled samples over 12.1 s, 11,933 ticks at 1.006 ms, 8 threads
labels cover 87.10 s of the 95.97 s of thread-time observed (90.8%); 8.87 s was outside every label
threads were on CPU 98.68% of sampled wall time
  inside labelled work it was 98.55%, and that is what bounds the shares
at most 1.47 pp of any share is a thread waiting rather than working
so the ranking is trustworthy
================================================================================================
operation            share  occupancy  waiting   elapsed   in flight         calls    hits  noise  impl/call
flushBatch         44.250%    38.54 s     0.0%   11.75 s      3.28/8   288,514,362   38319  0.51%   133.6 ns
validateRecord     41.222%    35.91 s     0.0%   11.68 s      3.07/8   288,514,362   35697  0.53%   124.5 ns
parseRecord         9.478%     8.26 s     0.3%    6.14 s      1.34/8   288,514,362    8208  1.10%    28.6 ns
indexRecord         5.050%     4.40 s     0.0%    3.73 s      1.18/8   288,514,362    4373  1.51%    15.2 ns
```

Three things to know before you trust a table like that:

- **`at most 1.47 pp`** is an error bar that holds for every row at once. It is the report telling
  you how much of the shares is waiting rather than working, and it can come out saying *nothing
  here bounds the shares* — which is honest, and means your labels do not cover where the time went.
- **`noise` is `1/√hits`.** Two rows differing by less than their noise are tied, not ranked.
- **The top row of that specimen is wrong**, and the report says so forty lines further down. The
  demo leaks a label on purpose; `flushBatch` at 44% is other operations' time billed to it. The
  number looks completely plausible, which is the point.

**[docs/output.md](docs/output.md) explains every column and every warning**, using that same run.
The report also prints a short legend at its own foot, so you need neither this file nor a browser
to read one.

**When the boundary is not a block** — a listener, a before/after callback, a span across several
methods — there is an explicit form. Reach for it second, because it has no `finally` and so it can
leak:

```kotlin
Profiler.enter(rule); … ; Profiler.exit()        // nests with op { } in either order

Profiler.expectBalanced()                        // at a point the thread should be quiescent
```

A leaked label bills every later sample on that thread to the leaked operation — silently, and the
number still looks plausible. `expectBalanced()` is how that surfaces where it happened rather than
as a finding at the end; the report counts what it found either way. Under `strict` — the default —
the first one stops the session and names the operation, because a leak does not make a number
imprecise, it makes it somebody else's. Pass `strict = false` for labels on code you cannot fix.

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
./gradlew :run                                 # 60 s, half your cores, sampler on
./gradlew :run --args="--verify"               # sampler against the truth, four sample sizes
./gradlew :run --args="--sweep=1,2,4,8,16"     # the bench across thread counts
./gradlew :run --args="--hook"                 # what the instrumentation costs
./gradlew :run --args="--leakcheck"            # the one condition that stops a session
```

`:run` with the colon. Plain `run` matches the task in every module, so it starts the Calcite and
Lucene trials as well, with their defaults and none of your arguments.

Everything prints to the console. There is no UI, no output file and no configuration.

### Two kinds of check, and they are not interchangeable

```bash
./gradlew test      # 45 tests, ~3 s — the arithmetic
./gradlew :run      # 60 s — the instrument
```

**`test` cannot tell you the tool is right**, and is not meant to. Whether a share is accurate,
whether the sampler holds its step, what the hook costs — those are settled by agreement with an
independent measurement, and the bench is what produces one. Faking them in a unit test would give
tests that pass while the instrument is wrong, which is the worst outcome available here.

**The bench cannot tell you the arithmetic is right either**, which is what took a while to notice.
The bound on every share shipped in a form that was sound and vacuous on a thread pool, and nothing
caught it until a Lucene trial was re-run by hand. The inputs needed to fail were six numbers.

So `test` covers what is ordinary code sitting on top of the measurements — the bound, the floor
check, the long-execution verdict, the registry, and the report's promises about itself. Its
expectations are the numbers recorded in `docs/findings.md`, which makes a finding something that
fails a build rather than something somebody has to re-run a trial to notice.

**Use `:run`, with the colon.** An unqualified `gradlew run` matches the `run` task in *every*
subproject, so it runs the bench and then all four trials in turn — minutes of extra load, and the
next run starts on a machine that is no longer quiet. `gradlew :run` is the bench alone;
`gradlew :trial-lucene:run` is one trial.

### Every flag

Six of them pick a **mode** — the bench does that one thing and exits. Without one it runs the
workload and checks itself against the truth.

| mode | what it does |
|---|---|
| `--demo` | the public API and nothing else: register, wrap, start, stop, print. No bench machinery, so it is also the only test that the API stands up on its own. Deliberately leaks a label every thousandth pass to show what the balance check is for |
| `--verify` | the sampler against the known truth at four sample sizes, plus the observer effect |
| `--sweep=1,2,4,8,16` | the bench across thread counts, one calibration shared by every entry so that the thing varied is not confounded with the thing held fixed. Forces `--sampler=off` |
| `--hook` | what the instrumentation costs per call, labels on against labels off |
| `--stackcost` | what a cross-thread stack walk costs — the measurement that decides whether the tool may ever take one |
| `--leakcheck` | stages a leaked label on purpose and asserts it stops the session, and *only* under strict |
| `--fanout=N` | requests hand their chunks to a pool of N helpers, at one driver and at `--threads` of them. Checks the sampled threads-per-request against the bench's own stopwatch. Needs `--coarse`; add `--propagate=off` to run the same thing with the context left behind, which is what the defect looks like |
| `--escape` | with `--fanout`, each request also dispatches a chunk nobody waits for, so it outlives the span carrying it. The stale-context detector must see it, and must stay silent without it |
| `--cpucost` | what a per-thread CPU reading costs and at what resolution it answers — the two numbers that decide whether occupancy can be measured from CPU rather than from thread state |

The rest shape the run:

| flag | default | what it is for |
|---|---|---|
| `--seconds=N` | 60 | length of the measured run |
| `--threads=N` | half your cores | worker threads. Filling every core starves the sampler, the JIT threads and everything else, and the workers then get preempted by all three |
| `--active=N` | `--threads` | how many of them actually work. The rest park — **starvation mode**, which is how the duty cycle's per-thread bound is tested against idle threads |
| `--step=MS` | 1.0 | sampling interval |
| `--lock=HOLD,EVERY` | off | hold a real `ReentrantLock` for HOLD µs every EVERY ms. The one thing here that genuinely blocks, and the only way a measurement of stalling can be checked against a known amount of waiting |
| `--labels=off` | on | the hook, off |
| `--sampler=off` | on | the sampling thread, off |
| `--state=off` | on | the per-sample thread-state read, off |
| `--oversubscribe` | off | allow more threads than cores. A mode, not an escape hatch: the sampler then reads slots rather than cores, so occupancy over-reads CPU by exactly the oversubscription factor — the one configuration whose duty cycle is predictable from the configuration alone |
| `--coarse` | off | place the coarse labels too — `checkpoint` containing `maintain`, `rankBatch`, and a variable-sized `request` — and check every span against the interval each worker timed itself. A switch of its own for the same reason as the three above: a context costs tens of nanoseconds where the fine hook costs two |
| `--coarse=violate` | off | the same, plus a coarse label on `traverse`, which the tier boundary rejects — 905 ns at a 65.2% share needs 2610 ns. The negative control for the floor check, so it is watched firing rather than assumed wired up |

**`--labels`, `--sampler` and `--state` are three switches rather than one, on purpose.** The hook's
cost, the sampling thread's cost and the state read's cost are different questions, and a thing that
is always on can only be priced by argument. `--state=off` has a second job since the duty cycle
went per-thread: it is the only way to exercise the fallback the bound uses when it cannot see where
the waiting was.

**Three flags that used to exist and no longer do.** `--strict` gated the floor check, which is now
a warning; what strict still governs is a leaked label, and the bench never leaks one, so the flag
changed nothing — `--leakcheck` tests the mechanism properly instead. `--wait` chose the sampler's
wait strategy; spinning is the only one that holds a 1 ms step (park drifts to 1.62 ms, and to
13.5 ms with every core loaded), the measurement is recorded in `docs/findings.md`, and the enum
remains in the library with `SPIN` as its default. `--jitter=off` disabled the sampler's interval
jitter, and in the whole project it never produced a measurement.

## What is here

```
src/main/kotlin/com/minogin/profiler/        the library — this, and nothing else, is the artifact
  Profiler.kt   the slot, the registry, op { } / enter / exit
  Sampler.kt    the sampling thread: the tick loop and the slot walk
  Report.kt     what a session collected, and how it is rendered
  Duty.kt       how much of the occupancy was CPU, and the bound that puts on every share
src/main/kotlin/com/minogin/profiler/bench/  the harness — never shipped
  Workload.kt   the twenty operations, the call graph, the schedule
  Burn.kt       the busy loop, calibration, iteration fitting
  Bench.kt      worker threads and the stages they are driven through
  StackCost.kt  what a cross-thread stack costs, which decides whether we may ever take one
  Main.kt       the modes, the run, and the bench's own tolerance table
  Verify.kt     the sampler against the truth, and the observer effect
  Print.kt      every report the bench prints about itself
src/test/kotlin/                             the arithmetic, checked in ~3 s — ./gradlew test
trial-calcite/  the fine tier pointed at Apache Calcite, and the harness that did it
trial-lucene/   the same pointed at Apache Lucene: the corpus, the wrappers, four configurations
trial-netty/    the same pointed at Netty: event loops, and time that is mostly not CPU
trial-common/   shared by every trial, JDK only: the JFR recording and stack analysis
docs/
  index.md      what each document is for — start here
  tldr.md       the whole project in plain words, for a five-minute read
  output.md     how to read the report, column by column and warning by warning
  profiler.md   the idea, and what was known about prior art
  plan.md       phases and where each one stands
  findings.md   what we learned, with the measurement behind every claim
  trial-calcite.md  the trial on Apache Calcite: the finding, and where the tool got in the way
  trial-lucene.md   the trial on Apache Lucene: concurrent, and against a timed-wrapper profiler
  trial-netty.md    the trial on Netty: event loops, and what a flame graph does with a handler chain
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

**Built since:** thread state beside every sample, per-operation concurrency, and a per-thread bound
on the error of every share — the `waiting`, `elapsed` and `in-flight` columns are those. And the
library surface enough to publish: **v0.1.0**, Apache-2.0, on JitPack.

**And the coarse tier is built, and contexts now cross threads.** `coarse(type) { }` around a logical operation gives
executions, mean, p50/p90/p99 and max — **measured**, two timestamps per execution, the only numbers
in the report that are not sampled — plus busy time per execution, so `mean − busy/exec` is the
waiting quantified, and the breakdown by fine operation underneath it. Verified by a truth that is an
identity rather than an estimate: the bench times every one of its own requests, and over half a
million of them the profiler's p50, p90 and p99 agree to **+0.00%**.

Propagation across executors landed after that: `.propagating()` on a pool, and work handed to it
stays inside the execution that forked it. Measured on the bench against its own stopwatch, a request
fanned across eight helpers reports **4.00** threads inside against **4.00** measured. On Lucene —
somebody else's code, one call on the pool it is handed — the labelled thread-time falling outside
every span went from **88.5%** to silent and `waiting` from **24.2%** to **3.8%**, with the mean span
unchanged, while Calcite and Netty stayed silent as controls. Two columns rather
than one, because they answer different questions: `inside` counts the threads a request ties up,
`working` counts the ones on a CPU — and on that run the difference is 0.99 of a thread, which is the
caller parked on its own join.

Work that outlives the request that forked it is caught too, which is the failure propagation makes
easier rather than harder: a task handed off and never waited for keeps being billed to a request
that has already finished. That time is excluded from the numbers and named, and under `strict` it
stops the session — 18.3% of coarse thread-time when the bench stages it on purpose, and 0.00% when
it does not.

**Still to build:** propagation through coroutines and futures, the whole-application parallelism
coefficient, annotations plus a bytecode agent, and JFR as an output format.

**Untested, and therefore unclaimed:** coroutines and virtual threads. The design argues the fine
tier is structurally safe with the first and the registry is now bounded for the second, but no
trial here creates either, so both are arguments rather than measurements.

**Constraint carried throughout:** it must not require you to hand over your thread creation. That
ruled out an otherwise attractive optimisation, and it is why the sampler is built the way it is.

Release notes are in [CHANGELOG.md](CHANGELOG.md).

See `docs/plan.md` for where things stand, `docs/trial-calcite.md` and `docs/trial-lucene.md` for the tool
used in anger on somebody else's code, `docs/case.md` for the case against the existing tools built out of observed failures,
and `docs/profiler.md` for the design and the prior art.

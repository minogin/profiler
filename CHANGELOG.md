# Changelog

## Unreleased

### Crossing threads

Work handed to a pool no longer leaves its logical operation behind. `captureCoarse()` on the thread
that forks, `withCoarse(ctx) { }` on the thread that receives, and wrappers over the pair:
`Runnable.propagating()`, `Callable.propagating()`, and `.propagating()` on `Executor`,
`ExecutorService` and `ScheduledExecutorService`. No new dependency — the published POM still says
this library has none.

Prefer wrapping the **pool** to wrapping tasks: wrap it once and you cannot forget a task. Capture
happens where you wrap and not where the task runs, so wrap on the forking thread. `schedule()` on a
scheduled pool deliberately does not propagate — a task that runs in five minutes will usually
outlive the execution that scheduled it, and crediting it there invents attribution rather than
losing it.

The sampler was not changed. It already counted an instance once per tick while counting every thread
in it, so the ratio moved on its own the moment two threads held one context.

**Two new columns, and they are one measurement answering two questions.** `inside` is the threads in
one execution with a parked one counted in full — what a request ties up, and the half that keeps
`threads inside = in flight × inside` exact. `working` is the ones a sample caught on a CPU — what
splitting the request bought, the work-span model's `T₁/T∞`. They differ by exactly the `waiting`
column: `working = inside × (1 − waiting)`.

**Measured against the bench's own stopwatch**, one driver fanning across eight helpers, 60 s:
`inside` reads **4.00** against **4.00** measured, within **0.1%**; `working` reads 3.01, so 0.99 of
those threads were parked — the driver, recovered from thread state alone. The span goes from
accounting for **0.2%** of the work its own request did to **105%**, and labelled thread-time outside
every span collapses from **76.4%** to **0.0%**. Root calls are conserved exactly in both arms, and
the escaping arm is still runnable as `--propagate=off`, so the before and after are an A/B in one
binary.

### Work that outlives its span

The failure propagation makes easier rather than harder, and the one direction that cannot be
recovered from: a task handed to another thread and never waited for goes on being billed to a
request that has already finished. Attribution invented, not lost.

A `closed` flag on the context, written once by the owner as it restores its slot and read by the
sampler for every occupied context it visits. Nothing else could see this — the balance check reads a
thread's own slot and finds it clean, the floor check reads sizes, and the outside-every-span line
reads work with no context at all.

Those samples are **excluded** from every number the type reports rather than folded in: crediting
them lets `busy/exec` exceed the `mean` span it sits inside, which cannot happen and would read as a
finding. Under `strict` it stops the session, above a share and a minimum sample count, which is the
same treatment a leaked label gets. `--leakcheck` now stages both and asserts each fires only under
strict.

Measured: **18.3%** of coarse thread-time with the bench staging an un-joined chunk per request,
**0.00%** with nothing staged. Getting the second number needed a second read — a clean join has the
helper release the context just before the owner closes it, which falls between the sampler's two
reads and read 1.14% stale on a correct run. On seeing a closed context the sampler now re-reads the
slot and asks whether the thread is still in it.

### The coarse tier

The half the fine tier could not reach: **how long did one execution take.** A `coarse(type) { }`
label allocates a context per execution, timestamps both ends, and publishes it into the slot beside
the fine operation id — so every sample records the pair, and the report can say *of the time under
`request`, this much was `validateRecord`*. `enterCoarse`/`exitCoarse` for a boundary that is two
callbacks; `Profiler.registerCoarse` for the ids; `Profiler.expectBalanced()` now checks the coarse
half first, because a leaked context collects a whole request's samples where a leaked label collects
one operation's.

Per type: executions, mean, min, max and p50/p90/p99 — **measured, not sampled**, the only numbers in
the report that are — plus busy time per execution, so that `mean − busy/exec` **is** the waiting,
and the breakdown by fine operation. Percentiles come from a 320-bucket log histogram, 2.5 KB per
type per thread and allocated lazily, so a program using only the fine tier pays nothing for any of
it. A percentile is reported at the top of its bucket: at most 12.5% high, never low.

**Verified against a truth that is an identity rather than an estimate.** A span is something the
bench can measure for itself, so each worker times every one of its own requests with the same two
clock readings the profiler takes. Over 531,049 requests the counts agree exactly and p50, p90 and
p99 agree to **+0.00%**; execution counts agree with the call graph to **+0**; parallelism reads
**exactly 1.0000**, which is the answer it must give until a context can cross a thread.

**Verified on three foreign codebases, not only the bench.** On Calcite the spans were checked against
a stopwatch that harness has had since before this tier existed — 2,751 plans, count and p50/p90/p99
all **+0.00%** — and the cross-tabulation reads *of the 9.08 ms a plan takes, `FilterIntoJoinRule` is
25.2%*. On Netty a request is pure CPU and `mean − busy/exec` reads **0.0%**, which is the negative
control: the column does not invent waiting. On Lucene it reads **0.0% at one thread and 24.5% at
eight**, because Lucene fans a search across a pool — so without propagation, parallel work inside a
coarse operation is reported as waiting. That is the honest limit of a thread-local context and the
reason the next phase exists.

**Two checks on the tier boundary, and they cover different failures.** A coarse label on something
too short is caught exactly — `d ≥ max(800 ns, 4 µs × share)` compared against a duration that was
*measured*, so unlike the fine floor it needs no statistical slack — and the message says which of the
two conditions bound, because *"the number describes the instrument"* and *"the program you measured
is not the one you started with"* have different remedies. And **work escaping its context** is now
measured: the report counts labelled time that fell under no coarse span, which reads 88.5% on Lucene
at eight threads and is silent on Calcite, Netty and single-threaded Lucene. Stated as a measurement
with both its readings, never as an accusation, since it cannot tell *"I bracketed part of the
program"* from *"work escaped"*.

**Bracketing came for free:** unlabelled samples taken under a context belong to that context, so a
coverage gap stops being global and becomes located — *"three quarters of a request is inside Netty's
codec and write path"* rather than *"the labels miss most of the run"*.

**Not built, deliberately:** cross-thread propagation and coroutines — that is the next phase, and
keeping it separate means a propagation bug can never be mistaken for a tier bug. There is no
parallelism column for the same reason: it would be a column of ones. It is measured all the same,
because a known answer is what an instrument gets calibrated against.

**Suspected and disproven:** that `--coarse` destabilised the bench's own two-truths self-check.
Three runs suggested it; twenty say otherwise — 9.56% mean scatter with the coarse tier against
13.22% without, both failing nine times in ten. The cause is the machine warming up, and the bench's
6% tolerance was set from cold measurements. Recorded in
[docs/findings.md](docs/findings.md#the-coarse-tier) because it was believed for an afternoon.

The coarse tier's own four checks passed on all twenty runs including the eighteen where the bench
declared itself broken — they compare spans against the workers' stopwatches on the same intervals,
so a drifting clock cancels on both sides.

**Breaking, and deliberately made before the second number exists.** The `threads` column is now
`in flight`, printed over the threads there were as `3.28/8`, and `OperationStat.concurrency` is now
`OperationStat.inFlight`.

It always counted *executions of a label running at once*, never threads spread over one execution —
for a fine operation those are the same count, because a fine operation is atomic and never leaves
the thread that entered it. They stop being the same when the coarse tier arrives and a span can
cross a thread, where `threads inside = executions in flight × parallelism per execution`. Eight threads
in a label is then either eight serial requests or two requests on four threads each, which want
opposite fixes. Renamed now rather than then, because a reader who has learned `threads` to mean
per-request parallelism will not un-learn it when a second column appears beside it. `parallelism` is
reserved for that second column: it is `work ÷ span` for one execution, the word's textbook meaning.

**And the ratio is not cosmetic.** The number is a property of your deployment rather than of your
code — by Little's law twice the arrival rate is twice the number with nothing changed, and it caps
at the pool size, since `threads inside = min(λ·W·parallelism, P)`. Below the ceiling it tracks your
load, at the ceiling it reports your pool. The ratio is what says which regime produced it, and it is
the only part of the column that is a finding. It stays in the table because `elapsed = occupancy ÷
in flight` cannot be computed without it.

## v0.1.0 — 2026-08-28

First release. The **fine tier**: operations identified by an integer in a thread-local slot, written
by a ~2 ns hook and read by a sampling thread once a millisecond. Apache-2.0, one dependency (the
Kotlin stdlib), Java 21+.

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.minogin:profiler:v0.1.0") }
```

**What it answers.** Which of your labelled operations is eating the time — a ranked list of shares
over thread-time with call counts, implied per-call duration, how much of each was waiting rather
than working, its wall-clock footprint, how many threads were inside it at once, and a **measured
bound on how wrong the shares can be**. It reaches operations of tens of nanoseconds, which is the
regime stack profilers cannot see into, and has no upper limit — Calcite's labels were
millisecond-sized and it matched JFR to within a percentage point.

**Verified** against a synthetic workload whose true answer is known independently, and on three
foreign codebases: Apache Calcite, Apache Lucene and Netty. Every claim in
[docs/findings.md](docs/findings.md) carries the measurement that produced it, and the test suite's
expectations are those same numbers.

**What it does not answer**, stated because a 0.x that implies more than it delivers is worse than
one that is narrow:

- No per-execution durations, and therefore **no percentiles, ever**. Sampling gives a share of
  time, not a distribution of latencies.
- No request latency and no cross-thread propagation. That is the coarse tier, and it is not built.
- No counterfactual. A share is where time went, not what removing the operation would save — in the
  Calcite trial those differed by **275×**.

**Untested and therefore unclaimed:** coroutines and virtual threads.

**Two sharp edges**, both of which the report tells you about rather than leaving you to find:
call `Profiler.release()` when a thread exits (there is a safety net, but it waits on a garbage
collection); and on event loops whose waiting is native the error bound is unavailable, and the
report says so instead of printing a number it cannot justify.

### Notable fixes on the way to this release

- **The registry no longer grows with the number of threads ever created.** It was a
  `CopyOnWriteArrayList` — an O(n) copy per registration and per release, and a walk as long as the
  live thread count every millisecond, with no ceiling. Virtual threads made both unbounded. Now a
  fixed array: 12,000 threads created leaves a walk of 800 entries, being the peak concurrency.
- **The error bound is taken per thread**, so idle threads no longer poison it — a starvation test
  went from a formally unbounded error to 3.16 pp — and it uses the thread-state read, without which
  it was sound and useless on a thread pool: Lucene read 47.35% where the truth is 98.46%.
- **A below-floor label warns instead of stopping the run.** The check is machine-dependent: the
  same label reads 17.8 ns at one thread and 55.4 ns at eight on one laptop.
- **A leaked label is the one condition that stops a session**, under `strict`, because it does not
  make a number imprecise — it makes it somebody else's.
- Four defects found by code review, including an unclamped duty that could print
  `at most -764160581304320300.00 pp`.

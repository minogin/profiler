# Changelog

## Unreleased

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

**Not built, deliberately:** cross-thread propagation and coroutines — that is the next phase, and
keeping it separate means a propagation bug can never be mistaken for a tier bug. There is no
parallelism column for the same reason: it would be a column of ones. It is measured all the same,
because a known answer is what an instrument gets calibrated against.

**One open item, recorded rather than explained away:** running the bench with `--coarse` makes the
bench's *own* two-truths self-check fail more often, on a different operation each time, and the
cause is not established — garbage was the first hypothesis and the collection counters refute it.
The coarse tier's own checks pass on every run, including the failing ones. See
[docs/findings.md](docs/findings.md#the-coarse-tier).

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

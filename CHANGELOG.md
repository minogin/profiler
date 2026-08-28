# Changelog

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

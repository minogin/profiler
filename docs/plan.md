# Plan

What we are building, in order, and where each phase stands. Kept current — when a phase closes,
its section is rewritten to say what was actually built rather than what was intended.

Findings, techniques and dead ends live in [findings.md](findings.md). The idea and the prior art
live in [profiler.md](profiler.md).

| phase | subject | status |
|---|---|---|
| 1 | The bench — a workload whose true answer is known | **done** |
| 2 | The fine tier — slots, hook, sampling thread | **done** |
| 3 | Verification — sampler against the truth | **done** |
| 4 | The coarse tier — contexts, spans, cross-tabulation | next |
| 5 | Crossing threads — propagation, and per-operation parallelism | not started |
| 6 | Thread state and the whole-application parallelism coefficient | not started |
| 7 | Library surface — annotations, agent, results API | not started |
| 8 | JFR output | not started |

**What this is for.** A general-purpose tool, released open source, for auditing the performance of
applications with hot short operations. Not tied to any one system — the graph-flavoured operation
names in the bench are a synthetic workload, nothing more.

Stack: Kotlin/JVM, Gradle, no dependencies. Output to the console for now; see phase 8.

---

## Architecture — two tiers

The tool measures two different kinds of thing, and they cannot share a mechanism.

**Fine — physical operations.** A hash probe, an edge scan, one filter condition. Billions of
executions, tens of nanoseconds each, atomic in the sense that they call nothing interesting.
Identified by an integer in a thread-local slot and measured by sampling. No identity, no
allocation, no timestamps — at these durations a clock read costs more than the operation.

**Coarse — logical operations.** Expanding a frontier, applying a filter set, serving a query.
Thousands of executions, milliseconds each, freely crossing thread boundaries. Each execution gets
a real context object: allocated, propagated across hand-offs, timestamped at both ends. At these
durations an allocation and two `nanoTime` calls are free.

**They meet at the slot**, which carries both the current fine operation id and a reference to the
current coarse context. Every sample records the pair, so the fine breakdown can be cross-tabulated
against the logical operation it happened under:

> *Of the 400 ms of CPU under "apply filters", 180 ms was `matchCondition`, and it ran at 3.7×
> parallelism.*

Neither tier answers that alone. Fine sampling says what is hot but not what it was for; coarse
spans say where the time went but not why.

**Why there is no per-thread stack.** An earlier design had inclusive time coming from a stack of
ids per thread, which brought torn reads, recursion handling, and the problem that a stack is far
harder to carry across a coroutine suspension than a single value. The two tiers dissolve all of
it: inclusive time is wanted precisely for the operations that become coarse, and those carry an
explicit context that works across threads, which a per-thread stack never could. Fine operations
are atomic, so their self time *is* their inclusive time.

---

## Phase 1 — the bench · done

Twenty operations with self durations from 20 ns to 2 µs — two orders of magnitude — arranged in a
DAG four levels deep, driven by a shuffled fixed-seed schedule across N worker threads.

The shuffle is load-bearing, not cosmetic. It spreads every operation uniformly across the run, so
anything that drifts — clock, temperature, core migration — affects all operations in proportion
and cancels when shares are taken. Nearly every correctness argument in this project reduces to
that one property.

**Two truths, cross-checked.** Both multiply call counts by a duration; they differ only in where
the duration comes from.

- **A — configuration.** The duration we asked for.
- **B — batch measurement.** The duration measured afterwards on the worker threads, summed per
  thread so that each thread's calls meet its own measured durations.

Call counts are exact: counters sit on root calls only and everything nested is derived through
the call graph. If A and B disagree the bench is broken and the run stops — that check caught the
JIT collapsing the busy loop, which A alone could never have noticed.

**Also in:** iteration counts fitted by measuring at each operation's own working point; a warm-up
that checks only whether throughput has stopped *climbing*; the clock probed per phase including
during the run; `--sweep` across thread counts; starvation mode; and every tolerance derived from
measurement with the measurement recorded beside it.

**What it does not do:** the workload is uniform across threads and constant in time, and no
operation ever hands work to another thread. Phases 5 and 6 both need more than that.

## Phase 2 — the fine tier · done

A padded slot per thread holding the id of the operation that thread is inside. Entry writes the
id, exit restores the previous value — restores, not clears, or nesting collapses. A separate
thread wakes on a jittered interval, reads every slot, and increments a counter per id.

**Decisions that turned out to matter:**

- **Opaque access, not volatile.** A volatile store on x86 needs a StoreLoad barrier and the hook
  does two per call — that was 16% of throughput. Opaque emits no fence and stops the JIT
  eliminating the access.
- **The sampler spins.** Parking cannot hold a 1 ms step under load; it degraded to 13.5 ms and
  bunched its samples. Spinning costs a core, so with the sampler on the workers can never have
  every core, and a genuinely saturated machine is only measurable uninstrumented.
- **Counters on the slot.** The hook already holds the per-thread data, so counting is nearly
  free — and a share alone cannot distinguish 200M calls at 8 ns from 1000 at 1.6 ms.
- **Slots are released when a thread dies**, or dead threads read as idle ones forever.
- **`--labels` and `--sampler` are separate switches**, because the hook's cost and the sampler
  thread's cost are different questions.

## Phase 3 — verification · done

All three hypotheses from the original design hold, **for the fine tier**.

**1. The method works on operations four orders shorter than a tick.** At 476k samples the top 10
of 20 operations are ranked in the same order as an independently computed truth, and the largest
consumers are the most accurate — `popFrontier` within 0.25% of itself, `filterNode` within 0.00%.

The metric is the point. Divergence in percentage points shrinks as `1/√N` whether the method works
or not. Dividing each gap by its own standard error separates the two: unbiased and the RMS stays
near 1 at every sample count, biased and it grows with N. Across a 20× range of samples it grew
5.8× against 4.5× for pure bias, so the residual is systematic.

Worst miss is `tinyStep`, 15% of itself: a 20 ns operation holding 3.2% of the time. Errors
concentrate on the shortest operations and on parents, which absorb their children's hook entry
cost. The mechanism is only partly understood — see findings.

**2. The observer does not disturb the observed.** The hook is 1.7–2.3 ns per call, about 2% of a
call-weighted mean operation of 88 ns. Measured directly; the throughput comparison between
configurations is printed but marked inconclusive, because the machine drifts by more than the
effect.

**3. Unsynchronised slot reads do not smear the picture.** Follows from 1 holding.

## Phase 4 — the coarse tier · next

Contexts, spans, and the cross-tabulation. Same-thread to begin with — crossing threads is phase 5,
and the two are worth separating so that propagation bugs cannot be confused with tier bugs.

**The context object.** Allocated on entry to a coarse operation, holding a type id, a reference to
its parent context, a start timestamp, and one counter the sampler increments. Every identity field
is `val`: final fields carry a freeze at the end of construction, which is what makes the reference
safe to publish into the slot and read from the sampling thread without a lock or a fence.

Coarse operations nest, so the parent reference is a stack — but of few, long-lived objects, so a
linked reference costs nothing.

**Contexts are never recycled.** Reuse breaks the final-field guarantee and would let the sampler
attribute samples to a context that has since become something else. Silent misattribution, which
is the failure mode this whole design keeps having to guard against.

**Aggregation is per type, not per instance.** Fifty types is bounded; fifty thousand executions is
not. Instances are absorbed into their type's statistics and forgotten.

**What comes out, per coarse type:**

| quantity | statistics | basis |
|---|---|---|
| span | count, avg, min, max, p50/p90/p99 | measured exactly, two timestamps |
| total CPU | sum | sampled |
| breakdown by fine operation | shares | sampled, from the `(fine, coarse)` pair |
| parallelism | avg, p50/p90/p99 | sampled — see the caveat |

Percentiles come from a logarithmic-bucket histogram: fixed memory, roughly 1.6 KB per type, any
percentile to a known precision. About forty lines, since we take no dependencies. p99 is included
because it costs nothing extra and the tail is usually where the interesting behaviour is.

**The caveat that must reach the output.** A coarse operation's duration is measured; its CPU is
*sampled*. An 8 ms instance with four threads busy at a 1 ms tick collects around 32 samples and is
usable; a 100 µs instance collects zero or one and is noise. So per-instance CPU and parallelism
statistics are computed only above a sample threshold, and **the report has to say how many
instances were excluded** — otherwise it quietly describes a biased subset.

**Bench work:** some operations promoted to coarse, so there is something to cross-tabulate.

## Phase 5 — crossing threads · not started

Where a logical operation stops being a thread-local concept.

**Propagation.** Every hand-off needs a hook: executors (wrap the `Runnable`), coroutines
(`ThreadContextElement`), `CompletableFuture` (through its executor), parallel streams
(`ForkJoinPool`). Threads created by hand cannot be caught automatically and need an explicit call.

**Coroutines are the hardest instance and get the most care.** Not because the mechanism differs —
mount and unmount is exactly save and restore — but because a *suspended* coroutine occupies no
thread at all. Nothing is sampled while it is suspended, so its CPU is correctly zero, but its
*span* keeps running. That is arguably right, and it must be a deliberate decision rather than an
accident.

**The failure mode to design against.** A missed hand-off fails silently and in the contaminating
direction: work inherits a stale context and is billed to the wrong logical operation, and nothing
in the output says so. Losing attribution is recoverable; inventing it is not. Worth a deliberate
detection mechanism — a context that outlives its span, say.

**Bench work, and it is substantial.** The bench currently has no work distribution at all: each
worker independently runs its own schedule. Testing cross-thread coarse operations needs fork and
join, which is also what phase 6 needs for occupancy that varies over time. Building it once serves
both.

**What this unlocks:** per-operation parallelism becomes real rather than trivially 1.

## Phase 6 — thread state and the whole-application coefficient · not started

Sample the thread's state alongside the label. From that, a histogram of how often 1, 2, 3, … N
threads were busy, and the parallelism coefficient as its mean.

The sampler snapshots every thread at one instant, which is what makes this possible — a profiler
that sums time per thread has destroyed the information before you can ask. This is the one thing
an async-profiler bridge could never provide, since it samples each thread on its own timer signal.

**What can be done now:** the occupancy histogram, and the busy-versus-throughput curves across
`--sweep`, validated against starvation mode's known constant answer.

**What needs something the bench does not have:** the heuristic that distinguishes "executing" from
"spinning in the dispatcher" from "parked" needs something dispatcher-shaped to test against, and
our workers just loop. It arrives with phase 5's fork/join, or with coroutines.

**And the diagnosis, not just the mechanism.** Starvation mode proves the sampler can count busy
threads. It cannot prove the interesting claim — telling "steadily 3 busy, structural width limit"
from "sawtooth spiking to 16, barrier costs", which both average to 3. That needs occupancy that
varies over time, and the ground truth for it has to be *recorded* (per-phase timestamps) rather
than computed, because occupancy is emergent rather than configured.

## Phase 7 — library surface · not started

What someone else has to touch to use this. Two ways of placing fine labels, because operations do
not always coincide with method boundaries:

- **Annotations plus a bytecode agent.** `@Profiled("expand")` on a method, transformed at class
  load. No runtime dependency at the call site, attachable to a running JVM, removable by dropping
  a flag. The agent does not fight the JIT — it rewrites bytecode and C2 inlines the wrapper
  afterwards, exactly as it does for the hand-written form.
- **Explicit calls** for everything else: a loop body, half a method, a span across several calls.

Coarse operations are explicit by nature — they delimit logical work, which an annotation on a
method usually cannot express.

Also here, and not yet designed: operations registered at runtime by name rather than a fixed
array; surviving pools that create and destroy threads for the life of a process; reading results
through an API rather than `println`; and whether the profiler can be left on permanently — at
~2 ns per hook it may well be cheap enough, but that should be a decision with a measurement
behind it.

**Standing constraint:** it must not require the user to hand over thread creation. That already
ruled out an otherwise attractive optimisation — a slot field on a `Thread` subclass would be
roughly twice as fast as a `ThreadLocal` lookup — and it is not negotiable, since
`Dispatchers.Default` creates its own carrier threads and you cannot substitute them.

## Phase 8 — JFR output · not started

JFR as the transport, not as the mechanism.

A custom JFR event per *operation* is hopeless — events cost tens of nanoseconds even without stack
traces, and Datadog's attempt at scope events inflated recordings more than tenfold. But one
aggregated event per second carrying the counters costs nothing and lands in a format people
already have tooling for: JMC, flight recordings, existing pipelines.

# Plan

What we are building, in order, and where each phase stands. Kept current — when a phase closes,
its section is rewritten to say what was actually built rather than what was intended.

Findings, techniques and dead ends live in [findings.md](findings.md). The idea itself lives in
[profiler.md](profiler.md).

| phase | subject | status |
|---|---|---|
| 1 | The bench — a workload whose true answer is known | **done** |
| 2 | The sampler — slots, hook, sampling thread | **done** |
| 3 | Verification — sampler against the truth | **done** |
| 4 | Inclusive against self time | next |
| 5 | Thread state and the parallelism coefficient | not started |
| 6 | Coroutines | not started |
| 7 | Library surface — annotations, agent, results API | not started |
| 8 | JFR output | not started |

**What this is for.** A general-purpose tool, released open source, for auditing the performance
of applications with hot short operations. Not tied to any one system — the graph-flavoured
operation names in the bench are a synthetic workload, nothing more.

Stack: Kotlin/JVM, Gradle, no dependencies. Output to the console for now; see phase 8.

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

**Also in:**

- Iteration counts fitted by measuring at each operation's own working point, not extrapolated
  from a line across the whole range
- Warm-up that checks only whether throughput has stopped *climbing* — a plateau in both
  directions is something a drifting laptop cannot provide
- The clock probed per phase, including three probes during the run itself while workers are loaded
- `--sweep=1,2,4,8,16` to vary thread count off one calibration
- Starvation mode (`--active=3 --threads=15`) — a known, constant occupancy for phase 5
- Every tolerance derived from measurement, with the measurement recorded beside it

**What it does not do:** the workload is uniform across threads and constant in time. Every thread
runs the same mix flat out for the whole run. Phase 5 will need occupancy that varies over time,
and the bench cannot currently produce it.

## Phase 2 — the sampler · done

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

All three hypotheses from the original plan hold.

**1. The method works on operations four orders shorter than a tick.** At 476k samples the top
10 of 20 operations are ranked in the same order as the truth, and the largest consumers are the
most accurate — `popFrontier` within 0.25% of itself, `filterNode` within 0.00%.

The metric is the point. Divergence in percentage points shrinks as `1/√N` whether the method
works or not. Dividing each gap by its own standard error separates the two: unbiased and the RMS
stays near 1 at every sample count, biased and it grows with N. Measured across a 20× range of
samples it grew 5.8× against 4.5× for pure bias — so the residual is systematic.

Worst miss is `tinyStep`, 15% of itself: a 20 ns operation holding 3.2% of the time. Errors
concentrate on the shortest operations and on parents, which absorb their children's hook entry
cost. The mechanism is only partly understood — see findings.

**2. The observer does not disturb the observed.** The hook is 1.7–2.3 ns per call, about 2% of a
call-weighted mean operation of 88 ns. Measured directly; the throughput comparison between
configurations is printed but marked inconclusive, because the machine drifts by more than the
effect.

**3. Unsynchronised slot reads do not smear the picture.** Follows from 1 holding. Closed by
measurement rather than by reasoning about the memory model.

**What phase 3 does not establish** is whether self time is the quantity worth measuring — which
is why there is now a phase 4.

## Phase 4 — inclusive against self time · next

The sampler measures **self** time, because the slot holds the innermost operation: while a parent
runs a child, the slot says *child*. So a parent's share is only the work it does outside its
children. That was never a decision, just what a single slot gives you.

The difference is not small:

| operation | self | inclusive |
|---|---|---|
| frontierStep | 5.8% | **51.9%** |
| traverse | 4.3% | **65.2%** |
| rankBatch | 1.7% | 16.4% |

On self time `frontierStep` is seventh and looks minor. On inclusive time it is over half the run.
Those point at different hammers — *make this faster* versus *stop calling this so much*. Both are
legitimate questions and a profiler should answer both.

**Approach:** replace the single slot with a small per-thread stack. Entry pushes, exit pops —
about the same number of memory operations as now. The sampler walks the stack, crediting
inclusive to every level and self to the innermost, both from one pass.

**Known difficulties, to be settled during the phase:**

- The sampler can catch a torn stack — depth from one instant, an id from another. The ancestors
  are the stable part, though, and the volatile end is the one we already accept staleness on.
- Recursion needs an operation credited once per sample, not once per stack level.
- A single value survives a coroutine suspension trivially; a stack has to be saved and restored
  wholesale. That lands on phase 6 and may constrain the design here.

**Side effect worth having:** the parent bias from phase 3 largely evaporates in an inclusive
view. `frontierStep`'s error is 6.2% of its self time but 0.7% of its inclusive time.

## Phase 5 — thread state and the parallelism coefficient · not started

Sample the thread's state alongside the label. From that, a histogram of how often 1, 2, 3, … N
threads were busy, and the parallelism coefficient as its mean.

The sampler snapshots every thread at one instant, which is what makes this possible — a profiler
that sums time per thread has destroyed the information before you can ask.

Telling "executing an operation" from "spinning in the dispatcher" from the inside is not possible
directly: both are RUNNABLE and the dispatcher is not instrumented. Working heuristic: empty slot
while RUNNABLE is almost certainly the dispatcher, empty slot while WAITING is idle. Testable
against starvation mode, which has a known constant occupancy.

**The gap.** Starvation mode validates the *mechanism* — can the sampler count busy threads. It
cannot validate the *diagnosis*, which is the interesting claim: distinguishing "steadily 3 busy,
structural width limit" from "sawtooth spiking to 16, barrier costs". Both average to 3, and the
bench can currently only produce the first. Deciding whether we need the second, and building the
temporal machinery if so, is part of this phase.

## Phase 6 — coroutines · not started

`ThreadContextElement`, `updateThreadContext` / `restoreThreadContext`, and the cost of the hook
at real dispatch frequency. Details in the design document.

Not optional for a general tool. Without the bridge the method does not merely lose data, it
invents it: a coroutine that suspends without clearing its slot leaves its label on a thread that
goes on to do something else, and the wrong operation gets billed.

Note the interaction with phase 4: a single value survives mount and unmount trivially, but a
per-thread *stack* has to be saved and restored wholesale. That may constrain the inclusive design,
so it is worth settling the coroutine story before committing to a stack.

## Phase 7 — library surface · not started

What someone else has to touch to use this. Two ways of getting labels in, because operations do
not always coincide with method boundaries:

- **Annotations plus a bytecode agent.** `@Profiled("expand")` on a method, transformed at class
  load. No runtime dependency at the call site, attachable to a running JVM, removable by dropping
  a flag. The agent does not fight the JIT — it rewrites bytecode and C2 inlines the wrapper
  afterwards, exactly as it does for the hand-written form.
- **Explicit calls** for everything else: a loop body, half a method, a span across several calls.

Also in this phase, and not yet designed:

- Operations registered at runtime by name, rather than a fixed array of twenty. Touches the slot's
  counter array, the sampler's counters, and id assignment.
- Surviving thread pools that create and destroy threads for the life of a process. Registration
  and release are already correct; sustained churn is a different stress than fixed workers.
- Reading results through an API rather than `println`, while the application runs.
- Whether the profiler can be left on permanently. At ~2 ns per hook it may well be cheap enough,
  but that should be a decision with a measurement behind it.

**Standing constraint:** it must not require the user to hand over thread creation. That already
ruled out an otherwise attractive optimisation (a slot field on a `Thread` subclass would be
roughly twice as fast as a `ThreadLocal` lookup) and it is not negotiable — `Dispatchers.Default`
creates its own carrier threads and you cannot substitute them.

## Phase 8 — JFR output · not started

JFR as the transport, not as the mechanism.

A custom JFR event per *operation* is hopeless — events cost tens of nanoseconds even without
stack traces, and Datadog's attempt at scope events inflated recordings more than tenfold. But one
aggregated event per second carrying the counters costs nothing and lands in a format people
already have tooling for: JMC, flight recordings, existing pipelines.

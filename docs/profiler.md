# Sampling with operation labels — the design

The original idea, written up 18.08.2026 and revised as things were measured. Numbers that have
since been established by experiment are marked as such; the rest is still reasoning.

## The problem

A class of workload that ordinary profilers cannot answer questions about: a few dozen distinct
operations, each executed millions of times, each taking tens to hundreds of nanoseconds, running
in parallel with a complicated distribution of work. Which operation is eating the time?

An in-memory graph engine is the example this started from — expanding a frontier, probing a hash
map, scoring a node — but nothing about the method is specific to graphs. Any system with hot,
short, repeated operations has the same problem.

Why the usual tools do not answer it:

- **Granularity.** Sampling stack traces every few milliseconds cannot see a 200 ns operation as a
  distinct thing. After inlining it may not exist as a frame at all.
- **Timer instrumentation is worse than useless here.** `System.nanoTime()` costs twenty to thirty
  nanoseconds. Measuring a 200 ns operation with it distorts the result by tens of percent.
- **Coroutines break attribution.** The thread stack does not correspond to the logical task: work
  is spread across dispatcher threads and suspensions cut the trail.

Which leaves counting operations instead of timing them — how often, never how long.

## The idea

Do not measure the operation. **Count how often a random glance catches a thread inside it.**
Monte Carlo.

Each worker thread has a slot holding the id of the operation currently running on it. Entry
writes the id, exit restores the previous value. A separate sampling thread wakes every
millisecond or so, reads every slot, and increments a counter per id.

```kotlin
inline fun <T> op(id: Int, body: () -> T): T {
    val slot = Profiler.slot()
    val prev = slot.getOpaque()
    slot.setOpaque(id)
    try { return body() } finally { slot.setOpaque(prev) }
}

// the sampling thread, every ~1 ms:
for (slot in allSlots) counters[slot.getOpaque()]++
```

Restoring the previous value rather than clearing it is what lets operations nest.

### Why it works on nanosecond operations

You never need to catch an individual call. The probability that a random sample finds a thread
inside operation 7 *is* the share of time that operation takes. Twenty operations, a minute, eight
threads, a millisecond step — about half a million samples, so an operation holding 5% of the time
collects tens of thousands of hits. Accuracy comes from the number of samples, not from the
resolution of a clock.

**Confirmed by experiment.** At 476,000 samples the top ten of twenty operations came out in the
same order as an independently computed truth, and the largest consumers were accurate to within
one percent of themselves.

### Why it is cheap

Entry and exit write one integer each. No time is measured anywhere.

**Measured: 1.7–2.3 ns per hook call**, roughly 2% of a call-weighted mean operation of 88 ns.

That number was not free. The first implementation used a `volatile` slot and cost **16% of
throughput** — on x86 a volatile store requires a StoreLoad barrier, a lock-prefixed instruction
of tens of cycles, and the hook does two per call. Opaque access emits no barrier while still
preventing the JIT from eliminating the write. Since an opaque store compiles to the same
instruction a relaxed store in C or Rust would, a native implementation would gain nothing here.

### Why it beats a general profiler for this

- It does not walk stacks: no safepoint bias, and inlining cannot eat a label that is written
  explicitly in the code.
- Parallelism is accounted for naturally — the sampler visits every thread.
- The label is in domain terms ("expanding the frontier") rather than `HashMap.get`.

### What it costs you

Somebody has to say where an operation begins and ends. That is irreducible: the domain knowledge
cannot be recovered from outside the code, which is exactly why stack-based tools fail here. What
can vary is how the labels get into the code — see *Delivery* below.

## The coroutine bridge

Without it the method does not merely lose data, it invents it. Two failure modes:

- **Loss.** A coroutine resumes on a different thread where nobody set the slot. The work is
  attributed to nothing and the operation looks cheaper than it is.
- **Contamination, which is worse.** A coroutine suspends without clearing its slot. Another
  coroutine runs on that thread while the slot still says `expand`, and unrelated work is billed
  to expansion. Optimising from that data means optimising the wrong thing.

kotlinx-coroutines provides the hook: `ThreadContextElement` calls `updateThreadContext` before
resuming a coroutine on a thread and `restoreThreadContext` after it suspends. That is exactly
mount and unmount.

```kotlin
class OpLabel(private val op: String) : ThreadContextElement<String?> {
    companion object Key : CoroutineContext.Key<OpLabel>
    override val key get() = Key

    override fun updateThreadContext(context: CoroutineContext): String? {
        val prev = Profiler.currentLabel()
        Profiler.setLabel(op)
        return prev
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        Profiler.setLabel(oldState)
    }
}

withContext(OpLabel("expand")) { expandFrontier(node) }
```

The kotlinx documentation warns that `restoreThreadContext` may run concurrently with later
update/restore calls, so the implementation must be thread-safe. `CopyableThreadContextElement` is
the heavier option for complicated nesting.

**Open interaction with inclusive attribution.** A single value survives mount and unmount
trivially. If inclusive time is implemented as a per-thread *stack*, the whole stack has to be
saved and restored instead, which is materially harder. That may constrain the inclusive design.

### Worked example

Two dispatcher threads, two coroutines, a 1 ms step.

```
t=0   C1 mounts on T1        → update: T1.slot = "expand"
t=1   sample: T1="expand", T2=empty          expand=1
t=2   sample: T1="expand"                    expand=2
t=3   C1 reaches a suspension point, unmounts
      → restore: T1.slot = previous (empty)
      C2 mounts on T1        → update: T1.slot = "filter"
t=4   sample: T1="filter"                    filter=1
t=5   C1 resumes, on T2 now  → update: T2.slot = "expand"
t=6   sample: T1="filter", T2="expand"       filter=2, expand=3
t=7   sample: T1="filter", T2="expand"       filter=3, expand=4
```

## The parallelism coefficient — the main side benefit

An observation that motivated a lot of this: the empirical parallelism coefficient on a real
workload came out small — around 3 — with considerably more threads than that, and it was not
clear why.

**The sampler snapshots every thread at one instant.** One tick is a cross-section: who is doing
what, right now. Existing profilers destroy that by summing time per thread — a flame graph
cannot answer "how many threads were busy at once", because it is about totals. Here the quantity
is already in the data.

**What to record besides the label:** the thread's state — executing an operation, spinning in the
dispatcher looking for work, parked with nothing to do, blocked on a lock. Without state the
analysis does not go anywhere.

**What falls out immediately:** a histogram of how often 1, 2, 3, … N threads were busy. Its mean
is the empirical coefficient. The shape is more interesting than the mean:

- **Always exactly 3 busy, the rest parked** → there is simply no work. A structural limit: in a
  frontier traversal the level width *is* the parallelism ceiling, and no amount of pool tuning
  raises it.
- **Sawtooth — a spike to 16, then a long trough down to 1** → barriers between phases. A mean of
  3 with peaks at 16 means the joins are the problem, not the work.
- **All 16 busy, much of it blocked** → serialisation on a shared structure.
- **All 16 busy, state "executing", and throughput flat** → the threads are running but each one
  slower. That is memory: contention for bandwidth and cache. The one case where hardware
  counters are genuinely needed.

**The decisive fork.** Two opposite diagnoses look identical from outside ("it parallelises
badly") and can only be told apart by comparing two curves against N = 1, 2, 4, 8, 16 — threads
busy, and throughput:

- busy count rises linearly, throughput does not → memory is the limit, adding threads is pointless
- busy count plateaus at 3 → the problem is work supply, fixed by the scheduler or the traversal

The share of time spent in the dispatcher comes from the same data — a direct answer to "too much
management overhead".

**What the sampler cannot see:** *why* there is no work. Telling "the frontier is narrow" from
"there are tasks but the scheduler has not handed them out" needs the dispatcher's queue length in
the sample. That is a later addition.

## Delivery

The labels are irreducible, but how they reach the code is a choice, and so is where the results
come out.

**Labels in.** Both are wanted, because operations do not always coincide with method boundaries:

- **Annotations plus a bytecode agent** — `@Profiled("expand")` on a method, transformed at class
  load. No runtime dependency at the call site, attachable to a running JVM, removable by dropping
  a flag. This is how the established Java APM agents work.
- **Explicit calls** for everything else — a loop body, half a method, a span across several calls.

The agent does not fight the JIT: it rewrites bytecode, and C2 still inlines the wrapper
afterwards.

**Results out.** JFR as the transport, not as the mechanism. A custom JFR event per *operation* is
hopeless — events cost tens of nanoseconds even without stack traces, and Datadog's attempt at
scope-events inflated recordings more than tenfold. But one aggregated event per second carrying
the counters is free, and it lands in a format people already have tooling for.

**Ruled out:**

- **Inferring operations from stack traces** — the thing that already fails. Inlining eats the
  frames, coroutines break the attribution.
- **A bridge to async-profiler.** Tempting: its v4 C API exposes thread-local profiling context, so
  domain labels could ride along with its stack traces and flame graphs. But it samples each
  thread on its own timer signal, so there is no simultaneous cross-thread snapshot — and that
  snapshot is exactly what makes the parallelism coefficient possible. It could answer "which
  operation is expensive" and never "how many threads were busy at once".
- **External polling through attach, JMX or the Serviceability Agent** — milliseconds per query,
  often stopping the world. Three orders of magnitude too slow.
- **An external sampling process over shared memory.** Technically workable, and it moves the
  spinning out of the JVM — but not off the machine, since holding a millisecond cadence costs a
  core either way. A great deal of complexity for a thread that does one microsecond of work per
  millisecond.

## Known prior art (checked 18.08.2026)

- The JVM has no standard equivalent of Go's pprof labels. JFR cannot contextualise its built-in
  events; custom events can carry context.
- async-profiler v4 added a C API for thread-local profiling context
  (`asprof_get_thread_local_data`). The earlier PR #576 with `setContextId`/`clearContextId` was
  never finished; the discussion noted that thread-locals are not in general async-signal-safe,
  except under initial-exec.
- Datadog uses a sparse map from thread identifier to a 64-byte context block; slots are given
  meaning at the start of a session and point into a string dictionary. Their first approach,
  through scope events, fell apart on asynchronous applications — recordings inflated more than
  tenfold.
- dd-trace-java propagates context into virtual threads (November 2025): state captured at
  creation, activated when the continuation mounts, closed when it unmounts. The same shape as
  what coroutines need.
- pyroscope-java does not propagate labels to worker threads; the issue has been open since
  January 2026.

# Sampling with operation labels — the design

The original idea, written up 18.08.2026 and revised as things were measured. Numbers that have
since been established by experiment are marked as such; the rest is still reasoning.

## What this is, and what it is not

**A developer's tool.** It runs in profiling sessions and perf harnesses, on a machine the developer
controls. It is not an always-on agent inside a live system, and nothing should be designed as if it
were. A whole CPU core for the sampler is an accepted cost on that basis.

**What it must do**, in the order that matters:

1. **Profile nanosecond operations.** This is the regime nothing else reaches, and it is where the
   ~2 ns hook earns its complexity — not to save resources, but because a 100 ns instrument would
   be measuring itself.
2. **Collect a parallelisation profile** — threads, virtual threads, and coroutines. This is a
   product requirement, not a nice-to-have: coroutines breaking attribution is one of the two
   reasons the incumbent failed on the project this came from. See [case.md](case.md).
3. **Answer "where exactly should we look, and why".** Note that today the report answers *where* —
   shares and call counts — and not *why*. That gap is deliberate to record rather than paper over.

Both regimes are in scope: nanosecond operations, and the coarser microsecond-to-millisecond work
the Calcite trial covered. We do not get to choose what a real target turns out to be.

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

## Two tiers

Everything above is thread-scoped: the slot lives on a thread and the sampler walks threads. A
logical operation that forks across four threads appears as four unrelated operations, because
nothing ties them together.

Tying them together needs an identity that travels — a context object propagated at every
hand-off — and you cannot allocate one per twenty-nanosecond operation executed billions of times.
So the two cannot share a mechanism, and the tool has two tiers.

**Fine — physical operations.** A hash probe, one filter condition. Billions of executions, tens of
nanoseconds each, calling nothing interesting. An integer in a thread slot, measured by sampling. No
identity, no allocation, no timestamps. Self time is all they have, and since they are atomic, self
time *is* their inclusive time.

**Coarse — logical operations.** Expanding a frontier, applying a filter set, serving a query. A
context object with a type, a parent reference and timestamps at both ends, propagated across
hand-offs. Thousands of executions rather than billions, and each long enough that an allocation and
two clock reads are lost in the noise.

**They meet at the slot**, which carries the fine operation id *and* a reference to the current
coarse context. Every sample records the pair.

### Where the boundary is

Stating the tiers as size ranges — "tens of nanoseconds" against "milliseconds" — is how this
document read for a long time, and it is wrong in both directions. The boundary is not a size. It
comes from what each mechanism costs per execution:

| tier | instrumentation | cost per execution |
|---|---|---|
| fine | two opaque stores | **1.7–2.3 ns**, measured in phase 3 |
| coarse | two `nanoTime` plus an accumulate | **21.5 ns**, fitted from the Lucene timed-wrapper run |
| | …plus one allocation for the context | **not measured** — see the open question |

Taking coarse at ~40 ns pending that measurement, an operation may be coarse when **both** of these
hold. They protect different things and neither implies the other:

| # | condition | protects |
|---|---|---|
| 1 | `40 ns × calls ≤ 1% of the run` | **the program** — that you are measuring what you started with |
| 2 | `40 ns ≤ 5% of the operation's own duration` → **d ≥ 800 ns** | **the number** — that it describes the code and not the instrument |

Condition 1 alone is not enough, and the counter-example is small enough to be easy to miss: a 40 ns
operation called rarely passes it comfortably, and is then reported at 80 ns. The program is
undisturbed and the measurement is 100% wrong.

Written in terms of the operation's share of the run, the two collapse into one line:

> **d ≥ max(800 ns, 4 µs × share)**

| operation holds | must be at least |
|---|---|
| 1% of the run | 800 ns |
| 20% | 800 ns |
| 50% | 2 µs |
| 100% | 4 µs |

**Condition 1 only binds above about 20% share.** Everywhere else the 800 ns floor decides, so in
practice the rule is: **an operation under ~1 µs cannot be coarse — and that is the fine tier's
entire reason to exist.**

**Neither tier has an upper limit, and this is the part that surprises.** Calcite's rule labels were
hundreds of microseconds — coarse-sized by any reading — and were measured with the *fine* tier,
agreed with JFR to about a percentage point, and produced the 275× finding. Above 1 µs both tiers
work, and the question stops being *how long is it* and becomes *do I need per-instance data*.
Coarse tells you more; fine is 20× cheaper and, unlike a timer, its error is not proportional to
call count.

**Fine's own floor is 50 ns**, and it comes from measurement rather than from the hook's price:
below 45 ns the sampler reads 4.5–9.1% low, and C2 moves work across label boundaries
undetectably. Under 50 ns, label the enclosing loop with `op(id, times = n)` and divide.

**Two exclusions that have nothing to do with size.** Coarse needs a begin and an end with the
context flowing between them, and third-party code does not always offer one — a *placement* limit,
not a tier limit. And a loop body labelled `times = n` has no pair of instants to timestamp, so
batch labels are fine-only by construction.

### Negligible operations

An operation the sampler cannot see is one under roughly a thousand samples, which is about a second
of occupancy, which in a hundred-second run across eight threads is **under 0.15% of the work**. It
cannot be where the time went. We do not chase these, and a tool that tried would spend its
credibility on false positives.

**But cost and consequence are different numbers.** A 500 ns operation that takes a global lock a
thousand times a second is 0.05% of the run and invisible — and if each acquisition parks fifteen
threads for 300 µs, it *causes* four and a half seconds of waiting per second of runtime. The
requirement this puts on the tool is not to find the operation. It is that **the waiting must never
be invisible**, which is the same demand the report's closing counterfactual warning already makes
from the other direction.

### What the rule cannot do, and the check that covers it

The rule bounds added **work**. What breaks a parallel program is added **critical path** — a sum
against a max, and the two are unrelated. Forty nanoseconds added to eight hundred thread-seconds of
work is nothing; forty nanoseconds added to a 40 ns barrier section doubles it, and every thread
joining that barrier waits. Worse, those waiting threads are labelled too, so their occupancy rises:
instrumenting one operation makes **other** operations look expensive, and the report points at the
innocent ones.

The tool knows call counts and durations. It cannot know the critical path, so no static condition
can bound this. **The tier rule is necessary and not sufficient**, and its companion is procedural:
A/B the run three ways — bare, instrumented-but-inert, instrumented-and-labelled — comparing
**end-to-end throughput**, never summed occupancy, which is a sum and hides exactly this failure. It
has already caught one real instance: a careless Lucene wrapper, 13.3% slower, with one clause
moving from seventh to third.

### What the fine tier measures

The mechanism does two things. The hook **counts**, and the sampler **photographs** every thread at
one instant every millisecond. Every number below is derived from a count and a stack of
photographs, and everything the tier cannot do follows from the same two sentences.

| from | metric | what it is |
|---|---|---|
| counting — exact | **calls** | not estimated; the hook increments |
| photographs | **occupancy** | photos × step — summed thread-time |
| | **share** | its slice of all labelled photos |
| | **noise** | `1/√hits`, how wrong chance alone makes the share |
| | **long executions** | occupancy inside executions that outlived a tick |
| both | **implied per call** | occupancy ÷ calls |

**Concurrency costs nothing extra.** When three threads are inside an operation at one instant, that
photograph contributes three. Lucene ran eight workers and needed no new machinery for it.

**And one metric is available that we do not yet take.** Each photograph says *how many* threads were
inside operation X at that instant, so the run yields a per-operation concurrency distribution. No
stack profiler can produce it — they sample each thread on its own timer and never hold all threads
at one instant. See [ideas.md](ideas.md) item 16.

### Where the fine tier breaks

Sized correctly — 50 ns to 1 µs, or larger by choice — most of the ways sampling can fail either
define the boundary or belong to the other tier. The third column is the one that matters:

| | what breaks | does it apply to a correctly-sized fine operation? |
|---|---|---|
| **A** | no per-execution durations | **Always — and only hurts when the operation is bimodal.** A photograph has no duration, so there are no percentiles, ever. An operation that is 50 ns normally and 100 µs when it hits a lock reports 150 ns, and no execution was ever 150 ns. That operation is two operations wearing one name: the fast path is fine, the slow path is its own coarse label. |
| **B** | occupancy is not elapsed time | **Always.** A property of the unit, not of the operation. Eight threads working five seconds is forty seconds of occupancy in a five-second run. For computation the sum is real; for waiting it is not, because waiting happens simultaneously. **Solvable, and from photographs we already take** — see below. |
| **C** | waiting and working look identical | **In two forms.** Machine-wide stalling — preemption and GC — lands on everything uniformly (13.27–17.90% across all twenty bench operations), so it is the run's doing and not the operation's. Genuine contention is real, and the answer is to split the operation, not to re-tier it. |
| **D₁** | coroutines: the label follows the thread | **Never.** The defect needs a suspension point between the label write and the restore, and a suspension costs a continuation allocation plus a dispatch — hundreds of nanoseconds at best. "Fine-grained" and "suspends inside the operation" are mutually exclusive by construction, so the same-thread assumption is self-enforcing. See [ideas.md](ideas.md) item 8. |
| **D₂** | virtual threads: the registry explodes | **Yes, today, and it needs no suspension at all.** Every virtual thread appends a slot to a `CopyOnWriteArrayList` — an O(n) copy per thread created — and the sampler walks the list every millisecond. Ordinary 20 ns operations on a million short-lived virtual threads make the tick unbounded. |
| **E** | resolution floors | **Not a break** — it *is* the lower edge, defined above. |
| **F** | no structure: no caller, no nesting | **Always, and hurts never.** Atomic means there is nothing under an operation to lose. It is the reason the coarse tier has to exist, not a defect in this one. |

**So the genuine residue is three items**, and two of them turn out to be one feature. **A** and
**C** are the same case for an operation in this band — see the next section but one — and **B**
dissolves once the photographs are read for more than a sum. **D₂** is a registry problem in shipped
code with nothing to do with tiers, and it is the only outright defect on the list.

### Turning occupancy back into wall time

Recording each thread's *state* beside its label splits occupancy into working and waiting, which is
the obvious half of the fix and is not sufficient on its own. Three different situations produce
identical waiting occupancy:

| what happened | waiting occupancy | real cost |
|---|---|---|
| a hundred threads wait one second, all at once | 100 s | **1 s** |
| one thread waits a hundred seconds | 100 s | **100 s** |
| ten threads wait ten seconds each, staggered | 100 s | *in between* |

Thread state answers *what was this thread doing*. The question here is *how many were doing it at
the same instant* — and each tick already knows that, because the sampler photographs every thread
in one pass. Kept rather than summed away, it closes the gap arithmetically:

> **elapsed = occupancy ÷ mean concurrency while active**

**Worked, because the two answers are opposite.** A sixty-second run on sixteen threads, and
`lockAcquire` holding 100 thread-seconds of waiting occupancy:

| | mean concurrency while active | elapsed | the diagnosis |
|---|---|---|---|
| **convoy** — fifteen threads pile on at once, in bursts | 15 | **6.7 s** | worth at most 11% of the run; break up the convoy |
| **drizzle** — persistent mild contention | 1.7 | **59 s** | spans essentially the whole run; design the contention out |

Same occupancy, opposite fix. So **thread state and per-operation concurrency are halves of one
feature**, and neither is much use alone: the first says which part of the total is fiction, the
second says by how much. Both come from photographs already being taken. The concurrency half is
[ideas.md](ideas.md) item 16; the state half is what phase 6 exists for, and it also detects the
bimodal operation of case **A** and answers case **C** outright — one mechanism, three rows.

**Three limits survive it, and they are not small.**

- **`RUNNABLE` does not mean "on a CPU".** `BLOCKED`, `WAITING` and `TIMED_WAITING` are conclusive;
  `RUNNABLE` only means *eligible*, and it covers both a thread preempted by the scheduler —
  measured at 14–18% on a bench that never blocks — and a thread sitting in a blocking socket read,
  which the JVM cannot see into. Separating on-a-core from preempted still needs the per-thread duty
  cycle, [ideas.md](ideas.md) item 10.
- **Elapsed is not latency.** It says the operation had *somebody* inside it for 6.7 seconds. It says
  nothing about any single execution, so case **A** survives untouched and only splitting the
  operation answers it.
- **Elapsed is not a counterfactual.** *"This lock cost 6.7 seconds of wall clock"* is not *"you
  would be 6.7 seconds faster without it"* — those threads might have been blocked on something else
  regardless. That is the warning at the foot of every report, and no amount of sampling retires it.

### Two quantities both called "inclusive"

Take a parent doing 10 ms of its own work, forking four children of 100 ms each, and waiting.

- **Subtree thread-time — 410 ms.** All the CPU consumed by this operation and everything under it.
- **Own span — 110 ms.** How long the operation took, start to finish.

In sequential code they coincide. In parallel code they diverge, **and their ratio is the effective
parallelism of that operation** — 3.7× here. Not the whole-application coefficient, but per
operation: *this* subtree spreads out well, *that* one does not.

The two tiers produce one each. Sampling under a propagated context gives the thread-time, because
samples on every thread carry the context. The context's own timestamps give the span. Neither
alone answers the question.

### What this replaces

An earlier design derived inclusive time from a per-thread stack of ids. That brought torn reads,
recursion handling, and the problem that a stack is far harder to carry across a coroutine
suspension than a single value — and it could never have crossed a thread boundary at all, since a
forked child's stack does not contain the parent that forked it. The two-tier arrangement dissolves
all of it.

### Aggregation

Per coarse *type*, not per instance: fifty types is bounded, fifty thousand executions is not.
Instances fold into their type's statistics and are forgotten.

Spans are measured exactly, so they support a full distribution — count, mean, min, max and
percentiles from a logarithmic-bucket histogram at fixed memory cost. CPU is *sampled*, so
per-instance CPU and parallelism are meaningful only for instances long enough to have collected
samples: an 8 ms instance at a 1 ms tick with four threads busy gathers around 32, a 100 µs
instance gathers zero. Statistics over those must be computed above a sample threshold, and the
report has to say how many instances it left out.

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

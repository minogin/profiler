# Plan

What we are building, in order, and where each phase stands. Kept current — when a phase closes,
its section is rewritten to say what was actually built rather than what was intended.

Findings, techniques and dead ends live in [findings.md](findings.md). The idea and the prior art
live in [profiler.md](profiler.md). The trial on Apache Calcite has its own record in
[trial.md](trial.md). Where the existing tools fall short — the running case for building this at
all — is [case.md](case.md), and what we might do but have not committed to is [ideas.md](ideas.md).
What each document is for is [index.md](index.md); the short version of the whole thing, in plain
words, is [tldr.md](tldr.md).

| phase | subject | status |
|---|---|---|
| 1 | The bench — a workload whose true answer is known | **done** |
| 2 | The fine tier — slots, hook, sampling thread | **done** |
| 3 | Verification — sampler against the truth | **done** |
| — | Trial — the fine tier on somebody else's code | **done** |
| 3.5 | What a share is worth — bounding the error, not classifying operations | **in progress** — measuring done, thresholds next |
| 4 | The coarse tier — contexts, spans, cross-tabulation | after 3.5 |
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

## Trial — the fine tier on somebody else's code · done

Pointed at **Apache Calcite 1.42.0** query planning. Full record with every number in
[trial.md](trial.md); the harness is [`trial/`](../trial). What it settled:

**The candidate qualified.** A four-table chain join with join associate enabled takes 16–20 s to
plan, against 184 ms for three tables. JFR's flame graph is over 60% JDK collections and string
building, its inclusive view is one recursive spine where a dozen frames all read 80–99%, and the
frame carrying half the time — `ConverterRule.onMatch` at 49.14% — is not a rule at all but the
method twenty distinct enumerable rules inherit. The identity the domain cares about was not the
identity the stack had.

**The tool answered.** Labels on the rule *instance*, placed through Calcite's `RelOptListener`,
split that 49.14% almost entirely onto one rule: `EnumerableMergeJoinRule`, 46.18%. Removing it
takes planning from **17.8 s to 64.7 ms — 275× — for a plan 0.0026% worse by the planner's own
cost model.** That is the finding, and it is actionable.

**The sampler was validated against something we did not write.** Our shares and JFR's inclusive
shares agree within about a point on every rule in the slow configuration. Phase 3 proved the
sampler against our own bench; this is the first independent check, and it holds.

**Where they disagree, we learned something.** On a second configuration the two differ by 12 pp on
one rule, eleven times its noise floor, in a single shared run. Part is JFR stack truncation at 64
frames — measured, and raising the limit moved the number the predicted way. A label in a slot has
no depth, so that class of error cannot reach it. The remainder is unexplained and is an open
question. Separately: JFR delivered 6.3 ms and 13.7 ms when asked for 1 ms, where ours held 1.02 ms
and said so.

**What it cost:** nothing measurable. The labels sit on boundaries costing hundreds of
microseconds, so a 2 ns hook is parts per million. Both A/B comparisons came out with the wrong
sign, which is this machine's way of saying "below the floor".

**The friction, which is what phase 4 has to answer** — all nine items are in
[trial.md](trial.md#6-the-friction--what-the-trial-says-about-the-tool). The four that change the
design:

- **There is no enter/exit hook.** `op(id) { }` is a block, and almost nothing in third-party code
  is ours to wrap in a block. The trial wrote its own fifteen-line helper against `Profiler.slot()`.
  `Profiler.enter(id)` / `Profiler.exit()` and the stack they imply belong in the library, and the
  coarse tier needs exactly the same thing.
- **Non-lexical placement leaks silently and in the contaminating direction.** Calcite's "after"
  notification is not in a `finally`; a rule that throws leaves the label set and every later sample
  is billed to it. The trial added its own balance check. The library should provide one.
- **The report needs a counterfactual warning.** A 46% share became a 275× speedup, because the
  rule inflated everyone else's work as well as its own. Shares say where time went, not what
  removing something would do, and nothing in the output says so.
- **Call counts were the most valuable column.** They separate "1.5 ms per firing, 5,034 firings"
  from "9 µs per firing, 245,190 firings" — opposite problems with opposite fixes, and no stack
  profiler has counts at all.

**And the coarse tier's shape came out of it.** One plan is a coarse operation. Per-plan duration
varied 20–110 ms for byte-identical work, which nothing in the current output explains and which
per-instance spans with percentiles exist for. The sentence the trial wanted to write and could
not: *of the 48 ms median plan, 40% is `FilterIntoJoinRule`*.

## Phase 3.5 — what a share is worth · in progress

The fine tier is marked done, and it reports shares of **occupancy** — how many threads were inside
an operation — while presenting them as time. Three things stand between those two quantities and
none of them has been checked: an operation may block, threads may outnumber cores, and an
operation may not be the size its label claims.

**The decision that shapes this phase: bound the error, do not classify the operations.** The
tempting design is a rule for which operations are allowed to be fine. That rule cannot be written.
Nothing is guaranteed non-blocking — any allocation can meet a GC pause, any access can page-fault,
any thread can be descheduled between two instructions — so "could this block?" marks everything
coarse and answers nothing. Worse, contention is a property of the *run* and not of the code: a
`StampedLock` optimistic read is a 15 ns fine operation in a read-mostly phase and a parked thread
in a write-heavy one, from identical source. What can be measured is *how much* of the occupancy
was not CPU, and that single number bounds the error on every share at once, whatever caused it.

This is the same discipline as the noise floor already in the report — that says how wrong chance
could make a share; this says how wrong blocking could make it.

### 1. The CPU duty cycle, and the bound it implies · **done**

`ThreadMXBean.getThreadCpuTime(id)` per registered thread, sampled at a **low** rate — once a
second, or every *n* ticks — never per tick. The ratio of summed CPU delta to summed wall delta
across live threads is the duty cycle.

**Built.** [`Duty.kt`](../src/main/kotlin/com/minogin/profiler/Duty.kt): `ThreadCpuClock` checks
support, enables the clock, and *measures* its resolution; `DutyCycle` rides the sampler's tick loop
at a one-second window; `DutyReport` renders the line and the bound. It is in both reports — the
bench's and the library's `Report.render()`, which is where it matters, since it is the thing that
tells a user of the tool what their shares are worth.

**And what it found, which was not what this section expected.** The null test — the ordinary bench,
which allocates nothing and blocks on nothing — read **78%**, not ~100%. The implementation is
right: on this machine eight workers and a spinning sampler on 16 logical cores lose 14–18% of
their wall time to the scheduler, in preemptions of milliseconds. *Never blocks is a property of
the code; being on a CPU is not.* The full numbers are in [findings.md](findings.md#the-duty-cycle).

So the ground truth for the null test could not come from the configuration, and it now comes from
a second measurement: the workers' own account of their preemptions, taken from gaps in the clock
readings the run loop already makes to check its deadline. Two mechanisms with nothing in common,
agreeing to 0.06–0.54 pp across five configurations. That is a far stronger check than matching a
number we had assumed, and it is phase 1's two-truths discipline arriving where it was needed.

The report line, beside the achieved sampling rate:

> *threads were on CPU 96.4% of sampled wall time — at most 3.6 pp of any share is occupancy that
> was not CPU*

**Why it is an upper bound.** The non-CPU fraction is all the stalling there was, from every cause
together. Even if all of it landed on one operation, no share can move by more than that fraction.
Worked: an operation at a 50% occupancy share, with threads 97% on CPU and *every* stall belonging
to that operation, has a true CPU share of `(0.50 − 0.03) / 0.97` = 48.5% — so the reported figure
is within 1.5 pp no matter how the stalling was distributed. At 23% on CPU the same arithmetic
allows the operation to be almost entirely waiting. So at 96% the ranking is trustworthy; at 23%
the report is describing where threads *sit* rather than where cycles *go*, and it must say so
rather than let the reader assume otherwise.

**"Blocking" here means waiting for another thread — not waiting for memory.** A cache miss to DRAM
is ~100 ns during which the core is *occupied*: it is CPU time, it is real cost of the operation,
and the sampler already attributes it correctly. Nothing in this phase should try to detect or
subtract it. The same goes for an uncontended lock, which is a CAS and does not block. What this
phase is bounding is time when the thread was **not on a CPU at all**.

**It also retires the threads ≤ cores assumption** for free. The sampler reads slots, not cores, so
200 runnable threads on 16 cores over-read CPU by 12× — and that shows up as a duty cycle far below
100% without any special handling. It retired rather more than that: on this machine the scheduler
stops keeping runnable threads on cores long before the cores run out, at nine busy threads of
sixteen.

**Needs:** the owning thread's id on the slot, recorded at slot creation, off the hot path. Store
the id rather than a `Thread` reference so a dead thread is not pinned. Done — `OpSlot.threadId`.

**Platform caveat, measured and not assumed.** On Windows this comes from `GetThreadTimes`, updated
on scheduler ticks. Probed by spinning and watching the counter move, the step is **15.625 ms** —
1.6% of a one-second window, and the quantisation telescopes across windows, so the aggregate is
sound while a single window can read 100.29%. `isThreadCpuTimeSupported` and
`isThreadCpuTimeEnabled` are both checked and the clock enabled if it is off; where the resolution
cannot support the window the report says the number is unavailable rather than printing a bad one.

**What it costs.** Up to 214.7 µs per walk, once a second, on the sampling thread. The achieved
step stayed 1.001 ms with zero resyncs.

**The one weakness, and it is now measured.** The duty cycle covers every registered thread while
the shares cover labelled samples only, so a thread parked outside any operation lowers the bound
without appearing in what is being bounded. In starvation mode that is stark: 18.83% duty and a
formally unbounded error, while the three working threads were on CPU 96% of the time. The report
says so; tightening it needs the duty cycle per thread paired with that thread's labelling, which
wants the same slot index the long-instance detector wants — [ideas.md](ideas.md) item 10.

### 2. The long-instance detector · **built, thresholds not yet settled**

The sampler already reads the slot's operation id. Have it read **that operation's call counter**
in the same tick and keep the pair from the previous tick:

| across two ticks | id | counter | meaning |
|---|---|---|---|
| same | same | increased | many short instances — genuinely fine |
| same | same | **unchanged** | one instance spanning ≥ 1 tick |

A 20 ns operation has a 1-in-50,000 chance of being caught by a single sample, so an instance
surviving two consecutive ticks is not the operation its label describes. **Entry and exit are
untouched** — the counter increment already exists and the sampler reads one extra word.

Split the sampler's per-operation counter in two, *fresh* and *stuck*, so one pass yields both an
occupancy share and a running share, and the report can say per operation: *340 instances lasted
over a tick — this is not a fine operation, consider a coarse label.*

**Where the previous tick's pair is kept matters.** Not on the `OpSlot`: the sampler writing to that
object would invalidate the owner's cache line on every tick, which is precisely the false sharing
the padding exists to prevent. Give `OpSlot` an immutable index assigned at construction and let the
sampler keep its own parallel arrays. Reading `counts[id]` already touches a second cache line per
slot per tick, so the sampler's own cost roughly doubles — irrelevant, it has a core.

**What the sampler's read of `counts[id]` costs the owner, since it is the obvious worry.** The
owning thread writes that counter on every call, so the sampler pulling the line into shared state
forces one coherence round trip on the owner's next write. That is once per slot per tick against
millions of increments in the same millisecond — of the order of one increment in a million pays
for it. Expected to be unmeasurable, and it should be *measured* rather than assumed, in the
manner of everything else here: `--labels` and `--sampler` are already separate switches, which is
exactly the comparison this needs.

**Limits, which belong in the output as much as the finding does.** The floor is one tick: a 50 µs
lock stall can never span two ticks and is invisible. And *stuck* conflates blocked, descheduled,
and legitimately-long-but-running — separating those needs the thread's state, and only for slots
the counter test already flagged, which is normally almost none.

**What it came to.** `OpSlot` carries an immutable index, recycled when a thread dies; the sampler
keeps `prevOp`/`prevCount`/`prevStuck` arrays of its own and reads the counter opaquely, so the
owner's hot path is untouched. Verified in both regimes: on a quiet machine the run-wide rate of
executions outliving a tick is 0.04% and nothing is flagged; at 15 threads on 16 cores it is 15.52%
and *still* nothing is flagged, because preemption raises every operation together — 13.27% to
17.90% across all twenty, worst 1.15× the run-wide rate. That uniformity is what makes "excess over
the run-wide rate" a valid test, and it is now measured rather than argued.

It is also a third independent view of the stalling the duty cycle bounds: 18.04% non-CPU, 17.11%
by the workers' own gaps, 15.52% by the detector, in the predicted order since the detector's floor
is a whole tick. Full numbers in [findings.md](findings.md#the-long-instance-detector).

**What is not settled: the thresholds.** Naming an operation takes three conditions together — a
rate well above the run-wide one, a floor under the rate itself, and enough long executions that
one GC pause cannot be the whole story. All three are provisional, in one place
(`Report.SUSPECT_*`), and the experiments that settle them are below. The third condition was not
in the original design and was added the first time this ran: on a quiet machine the run-wide rate
is 0.04%, so a single long execution made `serialize` 3.85× the baseline and the check accused it.

### 3. Implied per-call duration · **built**

`hits × step / calls`, which is already derivable and was the most valuable column in the Calcite
trial. It is the smell test the person who wrote the code can apply and the tool cannot: an
operation known to be 20 ns showing 500 ns implied is stalling on something. It inherits the same
bound as everything else, and the column heading says occupancy rather than time.

Verified against the bench, whose true answer is known, over a hundredfold range of durations: it
reproduces each operation's configured duration times the load factor, and where it falls short it
reproduces the *known* attribution bias rather than a new one — `tinyStep` at −14% against phase 3's
−15.5% for the same operation. In the API demo it is exact: 120 busy-loop iterations at 0.83 ns
read back as 102.0 ns.

### What the tool does about it — the severity ladder

Three severities, and one question decides which a finding gets: **can the condition be attributed
to the code, or only to the run?**

| severity | when | what happens |
|---|---|---|
| **fatal** | the label is provably not measuring what it claims, and the condition is a property of the code rather than of the machine | sampling stops, the report is marked invalid and leads with the failure, the operation and the fix are named |
| **warning** | the measurement is honest but the label is in the wrong tier, or the run's conditions limit what the numbers mean | printed with the report, which stays valid |
| **note** | what the reader needs in order to read the numbers — the bound, the noise floor, what was excluded | printed |

**Too small is fatal. Too long is a warning.** They look symmetric and are not:

- *Too small* is deterministic. A 20 ns operation is 20 ns on a loaded machine, a quiet one, a
  different machine, and every rerun. There is no run in which the label is fine, so stopping costs
  the user one run and saves them a wrong conclusion. It is also detectable within seconds — see the
  bound below, which needs no hits at all.
- *Too long* depends on the run. Contention is a property of the workload that day; a `StampedLock`
  optimistic read is a 15 ns fine operation in a read-mostly phase and a parked thread in a
  write-heavy one, from identical source. Worse, Calcite proved that an operation can outlive a tick
  and still be measured perfectly: those rule labels are coarse-sized, and their shares agreed with
  JFR to about a percentage point and became the 275× finding. Aborting on that signal would have
  destroyed the most valuable result this project has produced.

**Fatal never means killing the host.** This is a library inside somebody else's process. Fatal
means the profiling session stops and says why; the application carries on. Our own bench may
exit(1) on it, because the bench *is* the application.

**And it is switchable, because of Calcite.** `strict = false` downgrades fatal to warning, for the
case where the labels are on code you do not own and cannot resize. The two harnesses in this
repository both switch it off deliberately — they exist to stress the instrument below its floor,
which is exactly what a real user should be stopped from doing by accident.

### Catching an operation that is below the floor

The implied duration already says it when the operation gets hits. What makes this a *check* rather
than a column is that it also works when the operation is never sampled at all, which is the case
that looks like a blind spot and is in fact the strongest evidence available.

Seeing zero hits in *n* samples bounds the true rate at under `3/n` with 95% confidence — the rule
of three — and the arithmetic then collapses: an operation's total occupancy is under **three ticks**
however many samples were taken, so its cost per call is under `3 × tick / calls`. Forty million
calls at a 1 ms tick puts it under 0.075 ns, a fraction of one cycle.

One formula covers both cases. Take the upper confidence bound on the hit count —
`hits + 2√hits + 3`, which is just 3 when hits is zero — and if even *that* implies a duration below
the floor, the operation is too small, whatever the sampler did or did not catch.

Two adjustments keep it from accusing the innocent: the sampler reads operations below 45 ns some
5–9% low, which is systematic and in the wrong direction here, so the bound is inflated by 20%
before comparing; and everything else is left to the bound's own conservatism. `edgeScan` at 45 ns
is genuinely under the floor and is deliberately not named, because being sure matters more than
being complete.

**The call counts have to be this session's.** The first version divided session hits by the
registry's process-lifetime totals, which include threads that died before sampling began — the
bench's warm-up is exactly that — and every implied duration came out about a third low. It was
caught because the check is a *bound*: it accused a 20 ns operation of being under 7.9 ns, and an
upper bound below the truth is impossible. An estimate that came out 60% low would have been
believed. The sampler now snapshots the counts before its first tick.

**What cannot be caught, and it belongs in the message rather than in a document.** C2 unrolling
adjacent short loops and shuffling work across the label boundaries is undetectable — the demo lost
95% of one operation and nothing in the data said so. That is a second, independent reason not to
put labels on tiny operations, and the only defence is the advice, which is why the advice is
printed rather than filed.

### What settles the thresholds, and in what order

Decided with the numbers rather than in advance, because the first thing the detector will do on
real code is fire on operations whose measurement is perfectly honest.

1. ~~build the detector and the implied duration column, verify on the bench where nothing should
   fire~~ — **done**.
2. ~~Run it on the Calcite trial.~~ **Done, and it changed the design.** The controls held — rules
   under 10 µs per firing read 0.00%, rules over 100 µs read 39–75% — and GC turned out not to be
   the false-positive source it was feared to be: 119 pauses totalling 2.6% of wall time, against
   the duty cycle's independently measured 3.01% of occupancy that was not CPU.
   What failed was the decision rule. Judging each operation against the *run-wide* rate of long
   executions assumes long executions are the exception; in Calcite's planner that rate is 53.52%,
   because most of those labels genuinely are coarse. Nothing could ever be named. **The floor now
   comes from the duty cycle** — what the machine did to everything — which separates all three
   data sets we have. Full numbers in [findings.md](findings.md#the-detector-against-calcite).
3. Add the contended bench operation, which gives a known blocking rate to measure against.
4. Then decide what the tool *does* about a flagged operation.

**Step 4 is deliberately last.** The Calcite labels sit on operations of hundreds of microseconds:
they are not fine operations by any definition here, and yet the shares they produced agreed with
JFR to about a percentage point and became the 275× finding. So the detector will flag them, and
aborting on that would have destroyed the single most valuable result this project has produced.
What distinguishes *honest but coarse-sized* from *corrupted* is not duration but whether the
thread was on CPU while it ran — which is the per-operation duty cycle, [ideas.md](ideas.md) item
10, and not something this signal can decide alone. Erring early is right; erring on this signal
alone is not.

### What this phase deliberately does not do

Classify operations as fine or coarse. Detect locks specifically. Attribute non-CPU time to a
particular operation — it bounds the error globally and does not apportion it. Nor does it touch the
coroutine or virtual-thread hazards, which are [ideas.md](ideas.md) item 8.

**Relationship to phase 6.** Phase 6 samples each thread's *state* alongside its label, and its
heuristic for telling executing from spinning from parked is exactly what would resolve the
ambiguity in *stuck* above — and would let non-CPU time be apportioned to an operation rather than
merely bounded. This phase is the cheap aggregate that can be had now, before there is anything
dispatcher-shaped in the bench to develop that heuristic against. The two are the same question at
two prices, and doing the cheap one first is what makes the expensive one checkable.

### Bench work, and it is the real work

The bench has never blocked and never oversubscribed — every thread runs the same schedule flat out
on at most one core each. Nothing here can be verified against it as it stands. Needed:

- an operation that contends on a real lock, with the contention rate a parameter, so the duty cycle
  and the *stuck* counter have a known truth to be checked against;
- a mode with threads well above core count;
- ~~the expected duty cycle computed from the configuration~~ — **done, and it had to be done
  differently.** The configuration cannot predict the duty cycle, because the operating system
  deschedules threads that have nothing to wait for. The second truth is instead the workers' own
  account of their preemptions, from the gaps in the clock readings the run loop already makes:
  `Worker.stallNanos`, no new bench machinery and nothing added to the hot loop.

**Done when:** ~~duty cycle reads ~100% on the existing non-blocking bench~~ → the OS accounting and
the workers' own account of the same quantity agree, which they do to 0.54 pp at 8 threads and
0.36 pp in starvation mode; an injected blocking operation moves it by the injected amount within
measurement; threads at 2× cores are reported as such; and the detector fires on the injected
blocker and stays silent otherwise.

## Phase 4 — the coarse tier · after 3.5

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

**Unsettled, and it has to be settled before the CPU column means anything: what is a sample worth
when the thread is not running?** The sampler counts a slot as being inside its operation whether
the thread is on a CPU, blocked on I/O, parked on a lock, descheduled by the OS, or — for a virtual
thread — unmounted entirely. What it measures is **occupancy**: how many threads are inside this
operation. Not CPU.

Occupancy is a real measurement, not a broken one. Waiting is slow, and a report that ignored
waiting would be answering the wrong question. Two different questions are in play and both are
legitimate:

- *Why does this take 200 ms?* — waiting counts in full.
- *Why is the machine saturated, and why does more parallelism not help?* — waiting costs nothing.

**What must not happen is mixing them, and the reason is additivity.** CPU time adds up across
threads: ten thousand threads burning 1 ms each is ten seconds of core time, a real quantity you
can act on. Wall-clock waiting does not add up: ten thousand threads waiting 200 ms each is 200 ms,
because they wait simultaneously. Summed occupancy is therefore not a quantity at all — it is
neither latency nor CPU. Sample ten thousand threads parked in one operation at a 1 ms step and
after 200 ms you have two million samples, which reads as 2,000 seconds of a 200 ms wait that used
no CPU.

The design above is right about where waiting belongs: **span** measures it exactly, and
**span − CPU** *is* the waiting, quantified. The defect is only that the CPU column as built would
be occupancy, so `span − CPU` would come out near zero and the report would say "no waiting here"
in exactly the case where it is all waiting. The one answer that was wanted, inverted.

Either the sampler learns to distinguish a running thread from a parked one, or the column is
renamed to what it actually measures and a separate mechanism supplies CPU. This is the same
problem as the coroutine one in a different costume — the slot is attached to a thread, and the
thing being measured is not a thread. See [ideas.md](ideas.md) items 8 and 9.

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

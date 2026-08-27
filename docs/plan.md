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
| 3.5 | What a share is worth — bounding the error, not classifying operations | **done** |
| 3.75 | Placement — enter/exit, the balance check, report polish | **done** |
| — | Trial 2 — Lucene: concurrent, and identity by instance rather than by class | **done** |
| — | Trial 3 — Netty: few event loops, many tasks, time that is mostly not CPU | **done** |
| — | Trials 4+ — a compiler, two negative controls | after Netty |
| 4 | The coarse tier — contexts, spans, cross-tabulation | after the trials |
| 5 | Crossing threads — propagation, and per-operation parallelism | not started |
| 6 | Thread state and the whole-application parallelism coefficient | **partly done** |
| 7 | Library surface — annotations, agent, results API | not started |
| 8 | JFR output | not started |

**What this is for.** A general-purpose tool, released open source, for auditing the performance of
applications with hot short operations. Not tied to any one system — the graph-flavoured operation
names in the bench are a synthetic workload, nothing more.

Stack: Kotlin/JVM, Gradle, no dependencies. Output to the console for now; see phase 8.

---
---

## What happens next, in order

Three small fixes first, then a decision point, then the coarse tier. The three are not tidying:
each one is a number in the report that is currently **wrong or misleading**, and each is now backed
by measurement rather than by argument. Together they are the accumulated interest on three trials.

### Step 1 — the three small things

**1a. The floor check stops being fatal.** **done.** [ideas.md](ideas.md) item 12.

*What was built.* Smaller than it looked: `Report.tooSmall()` already listed every below-floor label
at the end of every report, so the once-a-second `floorCheck` existed **only** to stop the run. It
is gone, with `nextFloorCheck` and `FLOOR_CHECK_NANOS`; the list stays and now names every offender
instead of the first one found.

That left `strict` gating nothing and `failure` with no producer, so `strict` was repointed at the
condition the ladder above always said was fatal and the code never implemented: a leaked label.
`Profiler.expectBalanced()` now calls `Sampler.fail()` under strict, naming the operation that was
open and the thread it was open on. The bench's own `--strict` no longer tests anything — the bench
never leaks — so `--leakcheck` stages a leak on purpose and asserts **both** directions: that it
stops under strict, and that it stops *only* under strict. A check that asserted the first alone
would still pass if the flag were wired to nothing.

*Verified.* `--leakcheck` passes both rounds. The bench at one thread names four operations and
finishes; at eight threads it names none, because eight busy threads make every operation on this
laptop 2.7× slower than its single-threaded fit and a 20 ns label reads 55 ns. Same binary, same
machine, half an hour apart — which is a sharper disproof of the "deterministic" premise than the
run-length one that started this, and it is recorded in
[findings.md](findings.md#the-machine). The bench had no surface for the warning at all
(it builds a `Report` and then prints its own tables, never `render()`), so it prints the list now.

A label below the 50 ns floor halts the session under `strict`. It has now stopped a *correct* run in
**two consecutive trials**: Lucene, citing 27.5 ns for a label whose settled figure is 49.7 ns, purely
because this laptop's throughput falls 2.2× with run length; and Netty, flagging `route` at 41 ns —
which is genuinely below the floor and is a label any reasonable person would place on a scan of
eight short strings.

By the [accuracy principle](profiler.md#how-accurate-this-has-to-be-and-where-that-budget-goes) a
below-floor label cannot move a ranking, so the cost of being wrong about it is the loudest failure
the tool has — stopping a run that was fine. **Downgrade to a warning, keep the message unchanged.**
The estimator work in item 12 becomes optional rather than blocking.

*Not affected:* the balance check stays fatal. A leaked label invents attribution, which is a
different category from a label being small.

*Check it with:* the bench, where four of twenty operations sit below the floor on purpose — it must
warn on all four and finish. And `trial-netty --labels`, which must stop needing `strict = false`.

**1b. The duty cycle per thread.** [ideas.md](ideas.md) item 10.

Two lines of every report divide by the wrong thing today.

The bound — *"at most 81.2 pp of any share is occupancy that was not CPU"* — is computed over **every
registered thread**, while the shares it bounds are over **labelled samples only**. An idle pool
thread therefore poisons a warning that is not about it. Measured in starvation mode: 18.83% duty and
a formally unbounded error, while the three working threads were on CPU 96% of the time and their
shares were fine.

The fix needs both halves per thread: thread *i*'s stall fraction, which the duty walk already
computes and discards, and thread *i*'s labelled fraction, which is a per-slot counter the sampler
writes. The stall that could possibly be inside labelled work is then `Σ min(stall_i, labelled_i)`.

The same counter fixes the **coverage** line, which has the same defect from the other direction: on
Lucene, 79.2% of unlabelled observations were a thread that was not runnable, so coverage against
*runnable* occupancy is ~83% rather than the 49.8% printed.

*Needs:* the immutable slot index, which already exists.

*Check it with:* starvation mode (`--active=3 --threads=16`), where the bound must fall from ~81 pp
to under 1 pp, and the ordinary bench, where nothing should change.

*Verify first, because it decides the shape:* that a thread inside a label is always runnable. The
bench's `--lock` mode is where to check — the label there sits *outside* the acquisition precisely so
that a parked thread is still inside the operation, which is the counter-example if there is one.

**1c. The report stops presenting CPU as the truth.** [ideas.md](ideas.md) item 17.

*"share is of labelled samples and is occupancy, not CPU"* reads as an apology, and it is backwards
for most of what anyone profiles. Occupancy counts waiting in full, which is the right behaviour for
the latency question. The duty cycle exists not because CPU is the goal but because **summed
occupancy is only additive when it is CPU** — a hundred threads parked one second on one lock is a
hundred thread-seconds of occupancy and one second of real cost.

So the line should say what it guards against — *this total may be counting the same wait many
times* — rather than implying a CPU measurement was the objective and was missed. Same two sentences
of output as 1b, so do them together.

*Check it with:* reading it. No measurement involved; this one is wording.

### Step 2 — a look at what those three turn up

Deliberately a pause rather than a phase. 1b in particular changes two numbers in every report, and
the trials' recorded figures were computed against the old denominators. Nothing is expected to
break, but "nothing is expected to break" is exactly when this project has been wrong before.

### Step 3 — phase 4, the coarse tier

Unless step 2 turns something up. The argument for going here rather than to a fourth trial:

- **It is the missing half.** The tool measures thread-time and cannot say how long one request
  took. [profiler.md](profiler.md) lists *"answer where exactly should we look, and why"* as
  requirement 3 and the gap is recorded there as deliberate.
- **The trials have converged.** Calcite taught the tool `enter`/`exit`; Lucene taught that
  misplacement is the danger and that the lexical form was back; Netty needed nothing new and its
  friction list is mostly *"the host decides"*. A fourth returns less than the third did.
- **Phase 5 needs it**, and phase 5 is what the supply-chain traversal this project came from
  actually requires — nanosecond operations crossing coroutine suspensions.

**The first task is bench work, not tool work:** promote some bench operations to coarse, so there is
a known truth to cross-tabulate against. Same discipline as every phase here — build the truth
before the instrument.

### Not in this plan, and why

- **Trials 4+** — the compiler and the two negative controls. Still worth doing, and now for the
  write-up rather than for the build. Kept in the table above.
- **The virtual-thread registry** ([ideas.md](ideas.md) item 8, second half). The only outright
  defect in shipped code: every virtual thread appends a slot to a `CopyOnWriteArrayList`, which is
  an O(n) copy per thread created, and the sampler walks the list every millisecond. It bites no
  workload we can currently point at — neither trial creates virtual threads and coroutines run on
  ordinary carrier threads — so it is a **release blocker rather than today's work**. Measured cost
  at the 1024-slot ceiling: the walk is 291 µs of a 1 ms tick, and it was 195 µs before thread-state
  sampling was added.
- **The on-demand stack sampler** ([ideas.md](ideas.md) item 14). Affordable and proven to work; four
  design questions unanswered. Diagnostics rather than correctness.


## Architecture — two tiers

The tool measures two different kinds of thing, and they cannot share a mechanism.

**Fine — physical operations.** A hash probe, an edge scan, one filter condition. Billions of
executions, atomic in the sense that they call nothing interesting. Identified by an integer in a
thread-local slot and measured by sampling. No identity, no allocation, no timestamps.

**Coarse — logical operations.** Expanding a frontier, applying a filter set, serving a query.
Thousands of executions rather than billions, freely crossing thread boundaries. Each execution gets
a real context object: allocated, propagated across hand-offs, timestamped at both ends.

**Where the boundary is, and it is not a size range.** It comes from what the instrumentation costs
per execution — ~40 ns for coarse against ~2 ns for fine — and it lands at **d ≥ max(800 ns, 4 µs ×
share)**, so in practice: *an operation under ~1 µs cannot be coarse, and that is the fine tier's
entire reason to exist.* Neither tier has an upper limit — Calcite's rule labels were hundreds of
microseconds and the fine tier measured them correctly. The derivation, the two conditions it comes
from, the exclusions that are not about size, and what the rule cannot bound are all in
[profiler.md](profiler.md#where-the-boundary-is), which is where the tier definitions live.

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

## Phase 3.5 — what a share is worth · done

The fine tier is marked done, and it reports shares of **occupancy** — how many threads were inside
an operation — while presenting them as time. Three things stand between those two quantities and
none of them has been checked: an operation may block, threads may outnumber cores, and an
operation may not be the size its label claims.

**All three are now measured.** Every share carries a bound on how far occupancy can be from CPU;
an operation that cannot have been measured correctly stops the run within a second and says why;
one that is merely in the wrong tier gets told which kind of wrong it is. What is left open is the
value of the thresholds, which are provisional and gathered in one place — and the fact that
deciding *which* operation was waiting, rather than bounding how much waiting there was, needs the
thread's state sampled beside its label. That is phase 6.

**The decision that shapes this phase: bound the error, do not classify the operations.** The
tempting design is a rule for which operations are allowed to be fine — *on the grounds of whether
they block*. That rule cannot be written, and it should not be confused with the tier boundary in
[profiler.md](profiler.md#where-the-boundary-is), which is a different rule about a different
quantity: that one is decided by what the instrumentation costs, which is known in advance, while
this one would be decided by what the run does, which is not.
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
over a tick — this label is not the size it claims, and coarse is now affordable for it.* Not
*"this is not a fine operation"*, which is how this read before the
[tier boundary](profiler.md#where-the-boundary-is) was settled: the fine tier has no upper limit,
and a long label is measured perfectly well by it — Calcite's were, to within a point of JFR.
What being long changes is that coarse becomes **available**, offering per-execution statistics
that fine can never give.

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

**One rung is fatal, and it is not the one this ladder was written for.** A *leaked* label stops the
session. Too small and too long are both warnings:

- *A leak invents attribution.* A label left open at a point the caller declared quiescent bills
  every subsequent sample on that thread to the wrong operation, and the result does not look like
  an error — it looks like a finding, with a plausible number beside it. Nothing downstream detects
  it and no rerun makes it valid. Checked in both directions by `--leakcheck`, which stages one on
  purpose: a check that never fires is not a check.
- *Too small* was fatal, on the argument that a 20 ns operation is 20 ns on a loaded machine, a
  quiet one, a different machine and every rerun. Lucene falsified the premise — this laptop's
  throughput alone moves the estimate 2.2× — and the
  [accuracy principle](profiler.md#how-accurate-this-has-to-be-and-where-that-budget-goes) finished
  it: a below-floor label is one row of a list and cannot move a ranking, so the cost of being wrong
  about one is the loudest failure this tool has. It stopped a *correct* run in two consecutive
  trials. Now a warning, with the advice unchanged and every offender named rather than only the
  first.
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
case where the labels are on code you do not own and cannot fix. The leak is then still counted and
still printed — switching strict off buys a report, not silence.

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
3. ~~Add the contended bench operation~~ — **done**, `--lock`. The duty cycle tracked injected
   blocking from 0.4% to 64% of wall time and never missed the workers' own timing by more than
   1.15 pp; the detector named `lockedUpdate` alone; and the floor had to be rebuilt a third time,
   because a blocking operation is itself off the CPU and so was hiding behind the duty cycle.
4. ~~Then decide what the tool *does* about a flagged operation~~ — **done, and the answer is
   nothing fatal.** Calcite's rule labels are all flagged by this signal and their shares are
   correct; a run stopped over them would have destroyed the 275× finding. What the tool does
   instead is say *which kind* of long it is, where it can: the run's whole off-CPU budget bounds
   how much of an operation's long-running time could have been waiting, and on Calcite's slowest
   rule that leaves **at least 91% certainly on a core** — working, not waiting, share honest,
   label it coarse. Where the budget is big enough to explain the operation away, the verdict is
   *cannot say which* and it carries the size of the budget, which is the part a reader can act on:
   3.2% on Calcite against 35.2% on the contended bench. Resolving that properly needs the thread's
   state beside the label, which is phase 6.
   **Since resolved — phase 6's first half is built and the verdict no longer hedges.** It reads the
   operation's own long-and-waiting samples instead of the run's aggregate budget, so it now says
   *waiting* where it could only shrug before (`lockedUpdate`, 70.1% of its long samples parked,
   against the workers' own 73.4%) and *working* where the old test would still have hedged
   (Lucene's `clause:prefix`, 100.0% of its long samples runnable). The aggregate reasoning above
   survives only as the fallback when state sampling is switched off.

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

- ~~an operation that contends on a real lock, with the contention rate a parameter~~ — **done.**
  `--lock=<hold µs>,<interval ms>` gives `lockedUpdate`, which takes a real `ReentrantLock` and parks
  everyone else. It sits outside the operation catalogue and outside the two truths, so none of
  phase 1's machinery had to learn about an operation whose duration is not its configured one.
  The label goes *outside* the acquisition, so a parked thread is still inside the operation — which
  is the property being tested.
- ~~a mode with threads well above core count~~ — **done**, `--oversubscribe`. At 32 threads on 16
  cores the duty cycle reads 37.39% against the workers' own 37.71%.
- ~~the expected duty cycle computed from the configuration~~ — **done, and it had to be done
  differently.** The configuration cannot predict the duty cycle, because the operating system
  deschedules threads that have nothing to wait for. The second truth is instead the workers' own
  account of their preemptions, from the gaps in the clock readings the run loop already makes:
  `Worker.stallNanos`, no new bench machinery and nothing added to the hot loop.

**Done when** — all four hold, and the numbers are in
[findings.md](findings.md#against-injected-blocking):

- ~~duty cycle reads ~100% on the existing non-blocking bench~~ → the OS accounting and the workers'
  own account of the same quantity agree. **0.28–1.15 pp across six configurations.**
- an injected blocking operation moves it by the injected amount. **Tracked from 0.4% to 64% of
  wall time blocked, never off by more than 1.15 pp.**
- threads at 2× cores are reported as such. **37.39% at 32 threads on 16 cores, and the report says
  it is describing where threads sit rather than where cycles go.**
- the detector fires on the injected blocker and stays silent otherwise. **`lockedUpdate` named
  alone, at 20× and 200× the machine floor; nothing named in any non-blocking configuration.**

What is *not* settled is the thresholds, which remain provisional — and one limit is now measured
rather than asserted: with a mean wait of 342 µs, a third of a tick, the detector sees 36% of the
blocking that is really there. It still names the operation, because the tail of the distribution
crosses a tick often enough, but it cannot quantify it. Quantifying is the duty cycle's job. Neither
instrument is sufficient alone, which is the argument for having both.

## Phase 3.75 — placement: labels in code you do not own · done

Phase 7's essentials, pulled forward, because the Calcite trial could not use the documented API at
all and because the coarse tier needs the same machinery anyway. Everything here comes from the
[friction list](trial.md#6-the-friction--what-the-trial-says-about-the-tool) — observed, not
imagined.

**1. `Profiler.enter(id)` / `Profiler.exit()`, and the span stack they imply.** An *addition* to
`op(id) { }`, never a replacement. The block form stays the first thing anyone is shown: it is
inline, it costs nothing beyond the hook, and its `finally` is generated by the compiler so it
cannot leak. But `op(id) { }` is a block, and almost nothing in third-party code is ours to wrap in
a block — the boundary Calcite exposes is two callbacks, one before and one after. The trial wrote
its own fifteen lines against `Profiler.slot()`, which is public but was never presented as the way
to place a label. The two forms must nest in either order.

**2. A balance check, because a non-lexical label leaks silently and in the contaminating
direction.** Calcite's "after" notification is not inside a `finally`: a rule that throws leaves the
label set, and every later sample on that thread is billed to it. No error, no warning, a plausible
wrong number. The trial checked the span stack after every one of 484 iterations rather than
assuming it, and the library should offer that rather than making each user reinvent it.

By the severity ladder this is *fatal* — a leaked label is a property of the placement, not of the
run, and it invents attribution rather than losing it. What makes it early enough to be worth
failing on is the user calling `expectBalanced()` at a boundary they know is quiescent; what makes
it catchable at all is that depth is checked when the session stops.

**3. Fold operations with no samples, and say how many were folded.** Calcite's report carried
twenty-five rules at 0.000%, which is most of a screen of nothing.

**4. The counterfactual warning.** A 46% share became a 275× speedup, because that rule was creating
work for every other rule as well as doing its own. Shares say where time went, not what removing
something would save, and the report has never said so.

**5. `op(id, times = n)` for batch labels.** The floor work says an operation under ~50 ns should
not be labelled individually — label the enclosing loop and divide. Today that leaves the report
speaking in loop-executions; this makes it speak in the user's units.

**Not here:** annotations, the bytecode agent, published artifacts, a frozen public API. Those stay
in phase 7. Nothing in this phase needs to be public — it needs to be *placeable*.

**Done when:** a label can be placed on a before/after callback without the user writing a helper;
a leaked label is reported rather than silently believed; and the report reads cleanly on a
workload with forty labels of which fifteen ever fire. **All three hold.**

**What it came to.**

- `Profiler.enter(id)` / `exit()` / `depth()` / `expectBalanced()`, with a fixed 64-deep stack per
  thread. Only the non-lexical form touches it, so depth is exactly the number of labels placed by
  hand and not yet closed — a balance check rather than a call-depth counter. **The trial's fifteen
  lines are gone**: `RuleLabeller` now calls `Profiler.enter`/`exit`, the plan-boundary check is
  `Profiler.expectBalanced()`, and the Calcite numbers are unchanged (`EnumerableMergeJoinRule`
  46.08% against 46.18% before).
- The report counts leaks two ways: labels found open where the caller said the thread was
  quiescent, and threads still inside one when sampling stops. **Measured in the demo**: a leak
  every thousandth pass on one of four threads inflated that operation's share by 3.4 pp against a
  construction of 40.7%, while the operation next to it landed on 40.4%. The share does not look
  wrong. That is the whole problem with a leaked label, and now there is a number beside it.
- Zero-sample operations are folded with a count, and the ones that *ran* and were still never
  sampled are named — those are a finding, not clutter. On Calcite: *22 operations were never
  sampled and are folded away; 1 of them did run: rule:EnumerableLimitRule.*
- The counterfactual warning closes every report.
- `op(id, times = n)` reports in the caller's units for a label placed around a loop.

## Trial 2 — Lucene · done

The full record is [trial-lucene.md](trial-lucene.md); this is what it changed.

**It qualified for a cleaner reason than Calcite did.** Calcite's flame graph pointed the wrong way;
Lucene's does not point at all. Four `TermQuery` clauses on four different terms share every frame
they have, and a flame graph attributed three of eight clauses with 51.2% of samples naming no
clause at all. The labels separated all eight: prefix 48.49%, phrase 42.66%, and the four
same-class clauses spanning 2.080% down to 0.163%.

**Everything under test came through, and two things did not.**

Held: the lexical form came back (a wrapper body is a block we own, so `enter`/`exit` was never
needed — the opposite shape from Calcite, and the second data point that both forms are required);
the counts column separated 20.8 M calls at 2.2 µs from 495 M at 81 ns for two clauses holding
nearly the same share; the long-instance detector fired on the prefix clause and was right; the
concurrency was uneventful — eight worker threads, no imbalance, no leak, shares reproducing across
run lengths to within 0.4 points; and `strict` caught a genuinely bad placement in one second.

Did not hold:

1. **The floor check is not machine-independent.** It also stopped a *correct* placement, citing
   27.5 ns for a label whose settled figure is 49.7 ns. On this laptop, throughput falls 2.2× between
   a two-second run and a forty-second one, so implied per-call duration rises 3× with run length
   while every share stays put. The claim in `floorCheck`'s own documentation — "identical on every
   machine and every rerun" — is false here. Candidate fixes are in
   [findings.md](findings.md#open-questions); the cheapest is to require the estimate to be low in
   two consecutive windows rather than one.
2. **A misplaced label is invisible from inside the report.** Labelling the scorer but not the
   factory that builds it reported the decisive clause a third low, with every number plausible and
   nothing indicating a gap. Only an independent measurement found it. **Since fixed, in part** —
   see below.

### What the trial changed in the tool

**Absolute occupancy beside share, and coverage in thread-time.** `share` is a share of *labelled*
samples, which is the right denominator and a treacherous number to compare between runs: it
re-scales every row whenever the set of labels changes. Measured with the bad placement preserved as
a configuration and both run twice, the phrase clause appears to become fourteen points cheaper when
a *different* clause's label is fixed — while its occupancy does not move at all (38–41 s in every
run) and the clause actually fixed doubles. The column costs nothing: `hits × step`, already
collected and previously discarded.

That makes the *iterative* case self-diagnosing — move a label, diff the column. It does not solve
the cold start, where there is no earlier run to compare against; that needs bracketing by a coarse
label (which phase 4 gives for free, and this is a better argument for phase 4 than the one below)
or a low-rate stack sample on unlabelled ticks ([ideas.md](ideas.md) item 14). Numbers in
[findings.md](findings.md#placement).

**And the closest competitor was measured.** Elasticsearch's approach — wrap every scorer in
`System.nanoTime()` — ranks the wrong clause first on this workload, because the instrument charges
per call and the two top clauses differ 21× in call count. One free parameter reconciles all eight
clauses to an RMS of 0.09 pp. It is in [case.md](case.md), and it is the strongest single argument
for sampling this project has produced.

**What did not come up:** stack depth. Lucene's deepest sample was 44 frames with zero truncation,
so the argument that beat Calcite hardest was irrelevant here.

## Trials 3+ — the rest of the list · next

Two data points now, and they disagreed usefully — Calcite said the tool needed a non-lexical form,
Lucene said the lexical one was back and that the danger had moved from *leaking* a label to
*misplacing* one. A third will do the same to whatever we believe after two.

**What makes a good target**, from what the trials actually taught us:

1. hot operations of tens of nanoseconds, executed billions of times;
2. **the domain's identity is not the stack's identity** — several logical operations behind one
   frame. This is the differentiator, and the only reason the Calcite result was interesting;
3. concurrent, so the duty cycle and the occupancy-against-CPU work is exercised on foreign code —
   Calcite planning is single-threaded and never tested any of it;
4. extension points that can be decorated without forking.

**Next: Netty**, then the compiler, then the two negative controls. Lucene is done and its record is
[trial-lucene.md](trial-lucene.md).

| candidate | why it is on the list | what it tests that the others do not |
|---|---|---|
| ~~**Lucene** search~~ · **done** | clauses share `Scorer` / `DocIdSetIterator` frames; concurrent across segments; built to be extended | placement by *wrapping*, and the first concurrent foreign workload — both delivered, plus the timed-wrapper comparison nobody planned |
| **Netty** pipeline | N handlers behind one `channelRead`; event-loop threads | a different concurrency shape entirely — few threads, many tasks, and time that is mostly *not* CPU |
| **javac or the Kotlin compiler** | many passes through the same visitor frames; the identity question is "which pass", which no stack answers | depth — compilers recurse hard, and JFR truncates at 64 frames where a slot has no depth at all |
| **JGraphT** or any graph library | closest to the shape this project came from: node expansion, edge scan, tens of nanoseconds | whether the tool says anything useful when methods *are* the operations |
| **Jackson** | hot, short, and its identity maps cleanly onto methods | the same, at the smallest operation size we have ever tried |

**The last two are negative controls and that is why they are worth doing.** A stack profiler
already answers "which method" well; if our labels merely reproduce what a flame graph says, the
tool should be seen to reproduce it rather than to invent a difference. [case.md](case.md) keeps an
honest section on where the other tools are better, and it is currently built from one workload.
A trial that ends in "async-profiler would have told you this in ten seconds" is a real result and
belongs there.

Each trial gets its own module beside [`trial/`](../trial), so nothing it depends on can reach the
profiler, which still takes no dependencies at all.

### Running one — what the first two trials say to do, in order

[trial.md](trial.md) and [trial-lucene.md](trial-lucene.md) are the two full records. What they
establish about *how to run one*:

1. **Qualify the candidate before instrumenting it.** Calcite earned the trial by taking 16–20 s to
   plan a four-table join against 184 ms for three, and by having a flame graph that was over 60%
   JDK collections and string building. A workload nobody would want profiled proves nothing. For
   Lucene that means finding a query whose cost is real and whose *clause* costs are not obvious.
2. **Find the hook the library exposes, and label the whole lifecycle of what it names.** For
   Calcite it was `RelOptListener` and nothing else was reachable without forking; for Lucene it was
   the `Query → Weight → ScorerSupplier → Scorer → DocIdSetIterator` chain. Lucene added the rule
   that costs the most to learn: **label the factory as well as the product.** Labelling only the
   scorer, and not the supplier that built it, reported the decisive clause at 32.2% instead of
   48.5% — and the report looked entirely healthy either way.
3. **Do not let the wrapper change the workload.** New with Lucene, and Calcite could not have shown
   it. Base classes supply working defaults for bulk fast paths, so a wrapper that overrides only
   the obvious methods still returns the right answers about a workload the library would never have
   run: 13.3% slower, one clause's calls 40× higher, its rank moved from seventh to third. Every
   escape hatch has to be delegated, and the check that it worked is a flame graph over the inert
   wrapper against one over the bare code.
4. **Measure the mechanism separately from the hook.** Attaching *any* listener made Calcite
   allocate two objects per rule firing; wrapping cost Lucene 3.84% before a single label was
   placed, of a 6.54% total. The comparison has to be three-way — nothing, mechanism-with-no-op,
   mechanism-with-label — or our hook is charged for somebody else's cost. Interleave it and swap
   the order every round: this machine's clock moved 2.5× mid-comparison and all configurations
   moved together, which is the only reason the number survived.
5. **Check the labels against something independent, and take the disagreement seriously.** JFR
   agreed with Calcite's labels to about a percentage point. On Lucene it disagreed, and the
   disagreement was ours — item 2 above. Where the two disagree, that is the finding, and it is not
   automatically the other tool's fault.
6. **Keep the friction list as you go.** It is the deliverable that gets dropped when the finding
   goes well, and it is what phase 3.75 was built out of.

**What is under test after two trials.** Lucene exercised `enter`/`exit` by *not needing it*, the
duty cycle and its bound on foreign concurrent code, the long-execution detector, the floor check
under `strict`, folded empty operations, and the counts column. Still never met on foreign code:
`op(id, times = n)`, the balance check on a workload that actually throws, and anything at all above
the fine tier.

## Phase 4 — the coarse tier · after the trials

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

**And this is where the graph-traversal workload belongs.** The project came from a system that
traverses a supply chain in memory, with nanosecond operations and coroutines — the exact shape this
tool was built for, and the shape no trial has covered. Written from the public form of the problem,
it is the right bench for this phase and the wrong one for any earlier phase: what it uniquely
exercises is work crossing threads and suspending, and until propagation exists there is nothing for
it to test. It is a bench, not a trial — our code, so it inherits our assumptions, which is exactly
why it cannot substitute for pointing the tool at somebody else's.

**What this unlocks:** per-operation parallelism becomes real rather than trivially 1.

## Phase 6 — thread state and the whole-application coefficient · partly done

### What is built · **done**

The fine-tier half — thread state per operation, and per-operation concurrency. Every number in
[findings.md](findings.md#thread-state-beside-the-label).

`OpSlot` carries a **weak** reference to its thread, so the sampler can ask its state without
pinning a thread that dies without releasing. Each tick the walk reads that state and, for a slot
inside a label, counts the hit as waiting when the thread is not runnable; separately it stamps the
operation as *seen this tick*, which counts ticks and not slots and so measures the operation's
wall-clock footprint. Three columns follow: **waiting**, **elapsed**, and **threads** — the last two
being `activeTicks × step` and `hits / activeTicks`.

**Verified against a second truth that shares nothing with it.** Under the contended lock at 1.60
utilisation, 52,422 of `lockedUpdate`'s 71,839 hits caught a thread that was not runnable — **52.483
s of waiting against 52.482 s** summed by the waiting threads' own `nanoTime` brackets. Every other
operation in the same run, none of which can block, read **0.00%**; and on the ordinary bench, where
preemption takes 30% of the wall time, everything reads 0.0% too, because a preempted thread is
`RUNNABLE` and this column is for waiting another thread caused.

**What it cost:** the slot walk goes from ~190 ns to ~284 ns per slot, which at eight slots is
0.75 µs of a 1 ms tick and does not move the achieved step. The effect on the workers could not be
measured — this machine's throughput varies by 29% between runs of the same configuration, which is
more than any difference between configurations. Recorded as inconclusive rather than as a number.

**The scaling limit, which is new and matters:** at the 1024-slot ceiling that same per-slot cost is
~95 µs per tick, about 10% of the step. Eight threads is free and a thousand is not — and a thousand
slots is exactly what the virtual-thread hazard produces.

### What is not built



Sample the thread's state alongside the label. From that, a histogram of how often 1, 2, 3, … N
threads were busy, and the parallelism coefficient as its mean.

The sampler snapshots every thread at one instant, which is what makes this possible — a profiler
that sums time per thread has destroyed the information before you can ask. This is the one thing
an async-profiler bridge could never provide, since it samples each thread on its own timer signal.

**This phase is worth more than it looks, and the reason came out of settling the tier boundary.**
Thread state is not one feature among several — paired with per-operation concurrency
([ideas.md](ideas.md) item 16) it closes **three** of the ways the fine tier fails at once, and both
halves come from photographs the sampler already takes:

- it answers *waiting or working* per operation, rather than as one aggregate bound over the run;
- it **detects the bimodal operation** — a label that is 50 ns on the fast path and 100 µs when it
  hits a lock reports 150 ns and describes nothing, and today nothing signals it, because a 100 µs
  slow path is a tenth of a tick and invisible to the long-instance detector too. Under sampled
  state that label shows two thirds of its samples parked, which is not a subtle signal;
- and with concurrency as the divisor it turns occupancy back into **wall time** —
  `elapsed = occupancy ÷ mean concurrency while active` — which is the one thing summed occupancy
  can never be. Worked in [profiler.md](profiler.md#turning-occupancy-back-into-wall-time), where
  the same 100 thread-seconds of waiting means *6.7 s, break up the convoy* or *59 s, design the
  contention out* depending only on the divisor.

**The limit to design around:** `RUNNABLE` means *eligible*, not *on a core*. It covers a preempted
thread — 14–18% on a bench that never blocks — and a thread in a blocking socket read, which the JVM
cannot see into. `BLOCKED`/`WAITING`/`TIMED_WAITING` are conclusive; `RUNNABLE` is not, and closing
that gap needs the per-thread duty cycle, [ideas.md](ideas.md) item 10.

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

What someone else has to touch to use this. The *placement essentials* were pulled forward into
phase 3.75, because the first trial could not use the documented API at all and because the coarse
tier needs the same machinery. What is left here is everything that hardens a surface rather than
making one exist.

Two ways of placing fine labels, because operations do not always coincide with method boundaries:

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

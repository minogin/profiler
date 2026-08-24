# Findings

What we learned building this, with the evidence. Grouped by subject rather than by date, and
appended to as things are discovered. The sequence of work lives in [plan.md](plan.md).

The rule for this file: every claim carries the measurement that produced it. A finding without a
number is a hunch, and hunches have been wrong here more than once.

Findings that are specifically about *other* tools' limits are cross-posted to [case.md](case.md),
which is the running argument for why this exists. Untested proposals live in [ideas.md](ideas.md).

---

## The JIT

**C2 folds constants through an unrolled loop, and it will eat a busy loop whole.**

The first busy loop was an LCG, `s = s * A + B`. Measured cost came out at 0.052 ns per
iteration — a fifth of a cycle, which cannot happen. C2 unrolls the loop and folds the constants:
`(s*A+B)*A+B == s*A² + (BA+B)`, so sixteen iterations collapse into one multiply.

An xor-shift is a linear map over GF(2). C2 does not compose such matrices, so there is nothing to
fold. Measured 0.83–0.89 ns per iteration, about three cycles, which is plausible for a dependent
shift-xor-shift-xor chain.

*Consequence:* a plausibility floor on the iteration cost is now a permanent check. Below 0.3 ns
per iteration the run aborts, because everything downstream would be garbage.

**A pure function's result must reach a field or the loop is dead code.** The busy loop's state
flows out through every call and eventually into a volatile sink. Removing that would let the
whole workload vanish.

**Deoptimisation is not a risk here.** One xor-shift loop, no polymorphism, nothing loaded after
startup, and the single branch in the hot loop is constant for the run. Once C2 has it, it keeps
it. C2 compiles after roughly 10⁴ invocations and the bench does tens of millions of root calls a
second, so JIT warm-up completes in well under a second — the elaborate plateau search that
originally sat here was measuring thermal drift and calling it JIT.

## Calibration

**A linear fit across the whole range misses badly at the short end.** Fitting ns-per-iteration
across 8…2048 iterations produced a *negative* intercept and made 20 ns operations 30–40% longer
than configured. The loop is unrolled, so an iteration does not cost the same at twenty iterations
as at two thousand.

*Fix:* the fit is a seed only. Each operation's iteration count is then settled by measuring at
its own working point, in proportional steps. Error dropped to under 1.5%.

**A slow clock coarsens granularity, it does not stretch operations.** At 2.37 ns per iteration a
20 ns operation gets 7 iterations instead of 24 and lands at 18.89 ns — still ~20 ns, but the
smallest step the fit can take is now 2.4 ns, so it cannot do better than ~6%. A flat 3% fit
tolerance is arithmetically unsatisfiable at that clock. *Open:* the tolerance should be derived
from the achievable quantisation rather than fixed.

**And it is not rare: the fit aborted 6 runs out of 12** in one afternoon on a machine whose clock
was healthy throughout (0.81–0.87 ns per iteration). A different operation each time — `tinyStep`
5.4%, `traverse` 9.2%, `checkpoint` 4.8%, `scoreNode` 11.9%, `rankBatch` 3.3% — and the fitted
iteration counts for the *parent* operations swung wildly between runs, `traverse` taking 25
iterations in one run and 66 in another. The guard is doing its job, but half the runs of the bench
are currently being spent on it.

## Measurement technique

**Measuring things sequentially that you intend to compare aliases drift onto the comparison.**
This has bitten three times in three different places.

1. *Batch measurement.* All seven trials of one operation, then all seven of the next — each
   operation owning its own ~28 ms window. On a hybrid CPU the scheduler moves threads between
   core types inside that window, and it landed as an 11% per-operation spread at 8 threads while
   4 and 16 threads sat under 2%. Interleaving the trials — every operation once, then round
   again — dropped it to 2.9%.
2. *Observer effect.* Three configurations run one after another, minutes apart. Reported the
   instrumented bench as **15% faster** than the clean one. Round-robin helped but not enough.
3. *Thread sweep.* Counts run in ascending order, so thermal drift is confounded with thread
   count. Noted in the output; a descending repeat would settle it.

**Subtracting two large numbers to find a small one does not work.** The hook is ~2 ns; on a
2000 ns operation that is 0.1% of the measurement while batch noise is percent-scale. Measured
across every duration the bench has, the differential is usable below roughly 200 ns and worthless
above it — it produced `serialize: −191.50 ns` and `+43.32 ns` on consecutive attempts.

*Fix:* measure the hook alone, where the signal is the whole measurement. 1.744 ns with a
per-thread spread of 1.6–2.4.

**Do not compare two different methods and call the difference a feature.** Timing `exec` against
`execLabeled` reported a *negative* hook cost. They are separate methods with separate inlining
trees; the difference includes "these compiled differently". Comparing `burn` against
`op { burn }` — same method, same loop shape — gives a usable number below 200 ns.

**The standalone cost of a hook is an upper bound on its marginal cost.** Direct measurement gives
1.7 ns; the differential inside a real operation gives ~0.85 ns, consistently, across four
operations. The hook's memory operations are independent of the busy loop's dependency chain, so
an out-of-order CPU runs them in the gaps. Both numbers are right; they answer different questions.

**Interleaving is not enough; the order has to swap too.** Comparing a plain and an instrumented
configuration round by round, always A then B, reported the instrumented one as **6.6% faster** —
consistently, in all four rounds, so not drift. Anything that depends on *position* within the
round rather than on elapsed time — a collection that always lands in the first slot, a clock that
has just come up from idle — is charged entirely to whoever goes first. Swapping the order every
round brought it to 1.021× with three rounds each way, which is the answer "below the floor".

This is the fourth time a sequential comparison has aliased something onto the effect, and the
first time round-robin alone did not fix it.

**A rate needs its numerator and its denominator to count the same window, and one of ours did
not.** Implied duration is `hits × step / calls`. The hits come from the sampling session; the call
counts came from the registry, which totals the life of the *process* — including threads that died
before sampling began, whose counts are folded into the retired totals and stay there. The bench's
JIT warm-up is exactly that: a separate set of workers that exits before the measured run.

It inflated every call count by around a third and so deflated every implied duration by the same
factor, uniformly, which is the worst way for an error to behave — it looked like a plausible
systematic bias and was published as one. What exposed it was a bound that could not be true: the
floor check accused a 20 ns operation of being under **7.9 ns**, and 7.9 was an *upper* bound.
An upper bound below the truth is arithmetically impossible, so the only possible fault was in what
the two sides were counting.

*Fix:* the sampler snapshots the call counts before its first tick and reports the difference. The
same operation now reads 25.6 ns, which is above 20 ns as an upper bound must be.

*Consequence:* the check that caught it was a bound rather than an estimate. An estimate that came
out 60% low would have been believed.

**Whole-run throughput cannot resolve a sub-1% effect on this machine.** The same three-way
comparison gave −13.84% and +11.27% on consecutive runs, with the signs of the two component
effects disagreeing within a single run. Printed as inconclusive rather than quietly kept.

## The machine

**Intel Core Ultra 7 255H: 16 physical cores, 16 logical — no hyperthreading.** It is a hybrid
design, performance cores and efficiency cores in one package. The two-speed split in per-thread
throughput (93M against 115M calls) is core *types*, not SMT siblings. Threads migrate between
them during a run, so a thread's speed is not even stable over time.

**8 threads is the worst case for measurement stability**, not 16.

| threads | scatter (before interleaving) |
|---|---|
| 1 | 1.76% |
| 2 | 4.10% |
| 4 | 1.79% |
| **8** | **11.09%** |
| 16 | 1.75% |

At 1–4 they all fit on performance cores; at 16 every core is occupied so nothing can move; at 8
the scheduler has genuine freedom to shuffle. Less load meant *more* noise, which is the opposite
of what contention would predict.

**The clock swings by 2× inside a single run, tracking load.** Traced with
`Get-Counter '\Processor Information(*)\% Processor Performance'` during a bench run: sustained
8-thread work at 2.0–2.9 GHz, and whenever load went light all sixteen cores jumped back to
4.3–4.9 GHz. Intel's turbo budget — bursty work never exhausts it, sustained work drains it in
seconds.

*Consequence:* the bench oscillates between 1-thread and 8-thread phases several times per run
(calibration and fitting are single-threaded while workers park), and each transition changes the
clock. "Price of parallelism" is substantially that gap rather than a property of parallelism.
The clock is now probed per phase, including during the run.

**Heat soak is real and it accumulates across runs.** After an hour of load the same 8-thread
configuration that sustained 23M calls/s decayed to 12M within three seconds, and the busy loop
went from 0.83 to 2.37 ns per iteration. The guards caught it and refused to produce numbers.
Letting the machine idle restored it.

**The probe does not keep the CPU warm.** Tested on the theory that repeatedly running
`Get-Counter` holds cores at a high P-state: 25 samples at 1 s intervals on an idle machine
bounced between 87% and 128% of nominal with no upward trend. First five averaged 103.8%, last
five 92.6%. Might be machine-specific; it does not reproduce here.

## The hook

**A volatile store on x86 is not a store.** It needs a StoreLoad barrier — a lock-prefixed
instruction costing tens of cycles — and the hook does two per call. Measured at 16% of the
bench's throughput, against a design that assumed single-digit nanoseconds.

**Opaque is the right strength.** It forbids the JIT from eliminating or reordering the access,
which a plain field would not — dead-store elimination would drop the entry write once the body
inlines, silently blinding the profiler — while emitting no fence. Throughput cost fell from 16%
to below the measurement floor.

**Native code would buy nothing.** An opaque store compiles to an ordinary `MOV`, which is exactly
what a relaxed store in C or Rust produces. The barrier was the whole cost and it is a hardware
cost. Meanwhile the boundary would *add*: a JNI downcall is 15–30 ns, a Panama critical downcall
5–10 ns, against a whole hook of 1.7 ns. The only thing native would genuinely do better is the
thread-local (`__thread` under initial-exec is one instruction against a small hash probe), and
that is a fraction of the boundary cost you would pay to reach it.

**Counters are nearly free and are not optional.** The expensive part of the hook is finding the
per-thread data, and by the time you count you already hold it. A share alone cannot distinguish
200M calls at 8 ns from 1000 calls at 1.6 ms, and those want opposite fixes. Placed *after* the
label is set, so the cost lands on the operation rather than its caller.

**The counter's own distortion is exactly correctable**, unlike the attribution bias — its cost is
`calls × counterCost` and the counter measures precisely the quantity the correction needs.

**Do not label anything comparable to the hook.** At 1.7 ns per hook, labelling a 1 ns operation
means the instrument costs more than the thing it measures. The practical floor is a few tens of
nanoseconds; below that, label the enclosing loop and divide.

**Measured, the floor is between 45 and 70 ns, and it is accuracy rather than cost that sets it.**
Each leaf's sampled share against the configured truth, one 20 s run at 8 threads, sorted by
duration. Parents are left out because they carry the opposite bias — they read *high*, absorbing
their children's hook entry cost — and the question here is about size, not about nesting:

| leaf | built to be | error | its noise floor |
|---|---|---|---|
| tinyStep | 20 ns | **−8.44%** | 1.46% |
| nodeLookup | 20 ns | **−5.83%** | 1.67% |
| hashProbe | 25 ns | **−9.10%** | 1.70% |
| edgeScan | 45 ns | **−4.45%** | 1.42% |
| degreeCheck | 70 ns | −0.39% | 0.97% |
| markVisited | 110 ns | +2.08% | 0.88% |
| pushFrontier | 170 ns | −0.32% | 0.72% |
| popFrontier | 260 ns | +0.22% | 0.58% |
| filterNode | 400 ns | −1.27% | 0.87% |
| scoreNode | 620 ns | +1.11% | 0.83% |
| compact | 950 ns | +0.53% | 1.60% |
| rehash | 1.4 µs | +1.63% | 1.31% |
| serialize | 2 µs | +2.37% | 1.79% |

Everything from 70 ns upward is within ±2.4% and most of it is inside its own noise floor. The four
leaves at 45 ns and below read 4.5–9.1% *low* against noise floors of 1.4–1.7%, so three to six
times noise, all in the same direction. The break is between 45 and 70 ns, which is where the floor
of 50 ns comes from.

**The hook's cost is not what sets it.** At 20 ns the hook is 8.5% of the operation and is
correctable in principle, since the call count measures exactly the quantity a correction needs.
What is not correctable is the bias above, and beneath that sits the hazard that C2 will shuffle
work across the boundaries of adjacent short labels altogether — the demo lost 95% of one 12 ns
operation that way, with nothing in the numbers to show for it.

The 20 ns operations in the bench are there precisely because they sit below the floor: a bench
should work the instrument at the point where it fails.

*Correction:* this conclusion was first drawn from a different measurement — implied duration
against configured duration — which was wrong twice over. It divided by call counts that included
the warm-up (see below), and even fixed it cannot resolve a bias this size, because it compares
each operation against the *median* load factor while the bench already tolerates 6% of legitimate
per-operation scatter around that median. The share comparison above uses no call counts and no
median, and is the measurement that belongs here.

**Opaque labels do not fence the work between them, and the JIT will move it.** The API demo
originally wrote its three operations with literal trip counts — `burn(s, 40)`, `burn(s, 120)`,
`burn(s, 15)` — all inlined into one loop body over a single dependency chain. Measured shares came
out **19.4% / 78.4% / 0.46%** against **22.9% / 68.6% / 8.7%** by construction: the shortest
operation lost 95% of itself.

Opaque access guarantees the label writes are not eliminated, duplicated, or reordered against
*each other*. It creates no ordering with anything else. With constant trip counts the JIT unrolls
all three loops, interleaves them, and the boundaries the labels claim are not the boundaries the
CPU executes. Reading the counts from an array instead — so the loops cannot be fully unrolled —
gave 19.4% / 71.8% / **8.80%**, with the shortest operation landing on its 8.6% expectation.

The bench never showed this because its trip counts come from `iters[id]`. Real code rarely looks
like the broken version either, but the limit is real: **a label is only a boundary if the compiler
cannot see through it**, and adjacent tiny operations with compile-time-constant work are exactly
where it can. Worth knowing before someone labels three consecutive constant-size loops and
believes the answer.

## Against a stack profiler

All from the Calcite trial — the numbers and the workload are in [trial.md](trial.md).

**Two sampling mechanisms with nothing in common agree to about a percentage point.** Our shares
against JFR's inclusive shares, same run, per rule: gaps of 1.0, 0.8, 1.1, 0.6, 0.1 and 0.2 pp.
Phase 3 checked the sampler against a bench we wrote; this is the first check against code we did
not, and it is the stronger of the two.

**A shared base-class method does not merely hide the identity — it inverts the ranking.** Twenty
Calcite rules inherit `ConverterRule.onMatch`, which calls the subclass's `convert` and then does
the expensive part itself. So the subclass frame encloses the cheap half of the firing:
`EnumerableMergeJoinRule.convert` is visible in 0.81% of samples against a label share of 46.18%,
a factor of 57, while `JoinCommuteRule` — which overrides `onMatch` — reads 30.16% in the stacks
against 30.21% in the labels. The same recording is accurate for one rule and wrong by 57× for
another, and nothing in it says which is which, because the difference is a base class's internal
structure. A reader with only the flame graph ranks the wrong rule first. Measured on a 29,655
sample recording with truncation eliminated, so this is structural and not a sampling artifact.

**A stack profiler has a depth limit and a label does not.** JFR truncates at 64 frames by default,
and when it truncates it is the *root* end that is lost — so a method that was merely on the way in
loses the sample entirely. Measured: 2.4% of stacks truncated, dominated by one subsystem, and
raising the limit to 2048 moved that rule's share up by 3.7 pp. A label in a thread-local slot is
one int; recursion depth cannot reach it.

**A profiler that will not say what rate it achieved should not be believed about anything else.**
JFR asked for 1 ms and delivered 6.3 ms in one run and 13.7 ms in another — a factor of 6 to 13,
unreported. Over the same 40 s window that was 2,669 samples against our 39,360. Our sampler
prints the achieved step beside the requested one, which was originally there to catch the parking
problem and turns out to matter for a different reason: it is the only way a reader knows how much
evidence is behind a share.

**A share is not a counterfactual, and the gap can be two orders of magnitude.** An operation
measured at 46% of planning time turned out to be worth a **275×** speedup when removed, because
it was creating work for every other operation as well as doing its own. Nothing in the report says
this, and a reader who treats shares as "what I would save" will be wrong in the safe direction
sometimes and the unsafe direction other times. It needs to be said in the output.

**Placing a label in code you do not own means finding the one hook it exposes.** For Calcite that
was a listener notified before and after each rule firing — enough, because the rule is the unit
anyone would act on. Everything else in the hot path was unreachable without forking. The honest
scope of the fine tier on a third-party library is the domain concepts that library exposes a hook
for, and no more.

**A non-lexical label leaks in the contaminating direction.** The hook Calcite offers is two
callbacks, and the "after" one is not inside a `finally` — so a body that throws leaves the label
set and every later sample is billed to it. No error, no warning, a plausible wrong number. The
trial checked the span stack was balanced after every one of 484 iterations rather than assuming
it. Any enter/exit API needs that check available to its users.

**The placement mechanism has a cost of its own.** Attaching *any* listener made Calcite allocate
two event objects per rule firing whether the listener did anything or not. Measuring labels
against no-labels would have charged our hook for somebody else's allocation; the comparison has
to be three-way — nothing, mechanism-with-no-op, mechanism-with-label.

**Where the hook cost lands relative to what it measures decides whether it matters at all.** On
the bench, labels sit on 20 ns operations and the hook is 2% of them. On Calcite the same hook sits
on boundaries costing hundreds of microseconds and is five parts per million — unmeasurable, and
both A/B comparisons came out with the wrong sign. "Do not label anything comparable to the hook"
has a happy converse: on coarse enough boundaries the instrument is free.

## The sampler

**Parking cannot hold a millisecond step under load.** Measured at a 1 ms request with 8 workers
on 16 cores:

| strategy | achieved | resyncs |
|---|---|---|
| `parkNanos` | 1.62 ms | 2534 of 6177 |
| `Thread.sleep(1)` | 1.81 ms | 2767 |
| **spin** | **1.001 ms** | **1** |

With all 16 cores loaded, parking degraded to 13.5 ms — the sampler simply could not get
scheduled. The initial diagnosis of Windows' 15.6 ms timer granularity was wrong; freeing half
the cores took parking from 13.5 ms to 1.62 ms, so the dominant problem was CPU contention.
`Thread.sleep(1)` was tried on the theory that HotSpot asks Windows for a finer timer; it made no
difference.

**Parking also bunches.** After falling behind it fires several ticks in quick succession —
minimum observed interval 1 µs. Bunched samples are correlated samples, and more of them do not
help.

**Fixed-interval sampling can alias.** If the workload has a rhythm near the sampling period, every
sample catches the same phase — the wagon-wheel effect, where more samples cannot help. The
interval is jittered ±25%, symmetric so the mean is unchanged. Our bench has no such rhythm; a
real application might (GC cycles, timer-driven work).

**A slot registry that only grows is a leak, and a lying one.** Slots left behind by dead threads
read empty forever. In starvation mode that put the idle share at 90% where 80% was correct. Slots
are released on thread exit, and the sampler output now checks slot count against live worker
count.

## The duty cycle

How much of the sampled occupancy was CPU — phase 3.5's bound on every share at once.

**A thread that never blocks is off the CPU 14–18% of the time on this machine.** This was
supposed to be the null test: the bench allocates nothing, waits for nothing and blocks on
nothing, so the duty cycle had to read ~100% and anything else was a broken implementation. It
read **78%**, and the implementation is right. Eight workers and a spinning sampler on 16 logical
cores lose that much wall time to the scheduler, in preemptions of milliseconds — worst single
preemption 31.6 ms in an 8-worker run and 75.0 ms in a 4-worker one, against a 15.6 ms quantum.

*Consequence:* "never blocks" is a property of the code and being on a CPU is not, so the ground
truth for the null test cannot come from the configuration. It has to be measured.

**Two mechanisms with nothing in common agree to half a percentage point.** The second reading is
the workers' own: the run loop already reads `nanoTime` once per 256 root calls to check its
deadline, and a gap between two of those readings longer than 0.5 ms is the thread having been
taken off the CPU rather than being slow. It is measured by the victim and owes nothing to the
operating system's accounting.

| configuration | `getThreadCpuTime` | the threads' own gaps | gap |
|---|---|---|---|
| 8 spinning threads + spinner (standalone probe) | 86.19% | 86.13% | 0.06 pp |
| bench, 8 workers | 83.91% | 84.45% | 0.54 pp |
| bench, 8 workers | 81.55% | 82.08% | 0.53 pp |
| bench, 4 workers | 90.63% | 91.39% | 0.76 pp |
| bench, starvation 3 of 15 | 19.67% | 19.84% | 0.17 pp |
| bench, starvation 3 of 15 | 18.83% | 19.19% | 0.36 pp |

The OS reads lower every time, which is the expected direction: the workers cannot see a
preemption shorter than half a millisecond, so their figure is a lower bound on stalling. The
tolerance is set at 1.5 pp from these six, a little over double the worst of them.

**`getThreadCpuTime` on Windows is usable at a one-second window, and the resolution is 15.625 ms
measured rather than assumed.** The plan carried this as a caveat to be checked: on Windows the
value comes from `GetThreadTimes`, updated on scheduler ticks. Probed by spinning and watching for
the counter to move, the smallest step is 15.625 ms — 1.6% of the window, and the quantisation
telescopes, since each window's delta is the difference of two readings of one cumulative counter
and a rounding error at a boundary enters one window positive and the next negative. A single
window can even read **100.29%**, which is that error made visible. The aggregate does not.

**The descheduling is load-dependent, and the numbers are large.** One spinning thread on 16 cores
reads 98.81%; eight read 95.57%; eight plus a spinning sampler read 90.08%; the bench with its
sampler and main thread reads 81–84%. In starvation mode, where only 3 of 15 threads work, the
working threads lose 0.8–4%. So the machine's willingness to keep a runnable thread on a core
falls away long before the cores run out — which is the honest form of the "threads ≤ cores"
assumption this phase set out to retire.

**Reading every thread's CPU time costs up to 214.7 µs and does not disturb the sampler.** It runs
on the sampling thread once a second. Achieved step stayed 1.001 ms with zero resyncs; the worst
step observed was 1.445 ms against a 1.25 ms jitter ceiling, so the walk lands inside a single tick
and delays it by a fraction of a step, roughly one tick in a thousand.

**The bound is pessimistic when threads sit outside any operation.** The duty cycle covers every
registered thread while the shares cover labelled samples only, so a parked thread lowers the duty
cycle without appearing in the shares at all. Starvation mode is the extreme: 18.83% duty and a
formally unbounded error, while the three working threads were on CPU 96% of the time and their
shares are fine. The report says so rather than pretending otherwise. Tightening it needs the duty
cycle per thread, paired with that thread's labelling — see [ideas.md](ideas.md).

## The long-instance detector

Whether an operation labelled as fine actually is. The test is two words per slot per tick: the
same operation as last tick *and* an unmoved entry counter means nobody entered in between, so this
is one execution still running a tick later — four orders of magnitude past what a 20 ns label
claims. Nothing is added to entry or exit; the counter already exists for the calls column.

**Implied duration reproduces the configuration across a hundredfold range.** `hits × step / calls`
against what each operation was built to be, on a quiet machine at 8 threads:

| operation | configured | implied | ratio |
|---|---|---|---|
| tinyStep | 20 ns | 20.4 ns | 1.02× |
| hashProbe | 25 ns | 25.3 ns | 1.01× |
| markVisited | 110 ns | 124.9 ns | 1.14× |
| popFrontier | 260 ns | 289.7 ns | 1.11× |
| rehash | 1.4 µs | 1.6 µs | 1.13× |
| serialize | 2.0 µs | 2.3 µs | 1.14× |

The load factor that run was 1.189, so the ratios should sit there and mostly do. Where they fall
short it is the *known* attribution bias and not a new defect: `tinyStep` at 1.02× against a 1.19×
load is −14%, and phase 3 measured that same operation at −15.5%. Two different routes to the same
number, which is the useful kind of agreement.

In the API demo the reproduction is exact: `validateRecord` runs 120 busy-loop iterations at
0.83 ns each and the implied duration is 102.0 ns.

**The detector's floor is the operating system, and it lands on every operation equally.** That is
the property the whole design depends on, and it holds. At 15 threads on 16 cores:

| | reading |
|---|---|
| duty cycle: occupancy that was not CPU | 18.04% |
| the workers' own preemption gaps (floor 0.5 ms) | 17.11% |
| executions that outlived a tick (floor 1 ms) | 15.52% |

Three mechanisms with nothing in common, and in the predicted order — the detector reads lowest
because its floor is a whole tick, so it cannot see the shorter preemptions the other two catch.

Per operation, that 15.52% is spread almost uniformly: **13.27% to 17.90% across all twenty**, worst
only **1.15× the run-wide rate**, over a hundredfold range of operation durations. Preemption is
charged to whatever was executing, in proportion to its occupancy, so it raises everything
together — which is exactly why an operation has to be judged against the run-wide rate and not
against zero. On a quiet machine the same bench gives a run-wide rate of 0.04% and a worst
operation of 0.18%.

Nothing was flagged in either regime, which is the right answer: this bench has nothing that can
block.

**A ratio against a small baseline is not evidence, and the first version of the check said so the
hard way.** On the quiet run the run-wide rate is 0.04%, so `serialize` — with *one* long execution
in two thousand samples — came out at 3.85× the baseline and was duly accused of blocking. A rule
that only compares against the baseline will therefore accuse something in almost every quiet run.
It takes three conditions together: a rate well above the run-wide one, a floor on the rate itself,
and enough long executions behind it that a single GC pause or preemption cannot be the whole
story. The thresholds are provisional and are written in one place.

### The detector against Calcite

Four-table chain join with join associate, labels on the rule instance, 2 minutes of planning: 18
plans at 6.78 s each, 118,887 samples on one thread at 1.026 ms, span stack balanced after every
plan. Shares reproduce the original trial — `EnumerableMergeJoinRule` 46.67% against 46.18%,
`JoinCommuteRule` 29.14% against 30.21% — so nothing in this work has disturbed the sampler.

**The negative control is clean and the positive control is unmistakable.** Ordered by implied
duration per firing, in one run, in code we did not write:

| operation | implied per call | occupancy in executions over a tick |
|---|---|---|
| rule:EnumerableLimitRule | 105.7 ns | **0.00%** |
| rule:JoinPushExpressionsRule | 1.9 µs | **0.00%** |
| rule:ProjectMergeRule | 3.4 µs | **0.00%** |
| rule:EnumerableJoinRule | 6.6 µs | 1.32% |
| rule:JoinCommuteRule | 142.6 µs | 38.83% |
| rule:FilterIntoJoinRule | 784.5 µs | 59.38% |
| rule:EnumerableMergeJoinRule | 228.4 µs | **74.82%** |
| phase:optimise | 144.84 ms | 5.71% |

Nothing had to be assumed about these rules for the test to mean something: the sub-10 µs ones are
silent and the sub-millisecond ones are loud, across four orders of magnitude, on a signal whose
floor is one tick.

`EnumerableMergeJoinRule` is the shape the coarse tier exists for: a **mean** of 228 µs per firing
while three quarters of its time sits in firings that outlived a millisecond. A mean over a
distribution that skewed is not a description of anything.

**GC was not the false-positive source it was expected to be.** 119 young pauses, 3.359 s in total,
mean 28.2 ms — **2.6% of wall time**, against the duty cycle's 3.01% of occupancy that was not CPU
in the same run. Two independent instruments, one from `-Xlog:gc` and one from `getThreadCpuTime`,
landing half a percentage point apart, and the remainder is ordinary preemption. So a
heavily-allocating real workload does not drown the detector, and the pauses it does cause are
accounted for by the duty cycle.

**But the decision rule failed, and this is the finding that matters.** Not one operation was
flagged — in a workload where nearly every label is coarse. The rule compared each operation
against the *run-wide* rate of long executions, and that rate was **53.52%**, so three times it is
unreachable and nothing can ever be named.

The assumption underneath was that long executions are the exception and the run-wide rate is
therefore a floor of noise. On real code the exception can be the majority: here it is more than
half of all occupancy, because more than half of the labels genuinely are coarse operations.
*A baseline built from the operations under test cannot detect a defect that most of them share.*

**The floor should come from the duty cycle instead**, which measures what the *machine* did to
everything rather than what the operations did to themselves. Checked against all three data sets
we now have:

| run | occupancy that was not CPU | worst operation's long-execution rate | wanted |
|---|---|---|---|
| bench, quiet | 1.03% | 0.18% | silent |
| bench, 15 threads | 18.04% | 17.90% | silent |
| Calcite | 3.01% | 74.82% | **named** |

One number separates all three, and it is a number that was measured for a different purpose. The
machine's contribution to stalling is bounded by the duty cycle; anything an operation shows above
that is its own.

**Changed, and re-run.** Against the same Calcite configuration, with a 3.78% machine floor:

```
! rule:EnumerableMergeJoinRule: 2,488 executions lasted over a tick (87.9% of its occupancy, 527.3 us per call)
! rule:FilterIntoJoinRule:        950 executions lasted over a tick (80.1%, 1.79 ms per call)
! rule:JoinCommuteRule:         2,775 executions lasted over a tick (63.4%, 304.9 us per call)
! rule:ProjectRemoveRule:          48 executions lasted over a tick (50.8%, 1.05 ms per call)
! rule:JoinAssociateRule:          58 executions lasted over a tick (17.3%, 17.2 us per call)
```

Five named out of forty-odd labels, and they are the five the original trial spent a day
identifying by hand. The bench stays silent in both regimes with the same rule — at 15 threads its
floor is 17.5% and its worst operation 6.35%, or 0.36× the floor.

**A parent whose children are labelled is nearly invisible to this test.** `phase:optimise` runs
144.84 ms per call and reads **5.71%**, well under the floor. The reason is mechanical: a sample
only counts as stuck if the *previous* sample found the same slot holding the same operation, and
while a rule is firing the slot holds the rule, not the phase. Two consecutive samples rarely both
catch the parent directly.

The implied-duration column catches exactly that case — 144.84 ms per call is unmistakable — so the
two columns are complementary rather than redundant: implied duration finds long parents, the
detector finds long leaves. Worth knowing that neither alone is sufficient.

## Statistics

**Percentage points cannot separate noise from bias.** Divergence falls as `1/√N` whether the
method is sound or not. Dividing each gap by its own standard error — `√(p(1-p)/N)` — does
separate them: unbiased and the RMS stays near 1 at every sample count, biased and it grows with N
because the error bar shrinks while the bias does not.

Measured across a 20× range of samples the RMS grew **5.8×** against 4.5× for pure bias. That is
an unambiguous answer that raw percentage points, flat at ~1 pp across all four cells, could not
have given.

**Report the noise floor beside the error.** `1/√hits` says how wrong chance alone would make a
number. `checkpoint` at +8.3% looks bad until you see its floor is 4.5% — 495 hits. `tinyStep` at
−15.5% against a 0.88% floor is seventeen times noise.

**Set tolerances from measurement, and record the measurement next to them.** The original scatter
tolerance was a guessed 12% and let a real 11% defect pass three times. Measured, it became 6%.
The same mistake was nearly repeated by picking a 0.5 pp gate for phase 3 by analogy; the gate is
now the ranking of the operations that carry the time, which is what the answer is actually for.

## Open questions

**The attribution bias is only partly explained.** Parents read high and short leaves read low,
consistently and reproducibly — `frontierStep` +6.2%, `tinyStep` −15.5%. The mechanism is that a
child's hook entry runs before the slot is overwritten, so the entry cost is billed to the caller.
But the arithmetic does not close: the bias implies ~2.8 ns while the whole hook is 0.85 ns
marginal, and `expandNode` (three children, 35 ns self) should be the worst offender at +1.3%
while `visitNeighbor` (two children, 30 ns) comes out negative. Direction solid, magnitude not.

**Correcting it needs a model we do not have.** With call counts the correction is one line —
subtract `entryCost × calls` and give it to the parent. Without a mechanism that accounts for the
whole effect, subtracting the modelled part leaves us confident and wrong. There is also a
model-free route: measure at two hook costs (adding a known delay before the label) and
extrapolate to zero, with a third point validating linearity.

**The bench distributes no work.** Every thread independently runs the same schedule flat out, so
nothing ever crosses a thread boundary and occupancy never varies. Two later phases need more:
cross-thread coarse operations need fork and join, and the interesting occupancy shapes — level
barriers, stragglers, lock convoys — need parallelism that changes over time. Building work
distribution once serves both. Note that occupancy is emergent rather than configured, so its
ground truth has to be *recorded* (per-phase timestamps) rather than computed, which is a
departure from how every truth in this project has worked so far.

**The fit tolerance should scale with achievable quantisation** rather than being a fixed 3%.

**Our share and JFR's differ by 12 pp on one operation and the reason is not fully known.** Same
JVM, same 40 s, eleven times the noise floor, and every other operation agrees within 2.5 pp. Stack
truncation accounts for 3.7 pp of it, measured. A leaked label was ruled out by checking the span
stack after every iteration. The remaining suspicion is sampling bias — C2 strips safepoint polls
from counted loops and the subsystem in question is full of them — but that is a hunch with no
number behind it, which by the rule of this file makes it an open question rather than a finding.

**Can the profiler be left on permanently?** At ~2 ns per hook it may well be cheap enough that
there is no reason to switch it off, which would be a much better story than a build flag. That
should be decided with a measurement on a realistic workload, not by assertion.

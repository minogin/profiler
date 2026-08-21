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

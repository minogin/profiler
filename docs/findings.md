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

**The same label is below the floor or above it depending on how many threads are running.** Two
20-second runs of the same binary on the same laptop, differing only in `--threads`:

| operation | configured | implied at 1 thread | implied at 8 threads | flagged below the 50 ns floor |
|---|---|---|---|---|
| `tinyStep` | 20 ns | **17.8 ns** (0.89×) | **55.4 ns** (2.77×) | at 1 thread only |
| `nodeLookup` | 20 ns | 16.9 ns (0.85×) | 53.6 ns (2.68×) | at 1 thread only |
| `hashProbe` | 25 ns | 26.2 ns (1.05×) | 69.1 ns (2.77×) | at 1 thread only |
| `visitNeighbor` | 30 ns | 33.5 ns (1.12×) | 87.6 ns (2.92×) | at 1 thread only |
| `expandNode` | 35 ns | 38.5 ns (1.10×) | 111.7 ns (3.19×) | **never** |

At one thread the check names four operations and the run finishes. At eight it names none — not
because the labels moved, but because eight busy threads make every operation on this machine 2.7×
slower than its single-threaded fit, which lifts a 20 ns operation clean over a 50 ns floor.

This is what demoted the check from fatal to a warning (plan.md § 1a). The rung was justified on the
premise that a below-floor label is *deterministic* — "20 ns on a loaded machine, a quiet one, a
different machine, and every rerun". It is not deterministic across two runs of the same binary on
one laptop half an hour apart.

`expandNode` clears it in both, by 0.4 ns: its upper bound is 42.0 ns and the check inflates by
[FLOOR_BIAS_ALLOWANCE] 1.2 before accusing, giving 50.4 against a 50 ns floor. Five operations are
configured under the floor and four are named — the borderline one is let go, which is the
conservative direction for a check whose false positive stops a run.

**An A/B harness that tears itself down between arms is the dominant source of variance, not the
machine.** Four attempts at the same three-way comparison on the Netty trial, each fixing one thing:

| harness | result |
|---|---|
| rebuild server and client per arm, raw means | inconclusive — 72–88% within-arm spread against an 8.66% effect |
| …normalised by each round's own mean | **+21%: instrumentation apparently made it faster** |
| …plus awaiting `shutdownGracefully`, which has a **2 s default quiet period** | sign sane, ordering monotonic, still inconclusive at −10.16% ± 10.42% |
| **rebuild nothing** — one server, one client, one set of connections, a volatile flag the only thing moving | **−3.97% ± 1.83%, readable** |

The impossible +21% is what made the second harness findable: seven event-loop threads from the
previous arm were still winding down inside the next arm's measured window. A plausible-looking 5%
would have been believed.

Within-arm spread is still 60–65% in the working version — the machine did not improve — so the
lesson is not about this laptop. **The teardown was worth an order of magnitude more variance than
the thing being measured.** The price of removing it is a volatile read the JIT cannot fold, present
in every arm and therefore cancelling: a known constant bias traded for a readable answer.

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

**Throttling makes implied per-call duration depend on how long you profile for. Share does not.**
The clearest single measurement of the clock's effect on a *report*, from the Lucene trial — same
process, same query, only the run length varied:

| run length | searches/s | `term#2` implied/call | `term#2` share | `phrase` implied/call | `phrase` share |
|---|---|---|---|---|---|
| 2 s | 234.5 | 18.1 ns | 1.692% | 42.9 ns | 40.182% |
| 5 s | 151.0 | 31.2 ns | 1.940% | 68.0 ns | 42.300% |
| 10 s | 128.6 | 41.0 ns | 1.894% | 77.1 ns | 42.330% |
| 20 s | 114.9 | 54.1 ns | 2.070% | 88.3 ns | 42.350% |
| 40 s | 108.8 | — | — | — | — |

Throughput falls **2.2×** between a two-second run and a forty-second one. Every implied per-call
duration rises roughly in proportion; **every share moves by under 0.4 points.** So the two columns
are worth different things: a share is a statement about the program, and an implied duration is a
statement about the program *on this machine, at the clock it happened to be running at*.

*Consequence, and it is a defect:* the floor check fires on whichever value it sees first. On the
correctly-placed Lucene labels it stopped the session at 998 ticks citing `clause:term#2` at "under
27.5 ns", where the settled twenty-second figure for that label is 49.7 ns — above the floor. Its
justification for firing early ("a label below the floor is a property of the placement, identical
on every machine and every rerun") allows for statistical noise in the sample count and not for the
machine halving its clock between second one and second twenty. See the open questions.

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

**Placement by wrapping costs 2–4× the bare hook, because of the indirection in front of it.**
Measured on Lucene, where each label sits inside a wrapper method that the JIT cannot devirtualise:
roughly 267,000 labelled calls per search, and the label's share of the slowdown works out at
**3–6 ns per labelled call** against 1.7 ns for the hook measured alone on the bench. The range is
wide because it comes from a difference of two noisy configurations (wrapped-and-labelled minus
wrapped-and-inert: +2.7 points by mean, +5.0 by median). This is the honest number to quote when
the label cannot be placed inline — and it is a property of the placement mechanism, not of the
hook, which is why it had to be measured against the inert wrapper rather than against bare code.

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

From the two trials — Calcite in [trial.md](trial.md), Lucene in [trial-lucene.md](trial-lucene.md).
Entries say which.

**Many instances behind one class is the shape a flame graph cannot address even in principle.**
Calcite's gap was many classes behind one inherited method, which is at least *recoverable* from the
stacks if wrongly. Lucene's is four `TermQuery` clauses on four different terms: one `TermScorer`,
one `ImpactsDISI.advance`, no frame anywhere that differs. Measured on an eight-clause query, by
counting every collapsed stack attributable to exactly one clause: a flame graph identifies **three
of eight clauses** covering 48.8% of samples, and **51.2% of samples contain no clause frame at
all**. Our labels separate all eight, and the four that share a class span 2.080% down to 0.163% —
a 13× spread inside a group the stacks cannot subdivide.

**Counts separate two opposite problems that share a percentage.** Lucene's prefix clause holds
48.5% and its phrase clause 42.7%, and they are nothing alike: 20.8 M calls at 2.2 µs against
495 M calls at 81 ns. A 24× difference in call count one way and a 27× difference in unit cost the
other. The fix for one is to stop expanding it; the fix for the other is to stop calling it. Second
independent workload on which the counts column was the most valuable one.

**The timed-wrapper approach ranks the wrong operation first, and it is what production search
engines ship.** Elasticsearch profiles queries by wrapping every scorer in `System.nanoTime()`.
Run against the same eight clauses through the same wrappers as our labels, it reports phrase 48.85%
ahead of prefix 40.70%; sampling reports prefix 48.49% ahead of phrase 42.66%. Six clauses agree to
within a point and the top two are swapped. **One free parameter — a fixed cost per instrumented
call — reconciles all eight to an RMS of 0.09 pp** (fitted 21.50 ns/call; largest residual 0.18 pp).
The instrument accounts for 17.3% of the total it reports, concentrated on the clause making 21× as
many calls as its rival, which is exactly the clause it promotes. Cost: +35.4% throughput, against
+6.5% for the wrapper plus our label.

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

**A stack profiler's depth limit is a real failure mode that does not always apply.** Calcite
truncated 2.4% of stacks and raising the limit moved a rule's share by 3.7 pp. Lucene's maximum
stack depth over 10,329 samples was **44, with zero truncation**. Recorded because a list of
advantages is only honest if it includes the ones that did not come up.

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

## Walking a stack

Why this is measured at all: the Lucene trial showed that a label in the wrong place is invisible
from inside the report, and the only thing that can say *where* unlabelled time went is a stack. The
idea is to walk one **only on ticks that found the slot empty, and a hundred times less often than
the label walk** — enough to tell "the gap is one place" from "the gap is everywhere", never on the
hot path. It was blocked on a number. Reproduce with `--stackcost`; the numbers below are from the
bench and from the Lucene trial's `--stacks`.

**There are two costs and only one of them is easy to measure.** `Thread.getStackTrace` on another
thread is a handshake: the target has to reach a safepoint before it can be walked. A microbenchmark
of the *caller* measures a cost the workers actually pay and reports it as free. Both are measured
here, separately.

**Caller cost is a fixed handshake plus a per-frame charge.** Bench, 8 victims at a controlled depth,
2,000 timed walks each:

| frames reached | median | mean | p99 | max |
|---|---|---|---|---|
| 19 | 6.8 µs | 7.6 µs | 19.4 µs | 259.6 µs |
| 67 | 12.4 µs | 13.1 µs | 23.9 µs | 1,394.9 µs |
| 259 | 40.3 µs | 38.5 µs | 63.0 µs | 974.2 µs |
| 1,024 | 119.5 µs | 101.5 µs | 237.1 µs | 1,147.1 µs |

That is **≈5 µs fixed plus ≈0.11 µs per frame**, consistent across a 54× range of depth. The tail is
three orders of magnitude above the median, which is what a handshake with an unlucky thread looks
like.

**The victim's cost only becomes measurable at about 75,000 stacks per second.** On the Lucene
workload — real threads, memory-mapped I/O, traces of median depth 18 and maximum 40 — with the
prober toggling on and off *inside a single run*, ABBA rather than alternating:

| stacks/s | search time with | without | difference |
|---|---|---|---|
| 10 | 4.336 ms | 4.379 ms | −0.98% |
| 100 | 6.339 ms | 6.434 ms | −1.48% |
| 10,000 | 6.824 ms | 6.698 ms | +1.89% |
| ~76,000 | 5.659 ms | 5.187 ms | **+9.10%** |

The first three straddle zero and are the floor; the last is real and reproduced (+8.10% in an
earlier run at the same rate). Extrapolating linearly from it: **0.9% at 10,000/s, 0.09% at 1,000/s,
0.001% at 10/s.** The proposed rate has about three orders of magnitude of headroom before the cost
is even measurable.

The bench agrees. Its control moves 4.28% between rounds on its own, so 10, 100 and 1,000 stacks/s
all sit below the floor there too; at 71,575/s it reads −9.85% and at unthrottled 74,301/s −9.94%,
the two agreeing on **≈44 laps of lost work per stack**, or roughly 11 µs of victim thread-time at
depth 65.

**A stack costs *more* when you take them rarely, which is the opposite of convenient.** Caller cost
on the same Lucene threads, against the rate:

| stacks/s | median caller cost |
|---|---|
| 10 | **116.7 µs** |
| 100 | 78.1 µs |
| 10,000 | 19.6 µs |
| ~76,000 | 11.9 µs |

Ten times dearer at 10 Hz than at 76 kHz. Back-to-back handshakes find threads already at or near a
safepoint; an isolated one has to bring a running thread to one from scratch. It does not change the
verdict — 10 stacks/s at 117 µs is 1.2 ms/s, 0.12% of one core — but **a benchmark that measured
only the unthrottled case would have reported 12 µs and understated the intended operating point
tenfold.** That is the same trap as measuring the caller instead of the victim, one level down.

**`Thread.getAllStackTraces` is a global safepoint and must never be used for this.** 339.6 µs
median for 8 threads, against 11.9 µs for a single handshake — 28× the cost, and it stops every
thread in the process whether it was interesting or not. The convenient call is the wrong call.

### Would a triggered stack have named the missing label? Yes — and the trigger needs a filter

The proposal is to walk a stack **on demand** rather than at a fixed rate: when a thread has been
outside every label for longer than a tick, which is the threshold the long-instance detector already
uses for labelled work. The argument is that the trigger doubles as a filter — a long unlabelled
window is usually a label somebody forgot, while unlabelled time that is fine-grained and pervasive
is the host's own coordination, which no label could have covered.

Tested with `--gaps`, on the broken placement and the good one as a control. One stack per window,
and only threads whose slot is genuinely empty are walked. **Deepest frame in `lucene.search`, which
is where a clause's identity lives:**

| | broken placement | good placement |
|---|---|---|
| `MultiTermQueryConstantScoreBlendedWrapper.rewriteInner` | **48.4%** | *not in the top ten* |
| `ExactPhraseMatcher.advancePosition` | 7.9% | 16.3% |
| `MaxScoreBulkScorer.scoreInnerWindowMultipleEssentialClauses` | 5.1% | 9.8% |
| `FilterDocIdSetIterator.docID` | 4.2% | 9.4% |

The premise holds. On the broken placement the feature would have said *"of the time outside every
label where a thread was actually running, 48.4% is in `rewriteInner`"* — which names the missing
label outright. On the good placement that frame is gone and what remains is Lucene's coordination,
spread with no culprit above 16%. The control is what makes this evidence rather than a story: the
frame that appears in one and not the other is exactly the one the fix moved.

**But three quarters of the triggers are not unlabelled work at all — they are parked threads.**
Without a filter, 76.6% (broken) to 86.9% (good) of triggered windows are a pool thread waiting for
its next task, and the answer is a screen of `Unsafe.park`. A slot is empty for two entirely
different reasons and only one of them is a missing label. **`Thread.getState()` reads a field and
needs no handshake**, so the cheap check goes first and the expensive one is never paid for an idle
thread. The numbers in the table above are with that filter; without it the top frame in both
columns is `Unsafe.park` and nothing else is visible.

*Caveat on the run-length evidence:* the distribution of consecutive unlabelled ticks does show
windows far longer than scattering alone would give — 2× the geometric expectation from four ticks
on, rising past 30× at twelve — but it was computed without the runnable filter, so most of that
excess is idle threads rather than long unlabelled operations. The frame attribution above is the
evidence for the trigger; the run-length table is not.

### Why the duty cycle sits at 56%: the unlabelled time is mostly idle threads

The same probe answers a question that had been open since the trial. Counting the *state* of the
thread at every unlabelled observation, not only the triggered ones:

- 50.2% of slot observations are outside every label;
- **79.2% of those are a thread that was not runnable at all** — 39.7% of all observations;
- the duty cycle for the same run reads 56.21% on CPU, so 43.8% of occupancy was not CPU.

So parked pool threads account for roughly nine tenths of the off-CPU occupancy. Not memory-mapped
page faults, not the main thread waiting on the executor — idle workers between tasks. This is
[ideas.md](ideas.md) item 10's prediction, measured on foreign code for the first time.

**And it makes the coverage figure much better than it reads.** Treating labelled observations as
runnable — which is not verified, and would be false for a label wrapping something that blocks —
labels cover about **83% of the observations where a thread could have been running**, against the
49.8% of all observations the report currently prints. The honest denominator for "how much of this
run do my labels account for" is runnable occupancy, and the report does not yet use it.

**Method note: the first two attempts at this measurement were both wrong, in the ways this file
keeps recording.** `LockSupport.parkNanos` has a granularity near a millisecond here, so a rate
limiter built on it silently capped at 1,417 stacks/s when asked for 10,000 — the high-rate
configurations never ran and the answer looked like "free at every rate". And toggling the prober
every second put every probing phase at an even second and every control phase at an odd one, so on
a machine that throttles monotonically the drift was charged to the stack walk: it produced +4.89%
at 100 stacks/s against −0.34% at 10,000, which is incoherent. ABBA phasing fixed it.

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

**The bound was pessimistic when threads sat outside any operation, and taking it per thread fixed
it.** The duty cycle covered every registered thread while the shares cover labelled samples only,
so a parked thread lowered the duty cycle without appearing in a single share it supposedly bounded.

Per thread, the stall that could possibly be inside labelled work is `min(stall, labelled) ×
occupancy` — you cannot have more stall inside labels than you have stall, nor more than you have
labels. Summed over threads and divided by labelled occupancy, that is the number the shares are now
bounded by. Three 20-second runs, one for each thing that can go wrong:

| bench mode | aggregate duty | inside labelled work | bound | what the workers' own stopwatches say |
|---|---|---|---|---|
| ordinary, 8 threads | 98.95% | **98.94%** | 1.07 pp | 0.21% preempted → 99.79% |
| starvation, 3 of 15 working | 19.40% | **96.94%** | **3.16 pp**, was 81.2 | 1.04% preempted on the working threads |
| contended lock, 2 ms in 10 | 64.61% | **64.48%** | 55.08 pp | 34.53% lock wait + 0.17% preempted → 65.28% |

Each row is a different failure the fix had to avoid:

- *Ordinary* — every thread works and is labelled, so the two figures must agree, and they do to
  0.01 pp. A change that moved this row would have been a regression, not a fix.
- *Starvation* — the case the old number was vacuous on. 12 parked threads contribute
  `min(1.0, 0.0) = 0` and fall out. The bound goes from "formally unbounded" to 3.16 pp.
- *Contended lock* — the case that decides the *shape*. The label is placed outside the acquisition
  on purpose, so a thread parked on the lock is inside a labelled operation, and 52,422 of
  `lockedUpdate`'s 71,839 hits catch a thread that is not runnable. A "labelled therefore running"
  implementation would print ~0 pp here and be badly wrong. The bound stays at 55.08 pp, and the
  workers' own account of the same quantity — a stopwatch on the thread doing the waiting, sharing
  nothing with the OS accounting — puts duty at 65.28% against our 64.48%.

The starvation bound landed at 3.16 pp rather than the "under 1 pp" the plan predicted, and the
prediction was simply wrong arithmetic: the working threads were already known to be on CPU ~96%,
and `(1 − 0.96) / 0.96` is 4.2 pp, not 1. The measurement agrees with what was already recorded.

**Coverage had the same defect from the other end.** *"Labels cover 49.8% of thread-time"* reads as
a placement failure when 79.2% of the unlabelled samples were a thread that was not runnable at all.
Coverage is now also reported over runnable occupancy alone — with *both* sides restricted, since a
label can be held across a wait and leaving that in the numerator while removing it from the
denominator is the same mismatch pointing the other way.

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

### Against injected blocking

The bench now has one operation that genuinely waits: `lockedUpdate` takes a `ReentrantLock`, holds
it for a configured time, and every other worker that wants it in the meantime is parked. The label
sits *outside* the acquisition, so a parked thread is still inside the operation as far as its slot
is concerned — which is the whole point.

The duty cycle was checked against the workers' own timing of their waits, over a range of injected
blocking spanning two orders of magnitude:

| configuration | blocked, by the workers' own clock | duty cycle | it should have read | gap |
|---|---|---|---|---|
| no lock, 8 threads | — | 98.29% | 99.26% | 0.96 pp |
| hold 2 ms every 25 ms | 0.41% | 98.29% | 99.26% | 0.96 pp |
| hold 100 µs every 2 ms | 12.47% | 81.96% | 83.11% | 1.15 pp |
| hold 2 ms every 10 ms | 34.48% | 64.57% | 65.37% | 0.80 pp |
| hold 200 µs every 1 ms | 64.04% | 34.25% | 34.54% | 0.28 pp |
| 32 threads on 16 cores | 62.21% (preemption, not the lock) | 37.39% | 37.71% | 0.33 pp |

**The duty cycle tracks injected blocking from 0.4% to 64% and never misses by more than 1.15 pp.**
It does not care what caused the thread to be off the CPU — a lock, the scheduler, or thirty-two
threads on sixteen cores all read the same way, which is exactly what a bound on *all* stalling is
supposed to do.

**A queue's waiting time cannot be predicted from its configuration.** The first attempt configured
a lock utilisation of 0.64 — eight threads, 2 ms held every 25 ms — and queueing theory says a mean
wait of about 1.4 ms. Measured: **113 µs**, twelve times less. The cadence restarts after each
acquisition, so a thread delayed by the lock arrives later next time and the threads self-organise
out of each other's way. Negative feedback, not a Poisson queue.

*Consequence:* the configuration sets the regime and nothing more. What the waiting *costs* is
timed by the thread doing the waiting, exactly, for the same reason the preemption detector exists.

### The detector against blocking, and its floor made visible

With a mean wait of 2.95 ms — three ticks — the detector reads 56.04% of occupancy in executions
that outlived a tick, against 64.04% of wall time genuinely blocked: it sees **88%** of it.

With a mean wait of 342 µs — a third of a tick — it reads 4.46% against 12.47% genuinely blocked:
it sees **36%**.

That is the documented floor, measured rather than argued: *a stall shorter than one tick cannot be
seen*, and one comparable to a tick is seen only through the tail of its distribution. What survives
is enough to *name* the operation — `lockedUpdate` was flagged in both cases, at 20× and 200× the
machine floor — but not to *quantify* the blocking, which is what the duty cycle is for. The two
instruments are complementary and neither is sufficient: one attributes without quantifying, the
other quantifies without attributing.

The implied duration column carries the same story in a form a reader can act on: an operation
configured to hold a lock for 100 µs reads **598.6 µs per call**. Six times what it was built to be,
and the difference is waiting.

### A baseline must not contain the effect it is used to detect

Three attempts at the floor an operation is judged against, each broken by a workload the previous
one had not met. This is the recurring trap of this phase and it is worth stating as a rule.

1. **The run-wide rate of long executions.** Assumes long executions are the exception. Against
   Calcite's planner that rate is 53.52%, because most of those labels genuinely are coarse
   operations, so three times it is unreachable and nothing can ever be named.
2. **The non-CPU fraction from the duty cycle.** Right for preemption and GC, which are what the
   machine does to everything — but a thread blocked on a lock is also off the CPU, so a blocking
   operation raises this floor and hides behind it. Measured: `lockedUpdate` with **88.61%** of its
   occupancy in executions over a tick, against a floor of **35.43%** that its own blocking had
   created. Not named.
3. **The lower of that and the median across operations.** Preemption and GC raise every
   operation's rate together, so the median sees them; blocking is concentrated in one operation,
   so the median cannot be moved by it. The machine is blamed only for what both estimates agree on.

Checked against every data set now available, and it is the only one of the three that holds on all
of them:

| run | machine floor | worst operation | verdict |
|---|---|---|---|
| bench, quiet | 3.49% | edgeScan 3.81% | silent |
| bench, 15 threads | 30.50% | checkpoint 36.78% | silent |
| bench, 32 threads on 16 cores | 60% | compact 63.88% | silent |
| bench, lock held 2 ms every 10 ms | 0.81% | **lockedUpdate 92.03%** | named, alone |
| bench, lock held 100 µs every 2 ms | 0.87% | **lockedUpdate 17.32%** | named, alone |
| Calcite planner | 3.53% | **EnumerableMergeJoinRule 83.7%** | six rules named |

Being conservative about blaming the machine means being liberal about naming operations. What stops
that turning noise into an accusation is not the floor but the two guards beside it: a minimum share
and a minimum number of long executions.

### Working or waiting — telling the two apart without attributing anything

An execution that outlived a tick was either waiting for something or working for a millisecond,
and the two want opposite responses: the first means the share is occupancy and not CPU, the second
means the share is honest and the operation merely belongs in the coarse tier. The signal itself
cannot tell them apart. Two numbers already in the report can, one way round.

**The whole run had only so much stalling in it.** Off-CPU occupancy is `(1 − duty) × samples`, from
every cause together. An operation whose long executions occupy more samples than that must have
been *running* for the difference — whatever the rest of the run was doing, and without attributing
a single sample to anybody. Measured, on Calcite's slowest rule: 76.2% of its occupancy in
executions over a tick, against a run that was 96.8% on CPU, gives **at least 91% of that time
certainly on a core**. It is a coarse operation, not a stalling one, and its share is honest — which
is what the trial's agreement with JFR independently said.

**The inverse does not follow, and the report must not pretend it does.** The same budget is charged
in full against every operation separately, so a small operation always comes out ambiguous however
innocent it is: three of Calcite's six named rules cannot be resolved this way, in a workload with
no locks in it at all. So the second verdict is *cannot say which*, and it carries the size of the
budget, which is the part a reader can act on:

| run | off-CPU, whole run | what the reader learns |
|---|---|---|
| Calcite planner | **3.2%** of occupancy | nothing here can be mostly waiting |
| bench, lock held 2 ms every 10 ms | **35.2%** of occupancy | something here is |

Deciding *which* operation needs the thread's state sampled beside its label, and that is phase 6.
This is the cheap half of that question, and it is worth having because it settles the common case:
an operation is flagged, the run is 97% on CPU, and the answer is "your label is coarse, not
broken".

**The bound was checked against a known truth.** The bench times both halves of what `lockedUpdate`
does — holding the lock and waiting for it — so the claim can be tested rather than trusted. It
holds, and it is loose in the safe direction: at least 14.6% running against a real 26.4%.

### What the tool does about a long-running operation: nothing fatal

Decided from the evidence rather than in advance, which was the point of doing it last.

Calcite's rule labels are all flagged by this signal. Their shares agreed with an independent stack
profiler to about a percentage point and produced the 275× finding — the one result this project
has to its name. **A run stopped over them would have destroyed it.** So a long execution is a
warning, never fatal, and what varies is the advice:

- *certainly working:* the share is honest; label it coarse to get per-execution statistics.
- *cannot say:* read the share as occupancy, and here is how much off-CPU time the run had in total.

That is the opposite of the verdict for an operation below the floor, which is fatal, and the
asymmetry is the whole design: **too small is a property of the code, too long is a property of the
run.** A 20 ns label is 20 ns on every machine and every rerun, so there is no run in which it is
fine. A label that outlives a tick may be perfectly measured, and on the only real workload this
project has ever pointed at, it was.

## Thread state beside the label

**The sampled waiting share and the workers' own stopwatches agree to 0.00%.** The bench's
contended lock at 2 ms held every 10 ms per thread — 1.60 lock utilisation at 8 threads, so the
queue never drains — gives `lockedUpdate` 71,839 hits of which **52,422 caught a thread that was not
runnable**. At the achieved step that is **52.483 s of waiting**, against **52.482 s** summed by the
waiting threads' own `nanoTime` brackets. The two share nothing: one is a stopwatch on the thread
doing the waiting, the other is a state read taken from a different thread a millisecond at a time.

**And no false positives.** The other twenty operations in the same run cannot block by
construction, and every one of them read **0.00%**. That is the half of the check that matters more
than the agreement, because a state read that attributed waiting to the wrong label would still
have produced a plausible total.

**A thread the scheduler merely preempted correctly does not count as waiting.** On the ordinary
bench — which never blocks and which the duty cycle nonetheless reads at 66–71% because of
preemption — every operation reads 0.0% waiting. `RUNNABLE` covers a preempted thread, and that is
the intended behaviour rather than a gap: what this column measures is waiting *another thread*
caused, which is the kind that does not add up when occupancy is summed. Preemption is the duty
cycle's business and is already bounded there.

**Occupancy divided by elapsed recovers the concurrency, and it separates the two contention
shapes.** Same run: `lockedUpdate` holds 71.8 s of occupancy over **20.0 s of elapsed** — the whole
run, since at 1.6 utilisation the lock is never free — at **3.75 threads** inside on average. Against
it, every non-blocking operation in the same run sits between 1.00 and 1.36 threads. The bench's
uniform schedule makes those low figures the expected answer, and the lock is the only thing in the
run that piles threads up.

### The long-execution verdict stops saying "cannot say which"

The detector's advice used to be decided by charging **the whole run's off-CPU budget** against each
operation separately, which made almost everything ambiguous by construction. It now reads the
operation's own long-and-waiting samples, and both branches are confirmed on the workload each one
belongs to.

**Waiting — the claim the old test explicitly could not make.** Bench, contended lock:

> `lockedUpdate`: 8,844 executions lasted over a tick — and **70.1% of those long samples caught the
> thread parked or blocked — it is waiting, not working.** Read this share as occupancy: it does not
> add up across threads the way CPU does, and 30.01 s of wall clock had anyone inside it at all.

The workers' own stopwatches say 73.4% of that operation was waiting, against 70.1% among its *long*
samples. The gap has the sign it should: a 2 ms hold always outlives a tick and so is always in the
stuck population, while a wait shorter than a tick is not, which tilts the stuck population towards
holders.

**Working — and it took foreign code to trigger it.** Lucene, 45 s:

> `clause:prefix`: 5,880 executions lasted over a tick (5.0% of its occupancy) — and **100.0% of
> those long samples caught the thread runnable** — it is not waiting on anything, so the share is
> honest and the operation wants a coarse label for its per-execution statistics.

The bench cannot produce this case: it has exactly one operation that can be long, and that one
blocks. An honest long operation needed a real workload, which is the same reason the Calcite trial
existed.

**The wording is "runnable", not "on a core", and the oversubscribed bench is why.** At 32 threads on
16 cores, 59–65% of every operation's occupancy sits in executions that outlived a tick — and all
twenty read **0.0% waiting**, correctly, because a preempted thread is `RUNNABLE`. So this signal
rules out waiting on another thread and says nothing about waiting for a core. Claiming the second
would contradict the duty cycle, which is the only instrument here that can bound it.

### The column is blind to native waiting, and an event loop is native waiting

**Measured on Netty: about 61 seconds off the CPU, and the thread-state column reports zero
milliseconds of it.** A 45-second run on four event loops, 180.00 s of thread-time observed:

| | |
|---|---|
| unlabelled thread-time | 154.31 s |
| …of which a thread was **not runnable** | **0.0 ms (0.0%)** |
| duty cycle over the same run | 65.85% on CPU — so roughly **61 s off it** |

The mechanism was predicted in writing before the run. `Thread.getState()` reports `RUNNABLE` for a
thread inside a native call, and an event loop parked in `epoll_wait` or `WSAPoll` is inside a
native call. Java thread state cannot see through the JNI boundary, so it is not that the column is
imprecise here — it is blind, on the workload shape it was built for.

**What this settles about the two instruments**, which phase 3.5 argued and could not demonstrate:
they are two different quantities and each is blind where the other sees.

| | catches | misses |
|---|---|---|
| thread state, per operation | waiting another *thread* caused — a lock, a monitor, a park | native I/O waits; scheduler preemption |
| duty cycle, aggregate only | everything not on a CPU, both of the above included | which operation it belonged to |

61 s in one column and 0 ms in the other, same run, is as strong a demonstration as that argument
will ever get. It is not a case for extending the state read: the JVM does not know either.

### What it costs

**The slot walk goes from ~190 ns to ~284 ns per slot — about +93 ns.** Measured directly on the
sampler thread, `--state=off` against `--state=on`, ABBA-interleaved over four 15-second runs:

| | per slot |
|---|---|
| off | 163.4 ns, 217.7 ns |
| on | 268.6 ns, 299.2 ns |

The ranges do not overlap, which is what makes this the conclusive half of the measurement. **At
eight slots that is 0.75 µs added to a 1 ms tick — under 0.1% of the sampler's budget**, and the
punctuality is untouched: the achieved step is 1.002–1.004 ms in both arms with 0–2 resyncs in both,
and the one 27 ms outlier tick occurs once in each arm, so it is the machine and not the feature.

**Ninety-three nanoseconds is a lot for a field read, and the likely reason is that it is not one.**
`WeakReference.get()` followed by `Thread.getState()` is a chain of dependent loads — reference,
thread, field holder, status word — none of them in the sampler's cache, since the sampler touches
each thread's object once a millisecond and nothing else. That would make this memory latency rather
than work. *Not verified*: it is the explanation that fits, and separating it from the weak
reference's own read barrier would need a variant with a strong reference to compare against.

**The scaling limit follows from the same number, and it is worth recording before anyone meets it.**
At the `MAX_SLOTS` ceiling of 1024 the walk would add roughly **95 µs per tick, about 10% of a 1 ms
step**. Eight threads is free; a thousand is not, and a thousand slots is exactly what the virtual
thread hazard produces.

**The effect on the workers could not be measured — the machine moves by more than the effect.**
Over the same four runs, root calls per 15 s came out 234.4 M and 302.4 M with state on, against
311.6 M and 274.0 M with it off. The spread *within* one configuration is 29%, larger than any
difference between them, so this says nothing and is recorded as saying nothing. It is the same
wall phase 3 hit on the hook's own throughput comparison, and for the same reason. What would settle
it is interleaving the two configurations inside one JVM rather than across four, which the bench
cannot currently do for this switch.

There is a reason to expect the worker cost to be near zero regardless, and it should be treated as
an argument rather than a result: the sampler reads the thread's status word, which the JVM writes
only on a state *transition*. A compute-bound worker never transitions, so there is nothing for the
read to contend with. A worker parking and unparking thousands of times a second does transition,
and that is the configuration where a cost would show up if there is one.

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

## Placement

**A label belongs on the whole lifecycle of what it names, and a framework that separates
construction from use separates the cost too.** Lucene's clauses were first wrapped at the *product*
— the scorer and its iterator — leaving the factory calls bare, on the reasoning that building a
scorer is setup and the work is in the scan. True of a term clause; false of a prefix clause, which
rewrites into a hundred terms and unions their postings into a bitset before a single document is
scored. Measured: the prefix clause read **32.211%** with the factories unlabelled and **48.491%**
with them labelled, and unattributed occupancy fell from 60.7% to 47.8%. The single hottest complete
stack in the whole baseline recording — 9.05% of all samples — is that bitset being built inside
`ScorerSupplier.get`.

**And the report gave no sign of it.** Shares summed to 100%, every clause carried a plausible
number, the ordering looked sensible, and the answer was a third low on the clause that mattered.
Nothing internal to the tool could have caught it, because from the tool's point of view the time
really was outside every label. What caught it was disagreement with an independent measurement.
**A misplaced label is invisible from inside the report** — the same conclusion the leak experiment
below reaches by a different route.

**Invisible in the share column, obvious in absolute thread-time — which is why the report now
carries both.** The mistake was preserved as a configuration and both placements run twice, so the
difference could be measured rather than recalled:

| | share | | occupancy | |
|---|---|---|---|---|
| | product only | factories too | product only | factories too |
| phrase clause | 58.4%, 57.2% | 42.9%, 43.6% | 38.2 s, 41.5 s | 41.0 s, 40.1 s |
| prefix clause | 30.9%, 32.3% | 48.6%, 47.7% | 20.2 s, 23.5 s | 46.5 s, 44.0 s |
| labels cover | — | — | 65.3 s, 72.5 s | 95.5 s, 92.1 s |

By share the phrase clause appears to become **fourteen points cheaper** when a different clause's
label is fixed — a change with a plausible story attached, and the story is false. Its occupancy
does not separate by placement at all: 38–41 s in all four runs, which is this machine's run-to-run
spread. The prefix clause doubles, and the 25 s of coverage the fix gained is that one clause and
nothing else.

The general rule: **share re-scales every row whenever the set of labels changes, so it cannot be
compared between runs; absolute occupancy can.** That is the column to watch while placing labels,
and placing labels is iterative by nature. It costs nothing new — `hits × step` — and it was already
being thrown away.

**It does not solve the cold start.** With no earlier run to diff against, 36% coverage and 53%
coverage both look like "some coverage", and nothing in the absolute numbers says which is missing a
label. Localising a gap on a first run needs either bracketing by a coarse label or an actual stack,
and both are open — see [ideas.md](ideas.md) items 13 and 14.

**Placement by wrapping can silently change what the library does, and the counts column is the
tell.** Lucene 10 gives an iterator four bulk fast paths — `intoBitSet`, `docIDRunEnd`,
`nextDocsAndScores`, `ScorerSupplier.bulkScorer` — each with a working base-class default that falls
back to a doc-at-a-time loop. A wrapper that overrides only the obvious methods compiles, returns
the right documents, and profiles a query the library would never have run: **13.3% slower, one
clause's calls 40× higher (13.9 M → 556 M), its share 2.9× higher (1.73% → 4.94%), and its rank
moved from seventh to third.** Delegating all four preserves the path exactly — `MaxScoreBulkScorer`
inclusive 76.49% unwrapped against 76.85% wrapped, and the fallback bulk scorer never appears. The
naive report is not marked wrong anywhere, but 556 M calls at 8.2 ns each is not a plausible row:
**an implied per-call duration far below the floor is evidence about the placement, not only about
the label.**

**`strict` caught that on its own, in one second, on foreign code** — `clause:term#2: 5,115,899
calls at under 23.9 ns each, below the 50 ns floor` — against a mistake nobody anticipated when the
check was written. See the caveat under "The machine" and in the open questions: it stopped the
correct placement too.

**Wrapping restores the lexical form; a callback does not.** Calcite's only boundary was a pair of
notifications with no `finally`, which is why `Profiler.enter` / `exit` exist. Lucene's extension
point is a wrapper, and a wrapper method body is a block we own — so `op(id) { }` works everywhere,
its `finally` is compiler-generated, and no leak is possible. Two libraries, two shapes, and the
second data point that the library needs both forms.

**A leaked label does not look like an error. It looks like a finding.** Measured on purpose: one
worker of four enters an operation and never exits it on every thousandth pass, so everything that
thread does afterwards is billed to that operation until the next check. By construction that
operation and the one beside it do exactly the same amount of work — 40.7% each. The clean one read
**40.4%**; the leaking one read **43.8%**.

3.4 percentage points, on a line that carries a plausible share, a plausible call count and a
plausible implied duration. Nothing in the numbers says which one is wrong. That is why the balance
check is a count in the report rather than advice in a document, and why the non-lexical form is
documented second: `op(id) { }` has a `finally` written by the compiler and cannot do this.

**Folding empty operations is not just tidiness — it separates two different things.** Calcite's
report carried twenty-five rules at 0.000%. Folded away with a count, the report also names the ones
that *ran* and were still never sampled: on a 25 s run, `rule:EnumerableLimitRule` at 48,564 calls
and zero hits. An operation nobody called is noise; an operation called fifty thousand times that
the sampler never once caught is a statement about its size — under a nanosecond per call by the
rule of three — and belongs in front of the reader.

## Open questions

**What a coarse label will cost per execution — only two thirds of it is measured.** The tier
boundary in [profiler.md](profiler.md#where-the-boundary-is) is derived from ~40 ns per coarse
execution, of which **21.5 ns is measured** — fitted from the Lucene timed-wrapper comparison, which
is two `nanoTime` calls plus an accumulate — and the rest is an assumed cost for allocating the
context object, which nothing here has measured. The boundary moves with it: at 30 ns the coarse
floor is 600 ns rather than 800, at 60 ns it is 1.2 µs. Worth measuring before any threshold is
hard-coded, and the bench already has the shape needed to do it, since `--labels` and `--sampler`
are separate switches and a third would follow the same pattern. Two things also unaccounted for and
harder: the allocation's effect on *other* code through GC pressure, and the likelihood that the
extra body prevents C2 inlining, which is not additive at all — the demo has already shown C2 moving
work across label boundaries by 95%.

**The floor check is not machine-independent, and it stopped a correct placement.** Measured above:
implied per-call duration on this laptop rises 3× between a two-second and a twenty-second run,
purely from throttling, so `strict` halted a Lucene placement whose settled number is above the
floor. Three candidate fixes, none tried: require a minimum elapsed time or a minimum sample count
before the check may fire; require the estimate to be stable across two consecutive windows rather
than merely low in one; or normalise per-call duration by an observed clock rate, which the duty
cycle machinery already probes for another purpose. The second is the cheapest and needs no new
measurement. Whichever is chosen, the "identical on every machine and every rerun" claim in
`floorCheck`'s documentation has to go — it is false on this hardware.

**~~Why the duty cycle sits at 56% on a workload that ought to be CPU-bound.~~ Answered:** parked
pool threads, accounting for about nine tenths of the off-CPU occupancy — 79.2% of unlabelled slot
observations are a thread that is not runnable. Measured above. The candidates guessed at here
originally — memory-mapped page faults, the main thread blocking on `TaskExecutor`, slice skew —
are between them the remaining tenth.

*What is now open in its place:* the report divides by the wrong denominator twice over. Coverage is
quoted against all occupancy where it should be against *runnable* occupancy (49.8% against ~83% on
the same run), and the duty cycle's bound on a share is computed over every thread including the
idle ones, which is why it comes out wider than the shares it applies to. Both want the per-thread
split in [ideas.md](ideas.md) item 10, and both now have a number showing what it is worth. One
thing to verify first, because the ~83% assumes it: that a thread inside a label is always runnable.
It would not be for a label wrapping something that blocks, and the bench's contended-lock mode is
exactly where to check.

**The detector's thresholds are provisional.** Naming an operation takes three conditions together:
a rate of long executions three times the machine floor, a floor of 2% under the rate itself, and at
least 20 long executions behind it. The floor's *source* is settled by measurement — three
candidates were tried and only the third survives every data set — but those three numbers are
judgements. They separate all six configurations we have correctly, which is evidence but not
calibration.

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

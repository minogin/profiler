# Ideas

Things worth doing that are not yet decided, not yet tested, and not yet a phase. This file exists
because an idea that lives only in a conversation evaporates — the counterfactual mode below was
thought of, agreed to be good, and then not written down anywhere until it was asked for a second
time.

The three docs divide like this: [findings.md](findings.md) is what we measured and now know,
[plan.md](plan.md) is work that has been committed to, and this is everything else. An entry
graduates to plan.md when we decide to do it, or gets deleted with a line saying why not.

Each entry carries a **status**: `open` (nobody has looked), `blocked` (waiting on something
named), `promoted` (now in plan.md), or `dropped` (with the reason).

---

## 1. Counterfactual mode — disable an operation and re-run · open

The profiler already knows every registered operation. It could support suppressing one and
re-running the workload, reporting the two wall-clock numbers side by side.

**Why it matters more than it sounds.** In the Calcite trial an operation measured at **46% of the
time** turned out to be worth a **275× speedup** when removed, because it was inflating every other
operation's work as well as doing its own. A share and a counterfactual are different quantities
and the gap can be two orders of magnitude in either direction. The question a user actually has is
never "what fraction was this" — it is "what happens if I don't do it".

**The hard part, and why this may force the fork.** Suppressing an operation means not executing
it, and `op(id) { }` wraps a body that the host, not us, decided to run. A passive hook cannot help:
Calcite's `RelOptListener.ruleAttempted` is a notification with no return value and no veto. So this
needs one of three things — a host that supports skipping (rare), a fork of the host (see item 3),
or a profiler API where the *body* is ours to skip:

```kotlin
op(id) { ... }            // today: always runs
opUnless(id) { ... }      // could return null / skip when id is suppressed
```

The third is cheap in code we own and useless in code we do not, which is a real limit worth
stating rather than hiding.

**The objection that nearly kills it, and must not be designed around quietly.** Disabling logic
changes the workload. On the supply-chain traversal this idea comes from, switching a piece of
logic off *reduces the graph being traversed*, so the fast run is a smaller problem rather than the
same problem minus one part — the comparison measures two workloads, not two implementations. The
Calcite trial has the same flaw: removing the merge join rule changed the plan search space, so the
275× is a true wall-clock fact and is not "the cost of the rule". Recorded in [case.md](case.md),
because this is also the flaw in the manual method everyone falls back on.

So a "disable and re-run" feature that prints one number would be *industrialising a known-bad
method*. If it is built, it has to carry evidence about whether the workload stayed the same —
call counts of the other operations are the obvious check, and we already collect them: if
suppressing X halves how often Y fires, the comparison is void and the tool should say so rather
than print a speedup.

**Open sub-questions.** Does suppression mean "skip the body" or "run it and don't count it"?
How does it interact with nesting? Is the re-run automatic (two passes in one process, order
swapped — see the position artifact in findings.md) or is it a flag the user sets by hand?

## 2. Implement the same labels as JFR custom events, and compare · open

Build the Calcite labels a second time as `jdk.jfr.Event` subclasses with a rule field, correlate
the duration events against the execution samples offline by thread and timestamp, and compare the
resulting shares to ours and to JFR's inclusive view.

**Why.** Three reasons. It is a *third* independent validation of the sampler, on foreign code.
It prices the honest alternative, so [case.md](case.md)'s claim about custom-event cost stops being
an estimate. And it tells us whether the offline correlation produces anything our sample-time
lookup does not — per-instance durations, for one, which is exactly what the coarse tier wants.

**Cost.** Small. The label placement already exists; this reuses the same `RelOptListener` and
swaps what happens inside it.

## 3. Fork Calcite and label the generic internals · open

Clone Calcite, place spans by hand inside `VolcanoPlanner` and `RelSubset` — operand matching,
memo registration, set merging, digest construction — build, and re-run the trial.

**Why this is the more interesting experiment, not the fallback.** The trial labelled rule firings,
where Calcite has a clean domain boundary and a hook to hang it on. That is the easy case. The
unreached costs are the generic ones: `RelSubset.contains` at 24.6% of self time, `computeDigest` at
6.9%, the registration cascade that makes up most of the recursive tower. A flame graph is already
bad at those. Whether labels help *there* is untested, and it is the question that decides how
broadly the fine tier is useful.

**It is also the prerequisite for item 1** on this target, since suppressing a rule from outside is
not possible through a listener.

**Cost.** Not small — clone, patch, build a large Java project, and keep the patch alive. Wants an
explicit decision.

## 4. Read Datadog's Java profiler on context tagging · open

Datadog's profiler tags execution samples with the active trace/span context ("Code Hotspots") —
a thread-local label attached to samples, which is the same shape as this design. It is the closest
prior art we know of and nobody here has read it.

**Why.** To find out what they hit that we have not, before phase 4 fixes the API. Also to be able
to say honestly what is new here and what is not. [profiler.md](profiler.md) has the prior-art
section this belongs in.

**What we already have second-hand.** Phase 8 in [plan.md](plan.md) records that Datadog's attempt
at per-scope JFR events inflated recordings more than tenfold — which is where the "JFR as
transport, not as mechanism" position came from. That is a citation, not a reading.

## 5. The report should push "why is it expensive", not "delete it" · open

A profiler that prints a ranked list of shares invites exactly one response: remove the top row.
The Calcite trial produced a 275× speedup by deleting a planner rule — and the honest reading is
that the rule was structurally incapable of paying off *in that workload*, not that it is useless.
The output made the wrong action look obvious.

**What this might mean concretely.** The counterfactual warning (item 1 and friction item 5) is the
minimum. Beyond that: showing call counts beside shares already reframes "expensive" as "expensive
per call" or "called too often", which are questions rather than verdicts. Whether the report can
do more than that without editorialising is genuinely unclear.

## 6. Leave the profiler on permanently · dropped

Dropped as a goal: this is a developer's tool, run in profiling sessions and perf harnesses on a
machine you control, not an always-on agent inside a live system. Everything that followed from the
always-on framing goes with it — windowing for a process that has been up for a week, cross-process
aggregation, an attach agent, and the objection that the spinning sampler costs a core. A core is
an accepted cost here, the same way nobody closes Spotify to run a profiler.

**What survives, for a different reason.** The ~2 ns hook is not justified by a production resource
budget; it is justified by the **observer effect**. The target regime is operations of tens to
hundreds of nanoseconds, and an instrument costing 100 ns would be measuring itself. That
requirement is unchanged and is the reason the hook was worth the work.

**And a much smaller need survives too:** within a single session you want to reset and take more
than one snapshot — *"clear it, I am about to run the thing"*. That is a small feature, not the
windowing machinery, and item 7 needs it as well.

## 7. A perf-regression harness — the profiler as a CI gate · open

Run a labelled workload on every build, keep the per-operation shares and call counts, and fail or
flag when one moves. Still a developer's tool by any reading — it runs where the tests run, not in
a live system — so none of the always-on machinery applies.

**Why it fits this design particularly well.** Call counts are *deterministic* in a way timings are
not: if an operation fires 78,756 times on one build and 240,000 on the next, that is a real change
in behaviour and no amount of machine noise produces it. Shares need a noise floor and a tolerance;
counts need neither. A regression gate built on counts first and shares second would be unusually
robust for the genre, and no stack profiler can offer it because none of them have counts.

**What it needs that does not exist.** Machine-readable output — the report is `render()` to text.
Run-to-run comparison, with the tolerance discipline from findings.md rather than a fixed
percentage. And a session reset, so a harness can measure phases separately.

**What it does not need**, and this is the point: no agent, no windowing, no always-on story, no
cross-process aggregation.

## 8. Suspected defect: the label follows the thread, not the task · open

*Reasoning from the source, not yet reproduced. A test is the first thing to write.* Two hazards
that look like one problem and are not — they hit different tiers, in opposite ways, and want
different fixes. Recorded here rather than in findings.md because nothing has been measured yet.

| | attribution | slot registry | tier affected |
|---|---|---|---|
| **Coroutines** | **broken** — the label follows the thread, the work follows the continuation | fine; a dispatcher pool is bounded | coarse only |
| **Virtual threads** | **correct** — the JDK gives each virtual thread its own `ThreadLocal`s, so the slot follows the vthread across carrier switches | **explodes** | **fine, today** |

The first version of this entry treated them as one thing and had the impact backwards.

### The fine tier is safe from coroutines, and the reason is structural

The defect needs a **suspension point between the label write and the restore**. Nothing else
triggers it. If the body does not suspend, the label is set and restored on the same thread in the
same continuation, and the attribution is correct with no assumption required.

And a suspending body **is by definition not a fine-grained operation**: a suspension point costs a
continuation allocation plus a dispatch, hundreds of nanoseconds at best, against the tens of
nanoseconds the fine tier exists for. So "fine-grained" and "suspends inside the op" are mutually
exclusive by construction. The fine tier's same-thread assumption is self-enforcing rather than
merely convenient, which is worth knowing before anyone spends effort defending it.

This is also what [plan.md](plan.md) already decided from the other end — phase 4 is same-thread and
*"crossing threads is phase 5, and the two are worth separating so that propagation bugs cannot be
confused with tier bugs."* The coroutine defect is squarely a phase 4/5 problem, not a fire in what
is shipped.

### But the API does not enforce the boundary, and cannot do it cheaply

`op(id) { somethingSuspending() }` compiles with no warning, because Kotlin inlines the lambda into
the calling function and it inherits the call site's suspend-ness. The clean compile-time fix is
marking the lambda `noinline` — the lambda becomes a real function object of non-suspend type, so
suspend calls inside it become illegal — but that costs an allocation per call, which is fatal for
exactly the tier being protected.

So the guard has to be **runtime and opt-in**: a checked mode that verifies at exit that the
calling thread still owns the slot captured at entry. That is a second `ThreadLocal` lookup, which
is the expensive half of the hook — acceptable in a debug run, not in the measured one. A real
constraint on any fix, and it should be settled before phase 4 rather than during it.

### What goes wrong, mechanically

**Coroutines.** `op(id) { }` is an inline function, and Kotlin permits suspension inside an inline
lambda, so `op(id) { somethingSuspending() }` inside a `suspend fun` compiles with no warning. What
then happens:

```kotlin
val slot = Profiler.slot()          // thread A's slot, captured here
slot.setOpaque(id)
try { return body() }               // suspends; resumes on thread B
finally { slot.setOpaque(prev) }    // clears A's slot, not B's
```

- Thread A's slot stays set to `id` for the whole suspension, while A goes on to run unrelated
  work — all of it billed to `id`.
- Thread B, where the work actually resumes, was never labelled, so the real time lands on
  whatever B's slot happened to hold.
- The restore writes to the wrong slot.

Wrong in both directions, silent, and the numbers look plausible. This matters more than an
ordinary bug because [profiler.md](profiler.md) lists *"coroutines break attribution — work is
spread across dispatcher threads and suspensions cut the trail"* as one of the three reasons the
existing tools fail here. It is a founding motivation, and the implementation currently has the
same defect it was built to fix.

**Virtual threads.** `Profiler.slot()` is a `ThreadLocal`, so every virtual thread gets its own
`OpSlot` appended to a `CopyOnWriteArrayList` — an O(n) array copy per thread created, quadratic
over a run — and the sampler then walks the whole list every millisecond. `release()` is manual, so
anything not released stays in the walk list forever. A target that creates virtual threads freely
does not degrade this gradually; it makes the sampler's tick unbounded. The class doc records the
assumption this was built on — *"threads are expected to register themselves up front, so the list
is stable by the time the sampler starts"* — which is the bench's threading model, not a real
target's.

Note that the virtual thread half needs **no suspension and no coroutine**. A target that runs
perfectly ordinary 20 ns operations on a million short-lived virtual threads kills the registry on
its own. That is why it is the half that touches shipped code.

**Both are properties of the *target*, not of how the profiler is deployed.** They bite a developer
profiling their own app on their own machine exactly as hard as anything else would.

**Possible directions, none chosen.** A label carried on the coroutine context rather than the
thread, with a `ThreadContextElement` restoring it on each resume; a `suspend`-aware `op`; refusing
to compile against a suspending body; or accepting the limit and documenting it loudly. The virtual
thread half wants a different slot registry — one that does not copy on every thread and can evict
without being asked.

## 9. Detect when a "fine" operation is not fine, for free · promoted

**Promoted to phase 3.5 in [plan.md](plan.md)**, reframed on the way: the point is not to detect bad
operations but to *bound the error* on every share at once, since "could this block?" classifies
everything as coarse and answers nothing. The detector below survives as one of three deliverables.

**The problem this answers.** The fine tier rests on two assumptions, and neither is verified:

1. **Operations do not block.** Argued from "a 20 ns operation has no time to block". But a fine
   operation can park on a **contended lock** — nanoseconds uncontended, microseconds or worse
   contended — and it can stall on a GC pause, a page fault, or a plain OS deschedule. When it does,
   occupancy inflates and the report says *"this operation is expensive"* where the truth is *"this
   operation is waiting for another thread"*. Opposite diagnoses, opposite fixes. For parallel
   in-memory graph traversal — the workload this tool exists for — that is not hypothetical.
2. **Threads ≤ cores.** The sampler reads slots, not cores, so with 200 runnable threads on 16
   cores every slot is counted every tick and occupancy over-reads CPU by 12×. The bench used 8 and
   16 threads on 16 cores and never oversubscribed, so this has never been exercised.

So the honest status is not that "fine operations do not block" is *wrong* — it is that it is a
**design intent asserted by the person placing the label, and never checked**. Which is exactly the
kind of thing this project keeps insisting on measuring instead of assuming.

**The detector, and it costs nothing on the hot path.** The sampler already reads the slot's
operation id. Have it read the **call counter for that id** in the same tick, and keep both:

| consecutive ticks | id | counter | meaning |
|---|---|---|---|
| same | same | **increased** | many short instances — genuinely fine |
| same | same | **unchanged** | *one* instance spanning ≥ 1 ms — 50,000× longer than a 20 ns operation should be |

That is the whole test. A single 20 ns instance has a 1-in-50,000 chance of being caught by one
sample, so an instance that survives two consecutive ticks is **not the size its label claims** —
which is a different statement from "not a fine operation", and the wording here used to be the
second. The [tier boundary](profiler.md#where-the-boundary-is) puts no upper limit on the fine tier,
and Calcite settled it in practice: labels of hundreds of microseconds, sampled, agreeing with JFR
to about a percentage point. What the detector finds is a label whose *description* is wrong, and
the useful consequence is that coarse is now **available** to it — not that fine has stopped working.
The counter increment already exists on the hot path; the sampler reads one extra word.
Entry and exit are untouched, which is the constraint that matters.

**What it cannot tell you alone.** An unchanged counter means "stuck in one instance", which
conflates blocked, descheduled, and legitimately-long-but-running. Separating those needs the
thread's state — but only for slots the counter test already flagged, which is normally almost
none. That wants a (weak) thread reference on the slot, off the hot path.

**What comes out.** Two counters per operation instead of one — samples where the instance was
fresh, and samples where it was stuck — which yields both an occupancy share and a running share
from the same pass. That is `span − CPU` for the fine tier, obtained without spans. And it is a
direct answer to the third requirement in [profiler.md](profiler.md): not just *where* the time
went, but *whether the operation was working or waiting*, which is the "why" a user needs before
they can act.

**On automatic promotion to the coarse tier.** Retroactive promotion is not possible — the coarse
tier allocates a context object at entry and those entries have already happened. Two weaker forms
are: *report it* ("operation X had N instances lasting ≥ 1 ms; consider a coarse label"), which is
cheap and probably enough; or *adaptive promotion*, where a detected operation starts allocating
contexts on subsequent entries, which needs a check at entry and therefore costs hot-path budget.
Start with reporting; the diagnostic is most of the value.

## 10. The duty cycle per thread, so the bound is not vacuous when threads idle · promoted, done

Phase 3.5 measures the duty cycle over every registered thread, and the shares it bounds are over
labelled samples only. A thread parked outside any operation therefore lowers the bound without
appearing in the thing being bounded. Starvation mode is the extreme case and it is now measured:
18.83% duty and a formally unbounded error on every share, while the three working threads were on
CPU 96% of the time and their shares were fine. Any real application with an idle pool thread hits
a milder version of this, and a report that cries wolf at a 30%-utilised thread pool will be
ignored when it is right.

The fix needs both halves per thread rather than in aggregate: thread *i*'s stall fraction from
`getThreadCpuTime`, which the duty walk already computes and throws away, and thread *i*'s labelled
fraction, which is the share of the window's ticks where its slot held an operation. The stall that
could possibly be inside labelled work is then `Σ min(stall_i, labelled_i)`, which in starvation
mode is the three working threads' 0.8% and not the twelve parked ones' 100%.

The labelled fraction per window needs a per-slot counter written by the sampler, which needs the
immutable slot index that the long-instance detector wants anyway — so the two are naturally done
together, and doing them together is probably right.

**Also parked here:** the duty walk currently runs on the sampling thread, where its dearest
observed walk is 214.7 µs once a second — enough to push one tick in a thousand out by a fifth of a
step, with no resync. A thread of its own that parks for a second would cost no core and no
punctuality; it would cost the guarantee that both cover exactly the same span. Not obviously worth
it, but worth remembering that the sampler's punctuality was expensive to get.

## 11. Correct the attribution bias using call counts · dropped

The sampler reads high on parents and low on short leaves. With call counts the correction is
arithmetic rather than a model, and the counts are already collected. Blocked on understanding the
bias well enough to know what to correct — see the open question in findings.md.

**Dropped, on the accuracy principle** in
[profiler.md](profiler.md#how-accurate-this-has-to-be-and-where-that-budget-goes). This bias is
roughly uniform in direction and largely cancels when shares are taken, so it cannot move a ranking
or point at the wrong operation — and those are the only two failures worth spending on. The worst
case we have measured is `tinyStep` at 15% of itself, on an operation holding 3.2% of the run.
Correcting it would change nobody's next action.

**What would revive it:** evidence that the bias scales with something that differs between
operations — call count, nesting depth — rather than with size alone. That is the shape that swapped
Lucene's top two clauses when a *timer* did it, and it would be intolerable here for the same reason.
The open question in findings.md is worth keeping for that reason and not for this one.

## 12. Make the floor check survive a machine whose clock moves · half promoted, half open

The floor check fires within a second on the argument that a below-floor label is a property of the
code, "identical on every machine and every rerun". Measured on Lucene, that is false here: this
laptop's throughput falls 2.2× between a two-second run and a forty-second one, and implied per-call
duration rises with it — 18.1 ns at two seconds, 54.1 ns at twenty, for the same label. `strict`
stopped a *correct* placement citing 27.5 ns against a settled 49.7 ns. Shares over the same range
moved by under 0.4 points, so only this one column is affected.

**The accuracy principle decides this, and it decides against the clever options.** By
[profiler.md](profiler.md#how-accurate-this-has-to-be-and-where-that-budget-goes), effort belongs
where an error can move a ranking or point at the wrong operation. A below-floor label does neither —
it is one row of a list, and the cost of getting it wrong is that we *stop a correct run*, which is
the loudest failure this tool can produce and the one we have actually observed. So the first move
is not a better estimator at all: **make it a warning rather than fatal, and keep the advice**, which
costs nothing and cannot stop anybody's correct placement.

**That half is done** — plan.md § 1a. The estimator options below are what is left, and they are now
optional: nothing downstream depends on the bound being tight, because nothing stops on it any more.

That leaves the estimator work optional rather than blocking, and it should be judged on whether it
buys anything a warning does not. Options 2 and 4 below remain interesting for their own reasons —
4 in particular, because "a floor in units of the hook" is a *ratio*, and a ratio is the only form of
this check that could ever be machine-independent the way the documentation claims.

Candidate fixes, cheapest first:

1. ~~**Require two consecutive windows.**~~ **Falsified by our own data — do not do this.** It was
   written on the assumption that the early estimate is *noisy*. It is not: the counters are
   cumulative and the estimate drifts steadily upward as the clock decays, so consecutive windows
   sit inside the drift and both fire. For `term#2` on the careful placement the bound reads 33 ns
   at one second, 41 ns at five, 53 ns at ten and 68 ns at twenty; any consecutive-window rule fires
   in the first half of that. Recorded rather than deleted because the reasoning was plausible and
   the data killed it, which is the only reason this file is worth keeping.
2. **Require a minimum evidence budget** — a sample count or an elapsed time — before the check may
   fire at all. This one *is* supported: at twenty seconds the check declines to fire on the careful
   placement and does fire on the naive one, so it is correct given time. But the crossover here is
   between five and ten seconds, and that number is a property of this laptop's throttle curve, not
   of anything the tool knows. Picking it is exactly the kind of judgement this project keeps
   getting wrong without a bench for it.
3. **Fire immediately only on an unambiguous margin, and wait otherwise.** The observed clock swing
   is 2.2–3×, so an estimate below floor/3 cannot be a throttling artifact and can be fatal on
   sight; between floor/3 and the floor, keep watching and downgrade to a warning if it never
   settles. Separates the two cases we have at twenty seconds and does not need a magic delay.
   Whether it separates them at one second is untested — at one second the naive and careful
   placements both read 24–28 ns for the same label, so possibly not.
4. **Normalise by an observed clock rate, or express the floor in units of the hook.** The three
   reasons for a floor — the hook is a visible fraction, the sampler reads short operations low, C2
   moves work across adjacent boundaries — are all *ratios*, and both sides of every one of them
   scale with the clock. An absolute-nanosecond floor is machine-dependent by construction. A floor
   of k × measured-hook-cost would be machine-independent, which is what the documentation already
   claims. It also implies the floor depends on *how the label was placed*: an inline `op(id){}` hook
   is 1.7 ns and a wrapped one is 3–6 ns, so a wrapped placement's floor should be two to three times
   higher — which would put most of the Lucene term clauses in marginal territory. Most principled,
   most work, and the conclusion may be unwelcome.

Related, and **settled**: the ladder's premise was that a below-floor label is *fatal* because it is
deterministic, while a long operation is only a *warning* because it depends on the day's workload.
The floor verdict turned out to depend on the machine's power state, so the distinction did not hold
and the rung was revisited rather than patched. Fatal now means one thing — a leaked label.

## 13. Report the boundary a label does not cover · partly promoted

Lucene's first placement wrapped the scorer and not the supplier that built it, and lost a third of
the decisive clause with nothing in the report to suggest it. The general problem is that the tool
cannot see what it was not asked to look at, and unattributed occupancy is reported only as one
aggregate number ("47.8% outside any operation").

**Half of this is now built and is not an idea any more.** The report carries absolute occupancy per
operation and coverage in thread-time, which solves the *iterative* case completely: move a label,
diff the occupancy column, see exactly what changed and by how much. Measured, in
[findings.md](findings.md#placement). What is left here is the cold start — a first run, nothing to
diff against, and no way to tell 36% coverage from 53% coverage.

Two routes remain, and they are complementary rather than alternative:

**Bracketing, which the coarse tier gives us for free.** You localise a gap by enclosing it. With a
coarse label around one search, the report can say *"48% unlabelled, all of it inside `search` and
none inside `collect`"*; two levels narrow it again. This is a better argument for phase 4 than the
one currently written into the plan, which is about per-execution durations and percentiles. It
needs no new mechanism at all — it falls out of the cross-tabulation the coarse tier already
implies.

**A low-rate stack sample, but only on the ticks that are unlabelled** — item 14.

## 14. A low-rate stack sample on unlabelled ticks · unblocked, ready to promote

We refuse to walk stacks because the cost is per sample. But localising a gap does not need a stack
per sample — it needs enough to tell "one place" from "everywhere". At 10 Hz over twenty seconds
that is 200 stacks against 20,000 label ticks, and it fires *only when the slot is empty*, which is
exactly when the thread is established not to be doing anything we are measuring. Nothing on the hot
path changes.

It would have caught the Lucene mistake outright. The missing work was 9.05% of all samples under
one distinctive frame, so ~18 of 200 low-rate samples would have landed there, and the report could
have said: *"of the time outside every label, the largest single place is
`RewritingWeight.scorerSupplier` — 31% of it"*. That names the missing label rather than announcing
that one exists.

**~~Unknown and blocking: what a stack costs.~~ Measured — see
[findings.md](findings.md#walking-a-stack). It is affordable by three orders of magnitude.**

- On the Lucene workload, at 10 and at 100 stacks/s the effect on search time is inside a ±2% floor;
  it only becomes measurable at ~76,000/s, where it is +9.10%. Extrapolated down: **0.001% at
  10 stacks/s.**
- Caller cost is ≈5 µs of handshake plus ≈0.11 µs per frame, and it *rises* as the rate falls —
  116.7 µs per stack at 10 Hz against 11.9 µs at 76 kHz, because an isolated handshake has to bring
  a running thread to a safepoint from scratch. Still 1.2 ms/s at the intended rate.
- **`Thread.getAllStackTraces` is a global safepoint (339.6 µs for 8 threads) and must never be used
  here.** Walk threads one at a time.

**The premise is now tested too, not only the cost.** Walking one stack per unlabelled window longer
than a tick — *on demand* rather than at a fixed rate — puts the missing label at **48.4%** of the
walked stacks on the broken Lucene placement and out of the top ten on the good one. The trigger
doubles as a filter: a long unlabelled window is a label somebody forgot, while pervasive
fine-grained unlabelled time is the host's coordination, and only the first fires it.

**Two things the test changed.**

- **Filter by thread state first, and it is free.** 76.6%–86.9% of triggered windows are a parked
  pool thread, not unlabelled work. `Thread.getState()` is a field read and needs no handshake, so
  the cheap check goes first; without it the answer is a screen of `Unsafe.park` and nothing else is
  visible.
- **The same filter belongs in the report, independently of this feature.** 79.2% of *all*
  unlabelled observations are a non-runnable thread, which is why the duty cycle reads 56% and why
  coverage reads 49.8% where against runnable occupancy it is ~83%. That is item 10's per-thread
  split and it is now the more valuable of the two.

**What is left to decide before this becomes a phase.** The measurement says it is affordable and the
test says it works; neither says what the feature should be.

1. *Where the rate comes from.* On demand, as tested — one stack per unlabelled window over a tick,
   which self-limits to the rate of the phenomenon — with a global cap, because self-limiting is not
   the same as bounded. A low uniform trickle alongside it would characterise the diffuse remainder
   that never triggers, which is the difference between "you forgot something" and "you are fine".
   Whether that second mechanism earns its complexity is open.
2. *What to keep.* A whole trace is the honest thing to aggregate and the expensive thing to hold.
   One frame is not obviously the right one — the top frame is codec internals, and the frame that
   would have named the Lucene mistake was six deep. Probably: keep the whole trace, aggregate by
   the deepest frame belonging to a package the user named.
3. *How it reports.* The point is one sentence — *"of the time outside every label, the largest
   single place is X, at 31% of it"* — not a flame graph. A second table would undo the reason this
   design exists.
4. *Whether it stays off by default.* It is diagnostics about the placement, not measurement, and
   the measurement must never depend on it.

**The line it crosses, deliberately.** It makes the tool a stack profiler in a small way. The
argument for doing it anyway is that this is diagnostics *about the placement*, not the measurement:
no sampled stack ever enters a share, and the measurement stays an integer in a slot. Worth crossing
knowingly rather than by accident.

## 15. Make cross-checking a first-class feature, not a trial-only exercise · open

Both trials turned on comparing our labels against an independent measurement, and on Lucene the
comparison found *our* error rather than the other tool's. Every user placing labels in code they do
not own has the same exposure and none of the harness. Two thoughts, neither worked out: a documented
recipe (record JFR over the same window, collapse the stacks, compare inclusive shares against the
label shares) shipped as part of the README rather than as trial code; or a mode that records both
at once and prints them side by side, which is more use and considerably more work.

## 16. Per-operation concurrency, which we already collect and throw away · promoted

Each tick photographs every thread at one instant, so it already knows *how many* threads were
inside operation X at that moment. Today that is summed into occupancy and the distribution is
discarded. Kept instead, it gives per operation: *2.7 threads inside on average; one thread 18% of
the time, eight threads 4% of the time.*

**No stack profiler can produce this, and the reason is structural.** async-profiler, JFR and
everything else sample each thread on its own timer signal, so they never hold all threads at one
instant — the information is destroyed before the question can be asked. Our sampler walks every
slot in one pass, so it has it for free. [plan.md](plan.md) already says this about the
whole-application coefficient in phase 6; the per-operation version is the same observation one
level down and needs nothing phase 6 needs.

**What it costs:** one counter per operation per tick, on the sampler's own arrays — the same shape
as the long-instance detector's `prev*` arrays, and nothing on the hot path.

**And it is not a curiosity — it is half of the fix for occupancy not being elapsed time.** Recording
each thread's *state* splits an operation's occupancy into working and waiting, and that alone is not
enough: a hundred threads waiting one second and one thread waiting a hundred seconds are both a
hundred thread-seconds of waiting, with real costs a hundredfold apart. Concurrency is the divisor
that separates them — `elapsed = occupancy ÷ mean concurrency while active` — and on a lock holding
100 thread-seconds it is the difference between *6.7 seconds, break up the convoy* and *59 seconds,
design the contention out*. Same total, opposite fix. The derivation and its three surviving limits
are in [profiler.md](profiler.md#turning-occupancy-back-into-wall-time). So this item and the thread
state that phase 6 exists for are **halves of one feature**, and neither is much use shipped alone.

**What is unsettled:** whether it wants a full histogram or only the mean — the mean is all the
divisor above needs, and a histogram is what distinguishes *steadily three busy* from *sawtooth
spiking to sixteen*, which [plan.md](plan.md) phase 6 already wants for the whole application.

## 17. The report presents CPU as the truth and occupancy as the approximation · promoted, done

*A framing defect, not a measurement one. Nothing here is wrong; it reads as though the wrong
quantity is the goal.*

The duty line says *"threads were on CPU 18.8% of sampled wall time — at most 81.2 pp of any share
is occupancy that was not CPU"*, and the column footer says *"share is of labelled samples and is
occupancy, not CPU"*. Both read as an apology: CPU is the real number and occupancy is what we
managed to get.

That is backwards for most of what anyone profiles. Three quantities are in play and only the first
two are ever wanted:

| quantity | a 200 ms request that computes for 5 ms and waits for a database | answers |
|---|---|---|
| wall time | 200 ms | why is this slow for the user |
| CPU | 5 ms | why is the machine saturated |
| **occupancy — what we measure** | 200 ms | — |

Occupancy counts waiting *in full*, which is the right behaviour for the latency question. So the
reason the duty cycle exists is not that CPU is the goal. It is that **summed occupancy is only
additive when it is CPU**: a hundred threads parked one second on one lock is a hundred seconds of
occupancy and one second of real cost, because waiting is simultaneous and cycles are not.

So the line should say what it is actually guarding against — *this total may be counting the same
wait many times* — rather than implying a CPU measurement was the objective and was missed. Also
open: the same line's denominators are wrong, which is item 10, and the two are worth fixing in one
pass since they are the same two sentences of output.

## 18. The imbalance count is per process, and everything beside it is per session · open

*Noticed while writing the leak check for 1a, which had to expect the wrong number to pass.*

`Profiler.imbalances` is an `AtomicInteger` that is never reset, so a second session in the same
process reports the first session's leaks as well as its own. Every other number in a `Report` is
per session — deliberately, and at some cost: `callsAtStart` exists precisely because call counts
that spanned the warm-up were inflating implied durations by a tenth, and that mismatch was found
only because it made an upper bound come out *below* the truth, which is arithmetically impossible.

This one has no such tell. It fails quietly, in the direction of over-reporting, and only in a
process that profiles twice — which our harnesses do and a library's users certainly will.

The fix is the shape `callsAtStart` already uses: snapshot at `start()`, subtract at `stop()`.
`openAtEnd` is fine as it is, being a count of live slots rather than an accumulator. Not done with
1a because 1a was about which rung of the ladder a finding sits on, not about what a session is.

## 19. Narrow the duty bound where the state read is blind · open

*From Step 2. The bound is sound in every regime and useless in one, and the missing evidence may
already be collected.*

An event loop is off the CPU inside `epoll_wait` and reads `RUNNABLE`, so the state column cannot
see it and the duty bound has to assume all of it was inside the labels. On Netty that is 34.4% of
thread-time assumed against labels covering 13.9%, and the bound collapses. The report says so
rather than pretending, but "we cannot tell you" is not the end of the question.

**What might narrow it, cheapest first.**

1. **The long-execution detector already disagrees.** Netty's labelled operations sit at 0.00–0.01%
   of occupancy inside executions that outlived a tick, and `decode` reads 2.4 µs implied per call.
   An operation that never spans a tick cannot contain a millisecond of `epoll_wait`. That is not
   yet a bound — many short waits are not excluded — but it is evidence pointing the opposite way
   from the assumption, and the two are printed in the same report without ever being compared.
2. **Ask which labels are open when the thread is off the CPU.** The sampler knows the slot's
   current operation at every tick and it knows the thread state; what it cannot see is the
   *invisible* off-CPU, by definition. A cheap proxy: an operation whose implied per-call duration
   is far above its configured or expected size is where the waiting went. Requires an expectation,
   which the fine tier does not have for foreign code.
3. **Let the user label the wait.** The honest answer may be that the fix belongs to the placement,
   not to the instrument: a label around the selector loop turns invisible off-CPU into labelled
   occupancy, and the bound becomes tight for everything else. The report already recommends this.
   Whether the coarse tier makes it natural is a phase 4 question.

**The general shape of the problem** is that we have three instruments for off-CPU time — the CPU
clock (sees everything, per thread, no attribution), the state read (attributes per sample, blind to
native waits and preemption) and the long-execution detector (attributes per sample, floor of one
tick) — and the report presents them side by side without ever making them argue. See also item 15,
cross-checking as a first-class feature.

---

## Promoted to plan.md

**Phase 3.5** is item 9 above, reframed from detecting bad operations to bounding the error on every
share at once — the CPU duty cycle and the bound it implies, the long-instance detector, implied
per-call duration, and the bench work that gives all three a known truth to be checked against.

**Phase 4** already carries these, and they came out of the trial's friction list, recorded in full
in [trial.md](trial.md#6-the-friction--what-the-trial-says-about-the-tool):

- `Profiler.enter(id)` / `Profiler.exit()` and the span stack they imply, because `op(id) { }` is a
  block and almost nothing in foreign code is ours to wrap in a block.
- A balance check for non-lexical labels, and an imbalance count in the report — a leaked label is
  silent, plausible, and contaminates in the wrong direction.
- Folding zero-hit operations, with a count of what was folded.
- The counterfactual warning in the output.
- Documenting the id-caching pattern, which is not obvious and was rediscovered under pressure.
- The coarse tier's shape: per-instance spans with percentiles, and the cross-tabulation that reads
  *of the 48 ms median plan, 40% is `FilterIntoJoinRule`*.

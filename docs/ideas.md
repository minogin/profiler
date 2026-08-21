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

## 6. Leave the profiler on permanently · open

At ~2 ns per hook it may be cheap enough to ship enabled. The Calcite trial is evidence in favour:
on boundaries costing hundreds of microseconds the instrumentation was unmeasurable, and both A/B
comparisons came out with the wrong sign.

**What is missing.** A story for long-running processes — the report is currently start-to-stop
totals, which is the wrong shape for a server that has been up for a week. Windowing, resetting, or
periodic snapshots. Also carried in findings.md under open questions.

## 7. Correct the attribution bias using call counts · open

The sampler reads high on parents and low on short leaves. With call counts the correction is
arithmetic rather than a model, and the counts are already collected. Blocked on understanding the
bias well enough to know what to correct — see the open question in findings.md.

---

## Promoted to plan.md

Phase 4 already carries these, and they came out of the trial's friction list, recorded in full in
[trial.md](trial.md#6-the-friction--what-the-trial-says-about-the-tool):

- `Profiler.enter(id)` / `Profiler.exit()` and the span stack they imply, because `op(id) { }` is a
  block and almost nothing in foreign code is ours to wrap in a block.
- A balance check for non-lexical labels, and an imbalance count in the report — a leaked label is
  silent, plausible, and contaminates in the wrong direction.
- Folding zero-hit operations, with a count of what was folded.
- The counterfactual warning in the output.
- Documenting the id-caching pattern, which is not obvious and was rediscovered under pressure.
- The coarse tier's shape: per-instance spans with percentiles, and the cross-tabulation that reads
  *of the 48 ms median plan, 40% is `FilterIntoJoinRule`*.

# The case

Where existing tools fall short, and therefore what this one is for. Appended to whenever a real
profiling session shows a tool failing at something — the register is only worth keeping if it is
built out of observations rather than opinions.

**The rule for this file.** Every entry names the tool, the failure, and where it was seen. An
entry that is reasoning rather than measurement says so in its first line. There is a section at
the end for what the other tools do *better* than this one, and it is not optional — a register of
other people's weaknesses with no honest column is marketing, and would stop being useful to us
the moment we started believing it.

Numbers cited here are recorded in full in [findings.md](findings.md) and [trial.md](trial.md).

---

## Stack sampling, in general

Everything in this section applies to any profiler whose input is a periodic dump of thread stacks:
JFR's `jdk.ExecutionSample`, async-profiler, the IntelliJ profiler, and every flame graph made from
any of them. Observed on Apache Calcite 1.42.0 query planning, and — where an entry says so — on
Apache Lucene 10.4.0 search.

**Two instances of one class have the same stack, and no amount of reading it deeper will help.**
The Calcite entries below are about many *classes* behind one inherited method, which is at least
recoverable from the frames, if wrongly. The commoner case is worse. A Lucene `BooleanQuery` with
four `TermQuery` clauses on four different terms runs one `TermScorer` through one
`ImpactsDISI.advance` four times over; the identity that decides what to do about the query — *which
term* — is a field of an object, and no frame carries it. Measured by counting every collapsed stack
attributable to exactly one clause of an eight-clause query: **a flame graph identifies three of the
eight, covering 48.8% of samples, and 51.2% of samples contain no clause frame at all.** Our labels
separate all eight, and the four clauses sharing a class span 2.080% down to 0.163%. There is no
"read it more carefully" available here — Calcite's failure was a wrong answer, Lucene's is no
answer.

**It names methods, and the thing you want named is often not a method.** The largest meaningful
frame in the Calcite profile was `ConverterRule.onMatch` at 49.14% inclusive. `ConverterRule` is a
base class; about twenty distinct planner rules inherit that one method and do not override it. The
identity anyone would act on — *which rule* — is a property of the receiver object, and a stack
frame does not record the receiver. Labels split that 49.14% into 46.18% for one rule and ~3% for
the rest.

**The concrete class can appear one frame deeper, and that is worse than it not appearing at all.**
`ConverterRule.onMatch` calls `convert(rel)`, which each rule does implement — so the rule's name
*is* in the stack, and a careful reader can recover it. It is recoverable and wrong by 57×:
`EnumerableMergeJoinRule.convert` is visible in 0.81% of samples where the labels put the rule at
46.18%. The template method encloses the cheap half — `convert` builds one node, then `onMatch`
calls `transformTo` and the registration cascade under it is where the time goes, under a frame
naming only the base class. Of 14,330 samples under `ConverterRule.onMatch`, 96.87% name nothing
more specific.

**Worse still, it is inconsistent, and the profile does not say which case you are in.** Rules that
override `onMatch` themselves are enclosed correctly and the stack gets them right —
`JoinCommuteRule` reads 30.16% in the stacks against our 30.21%. So the same flame graph is accurate
for one rule and off by 57× for another, with no marker distinguishing them, because the difference
lives in a base class's internal structure. **The ranking inverts: the flame graph shows
`JoinCommuteRule` at 30% and merge join at 0.8%, and the truth is the other way round.** Not
"failed to answer" — answered confidently, and wrongly. Full tables in
[trial.md](trial.md#1-the-candidate-and-whether-it-qualifies).

**Self time points at the standard library.** Over 60% of Calcite's self time is
`ArrayList.indexOfRange`, `HashMap.putVal`, `String.equals`, `String.hashCode` and number
formatting. Every one of those attributions is correct and none of them is actionable — nobody is
going to fix `HashMap`. This is the normal outcome for any program whose cost is data structure
churn rather than arithmetic, which is most programs.

**Recursion destroys the inclusive view.** Calcite's planner recurses
(`registerImpl → fireRules → merge → fireRules → …`), so a dozen frames all read between 80% and
99% inclusive. Everything is responsible for everything. The view that is supposed to answer "which
activity is expensive" answers "all of them".

**There is a depth limit, and it discards the root end.** JFR's default is 64 frames; deeper stacks
are truncated from the *bottom*, so a method that was merely on the way in loses the sample
entirely — and truncation is biased towards exactly the deepest recursion, which is where the
interesting work is. Measured: 2.4% of stacks truncated, overwhelmingly inside one subsystem, and
raising the limit to 2048 moved that rule's share by 3.7 pp. An int in a thread-local slot has no
depth for recursion to exhaust.

**There are no call counts.** A stack profiler can tell you an operation took 10% of the time. It
cannot tell you whether that was 5,034 firings at 1.5 ms or 245,190 firings at 9 µs — opposite
problems with opposite fixes. Both of those are real rows from the Calcite profile, and the counts
came free, on the same cache line as the label.

**The achieved sampling rate is not reported, and is not the requested one.** JFR was asked for
1 ms and delivered 6.3 ms in one run and 13.7 ms in another, silently: 2,669 samples where ours
collected 39,360 over the same window. A share computed from an unknown number of samples has an
unknown error bar, and the reader is given no way to notice.

---

## The project this came from

*Reported from a year of work on it, not re-measured here.* The tool exists because of an in-memory
supply-chain traversal system — nanosecond operations repeated billions of times, heavily
coroutine-based. Two failures on that project are the origin of the whole design.

**IntelliJ IDEA's profiler was useless there, for two independent reasons.** The flame graph as a
form, for everything in the section above. And coroutines: work is spread across dispatcher threads
and every suspension cuts the trail, so the stack no longer corresponds to the logical task. Either
one alone would have been survivable. Together they left no usable reading.

**The only method that worked was switching logic off and re-measuring — and it is a bad method.**
This is worth stating carefully because it is the incumbent that actually gets used, and because it
is the honest competitor to any "share" a profiler prints:

- It is the only technique that gives a real answer to *what happens if I do not do this*, which is
  the question anyone actually has.
- **But disabling logic changes the workload.** On the supply-chain traversal, switching a piece of
  logic off *reduces the graph being traversed* — so the fast run is not the same run with one part
  removed, it is a different, smaller problem. The comparison measures two workloads, not two
  implementations, and the difference between them is not the thing you wanted to know.
- It is also serial, manual and slow: one hypothesis per rebuild, and every result contaminated the
  same way.

**The Calcite trial hit exactly this and did not notice at the time.** Removing
`EnumerableMergeJoinRule` gave 275× — but that rule shapes the search space, so the fast run
explored a *different and much smaller* plan space. The measured 275× is real as a wall-clock fact
and is not "the cost of the rule", for the same reason the supply-chain case is not. The finding
survives; the interpretation needed narrowing, and does not fully escape this.

So: the counterfactual is what people fall back on when profilers fail, and it has a structural
flaw that no amount of care removes. That is an argument for a profiler that can attribute without
removing anything — and a warning against building a naive "disable and re-run" feature and
presenting its number as clean. See [ideas.md](ideas.md) item 1.

## JFR custom events — the labelled alternative

*Reasoning and published cost figures, not our measurement. Item 2 in [ideas.md](ideas.md) is to
actually measure it.* This matters because it is the honest competitor: JFR can carry labels, and
saying otherwise would be an overclaim. What is enabled is `jdk.ExecutionSample`, one event type
out of roughly 150; a `jdk.jfr.Event` subclass with a `rule` field, begun and ended around each
firing, would have answered the Calcite question too.

**The cost per boundary is two to three orders of magnitude higher.** An event is an object
allocation plus a serialized record — call it 100 ns to 1 µs — against ~2 ns for an opaque int
store. On Calcite's 428 µs rule boundaries either is free. On the 20 ns operations the fine tier is
built for, it is not a contest, and this is the whole reason the design exists. Phase 8 in
[plan.md](plan.md) reached the same conclusion from the other direction — a JFR event *per
operation* is hopeless, one aggregated event per second is not — which is why JFR is a plausible
output format and not a plausible mechanism.

**It changes what it measures.** 78,756 event objects per plan is allocation pressure that was not
there before, in a workload whose cost is already dominated by allocation and GC. The int store
allocates nothing.

**It costs a correlation step.** Two independent event streams have to be joined offline by thread
and timestamp to answer "which label was open when this sample was taken". Ours answers that at
sample time because the label is already in the slot being read.

**It does not solve placement, and neither do we.** Both need somewhere to put the begin and the
end. See below.

## Timed wrappers — what a production search engine actually ships

*Measured, on Lucene. Full record in [trial-lucene.md](trial-lucene.md).* Elasticsearch answers
"which clause of this query cost the time" with a bespoke profiler: `ProfileWeight` and
`ProfileScorer` wrap every scorer and put `System.nanoTime()` on both sides of every call. That such
a thing had to be built at all is the strongest external evidence that the question is real and that
stack profilers do not answer it. It is also the closest competitor this project has, and on the one
workload where the two have been run side by side, **it names the wrong clause first.**

Same wrappers, same boundaries, same run length, only the instrument differing:

| | timed | sampled |
|---|---|---|
| phrase clause | **48.85%** | 42.66% |
| prefix clause | 40.70% | **48.49%** |

Six of the eight clauses agree to within a point. The two that matter are swapped.

**The cause is that the instrument charges per call, and calls are not evenly spread.** The phrase
clause makes 524.5 M calls, the prefix clause 24.6 M — 21× as many. Fitting a single free parameter,
a fixed cost per instrumented call, against all eight clauses reconciles them to an RMS of 0.09
percentage points (largest residual 0.18) at 21.50 ns per call. On that fit the instrument accounts
for **17.3% of the total time it reports**, essentially all of it landing on the clause it then
promotes to first place.

**And it is not cheap.** Against an uninstrumented baseline, interleaved with order swapped, n=480
per configuration: wrapping alone +3.84%, wrapping plus our label +6.54%, wrapping plus timing
**+35.36%**.

Two honest qualifications. This is one workload, and the failure needs a large spread in call counts
between operations — where all operations are called about equally often, per-call cost biases every
row alike and cancels out of the shares. And the reconciliation is a one-parameter fit, not an
independent measurement of `nanoTime`; what makes it evidence is that the model was stated before it
was fitted and that eight points fall on it.

The general form is worth stating, because it applies to us too: **an instrument whose cost is
proportional to call count, measuring quantities that are not, redistributes time towards whatever
is called most.** A sampled label is charged per *unit of time*, which is the same quantity it
reports, so it has no equivalent failure mode — that is the structural argument for sampling over
timing at this operation size, and it now has a number attached.

---

## What none of them can do, including this one

**You can only attribute to boundaries the host exposes.** Calcite offers exactly one hook, around
`rule.onMatch`. The generic costs — operand matching, the `RelSubset.contains` linear scan that is
24.6% of self time, memo registration, rule-match digest strings — are unreachable without forking.
Those are the costs a flame graph is *already* worst at, so this is where the two techniques fail
together rather than where one rescues the other.

**And more hooks does not mean better coverage.** Lucene exposes an extension point at every stage
of query execution, far more than Calcite did, and the fraction of time our labels could not reach
was *worse*: **47.8% of occupancy outside every label**, against Calcite's much smaller remainder.
The reason is not a missing hook — it is that a scored query's cost genuinely is about half
coordination between clauses, and coordination is not a clause. The limit is a property of the
workload's shape, not of how generous the library is, so no amount of extension points removes it.

**A hook that only observes cannot test its own finding.** Calcite's `RelOptListener` is notified
before and after a rule fires and has no way to say "skip this one". So the profiler can say an
operation is 46% of the time but cannot, by itself, find out what happens without it — which is the
question anyone actually has. See [ideas.md](ideas.md) item 1.

---

## Where the other tools are better

**They need no code changes at all.** async-profiler attaches to a running JVM and produces a
useful answer in thirty seconds on code nobody has ever read. Ours requires that somebody first
decided what the operations are, named them, and placed them. That is a large practical advantage
and it does not go away.

**They find what you did not think to look for.** A label can only report on a boundary someone
chose in advance. If the cost is somewhere nobody suspected, the flame graph will show it and the
labels will file it under whichever label happened to be set. The Calcite profile's JDK-heavy self
time was unactionable, but it was also *true*, and it is the kind of truth labels cannot produce.

**And the share they alone can speak for is not small.** On Lucene, **47.8% of occupancy was outside
every label** — `MaxScoreBulkScorer`'s coordination, the collector, the priority queue, weight
construction — despite Lucene exposing far more extension points than Calcite did. More hooks, worse
coverage, because a scored query's cost genuinely is about half coordination. On that workload the
labels explain where 52% of the time went and a flame graph is the only thing with anything to say
about the rest. Anyone reading our report as if it accounted for the whole run would be wrong by
half.

**A misplaced label is invisible; a wrong flame graph is at least a flame graph.** The first Lucene
placement labelled the scorer and not the factory that built it, and reported the clause that
mattered at 32.2% instead of 48.5%. Every share summed, every number was plausible, and nothing in
the report indicated a gap. It was found by disagreeing with JFR. A tool whose failures are silent
needs a second opinion in a way that a tool whose failures are visible does not.

**They work on code you cannot modify at all.** No hook, no source, no build — an agent-based
sampler still works. Our fine tier stops where the host's extension points stop.

**JFR is already there.** No dependency, no build change, shipped with the JVM, and its recordings
open in tooling everybody has. That is worth a great deal in a real incident.

**The two are complements, and the trial used them that way.** The flame graph said the cost was in
rule firing and the labels said which rule. Neither statement alone was the answer, and the
cross-check between them — agreement to about a percentage point on six rules — is the only reason
to believe either.

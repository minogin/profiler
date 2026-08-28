# The trial — the fine tier on somebody else's code

Everything before this was self-referential: we built a bench, built a tool, and proved the tool
reads the bench. This is the tool pointed at code nobody here wrote, to find out whether it is
*useful* rather than merely correct.

Target: **Apache Calcite 1.42.0**, query planning. The harness is in [`trial-calcite/`](../trial-calcite), and
every number below is reproducible from it. Findings that generalise beyond Calcite have been
folded into [findings.md](findings.md); this file is the trial's own record.

---

## 1. The candidate qualifies

**The workload.** A chain of *n* declared tables — a row type and a row count, no data anywhere —
joined key to key, planned by the Volcano planner with join commute, join associate, the
enumerable rules, and `AbstractConverter.ExpandConversionRule`. Planning never touches a row, so
the whole thing reproduces from one command with no database underneath it. That is what killed an
earlier attempt on DHIS2, where the pain needed a 700 GB database.

**It is genuinely slow, and the growth is the point.**

| tables | warm plan |
|---|---|
| 3 | 184 ms |
| 4 | 16–20 s |

A 108× step for one more table. Turning join associate off flattens it completely — 15–25 ms from
4 tables to 16, with no growth at all — so the explosion is join enumeration, not parsing or
validation. The 4-table configuration is the operating point for everything below.

**The qualifying test: does a conventional flame graph disappoint?** Profiled with JFR execution
samples (async-profiler has no Windows build; JFR's mechanism is the same periodic stack sampling
a flame graph is made of). 10,406 samples over 68.6 s:

| self time | share |
|---|---|
| `ArrayList.indexOfRange` | 24.56% |
| `HashMap.putVal` | 13.56% |
| `RelTraitSet.satisfies` | 8.87% |
| `VolcanoRuleCall.matchRecurse` | 4.85% |
| `DecimalDigits.uncheckedGetCharsLatin1` | 4.58% |
| `String.equals` | 3.40% |
| `RelTraitSet.findIndex` | 3.18% |
| `RelSubset.inputSubsets` | 3.01% |
| `HashMap.getNode` | 2.49% |
| `String.hashCode` | 2.42% |

Over 60% of the profile is JDK collections and string building. Correctly identified as hot,
telling you nothing about what the planner was doing.

The inclusive view is worse, because the call graph is one recursive spine —
`registerImpl → fireRules → merge → mergeWith → fireRules → …` — so a dozen frames all read
between 80% and 99% and none of them separates anything from anything.

And the frame that carries half the time, **`ConverterRule.onMatch` at 49.14%, is not a rule.** It
is the one method that twenty different enumerable conversion rules inherit. `EnumerableJoinRule`,
`EnumerableMergeJoinRule`, `EnumerableProjectRule` and the rest are distinct rule objects with
wildly different costs, and they share this implementation. This is the characteristic failure in
its purest form: **the identity the domain cares about is not the identity the call stack has.**

### The obvious objection, and what measuring it showed

A careful reader would push back: `ConverterRule.onMatch` calls `convert(rel)`, which each concrete
rule *does* implement. So the class name should appear one frame deeper, and the identity should be
recoverable from the collapsed stacks after all.

It is recoverable. It is also wrong by a factor of 57, and the profile gives no sign of it. Checked
against a dedicated 29,655-sample recording at `stackdepth=2048` (zero truncation), counting how
many samples name a concrete rule anywhere in the stack:

| | samples | share |
|---|---|---|
| under `ConverterRule.onMatch` | 14,330 | 48.32% of total |
| …of those, naming a concrete rule class | 448 | **3.13%** |
| …of those, naming nothing more specific | 13,882 | 96.87% |
| `EnumerableMergeJoinRule.convert` visible anywhere | 240 | **0.81% of total** |

Our labels put that rule at **46.18%**.

The reason is the template method. `ConverterRule.onMatch` does `convert(rel)` and then
`call.transformTo(converted)`, and the cost is overwhelmingly in `transformTo` — the registration
cascade that re-fires every rule against the new node. `convert` builds one node and returns. So
the subclass's own method encloses the cheap part of the firing and the expensive part happens
*outside* it, under a frame that names only the base class. The hottest single stack in the whole
recording, 13.65% by itself, is exactly that shape: `ConverterRule.onMatch → transformTo →
ensureRegistered → registerImpl → merge → mergeWith → fireRules → matchRecurse →
RelSubset.contains → ArrayList.indexOf`, with no rule class in it anywhere.

**And the same recording shows this is not uniform, which is the dangerous part.** Rules that
override `onMatch` themselves are enclosed correctly and the stack gets them right:

| rule | visible in stacks | our label |
|---|---|---|
| `JoinCommuteRule` (own `onMatch`) | 30.16% | 30.21% |
| `JoinAssociateRule` (own `onMatch`) | 4.21% | 3.80% |
| `EnumerableMergeJoinRule` (only `convert`) | **0.81%** | **46.18%** |

(Different runs of the same configuration, so the two columns carry run-to-run variation on top of
sampling error; the same-run comparison is in §2 and agrees to about a point. Nothing here turns on
tenths — the row that matters is off by 57×.)

So the flame graph does not merely fail to divide the 49.14%. **It ranks `JoinCommuteRule` at 30%
and merge join at 0.8%, and the answer is the other way round.** A reader with no other source
would act on the wrong rule, and nothing in the profile flags which rules are enclosed by their own
frame and which are not — that depends on a base class's internal structure.

Verdict: qualifies. Not because the flame graph is useless — it does hand you one lead, that
`RelSubset.contains` is a linear scan — but because it cannot answer the question anyone actually
has, which is *which rule*, and where it appears to answer it, it points the wrong way.

## 2. What the labels say

Labels were placed on two axes: the four phases of planning, at our own call site, and **the rule
instance**, through Calcite's `RelOptListener`, which is notified immediately before and after
`rule.onMatch(call)` runs. Same workload, 71,416 samples at an achieved 1.021 ms step:

| operation | share | calls | per firing |
|---|---|---|---|
| `rule:EnumerableMergeJoinRule` | **46.18%** | 78,756 | 428 µs |
| `rule:JoinCommuteRule` | 30.21% | 78,756 | 280 µs |
| `rule:FilterIntoJoinRule` | 10.44% | 5,034 | 1.51 ms |
| `rule:JoinAssociateRule` | 3.80% | 189,660 | 14.6 µs |
| `rule:ProjectJoinTransposeRule` | 3.12% | 245,190 | 9.1 µs |
| `phase:optimise` | 2.28% | 6 | — |
| `rule:EnumerableJoinRule` | 1.35% | 78,756 | 12.5 µs |
| everything else | < 1% each | | |

`phase:parse`, `phase:validate` and `phase:sqlToRel` together are under 0.11%. All the time is in
the search.

**The 49.14% the flame graph could not divide splits almost entirely onto one rule.** Merge join
alone is 46.18%; every other conversion rule together is 2.1%.

**The call counts turned out to matter as much as the shares.** `FilterIntoJoinRule` fires 5,034
times and costs 1.5 ms each; `ProjectJoinTransposeRule` fires 245,190 times and costs 9 µs each.
Those are opposite problems with opposite fixes, and a share alone — or a flame graph, which has
no counts at all — cannot tell them apart.

### The two methods agree

The same slow-configuration run, our shares against JFR's inclusive shares renormalised to samples
inside planning:

| rule | JFR | ours | gap |
|---|---|---|---|
| conversion rules (all) | 49.34% | 48.32% | 1.0 pp |
| `JoinCommuteRule` | 29.45% | 30.21% | 0.8 pp |
| `FilterIntoJoinRule` | 9.38% | 10.44% | 1.1 pp |
| `JoinAssociateRule` | 4.38% | 3.80% | 0.6 pp |
| `ProjectJoinTransposeRule` | 3.18% | 3.12% | 0.1 pp |
| `ProjectMergeRule` | 0.96% | 0.80% | 0.2 pp |

Two independent sampling mechanisms — a JVM stack walker and an unsynchronised read of a
thread-local int — within about a point of each other everywhere, on a workload neither was
designed against. Phase 3 proved the sampler against our own bench; this is the first time it has
been checked against something else, and it holds.

## 3. The finding

The labels name `EnumerableMergeJoinRule`. The experiment is to remove it. Interleaved, with the
order swapped every round:

| configuration | mean | min | max |
|---|---|---|---|
| with merge join | 17,827.6 ms | 16,236.2 ms | 18,926.9 ms |
| without merge join | **64.7 ms** | 59.0 ms | 75.7 ms |

**275× faster.** And the plan you get is the same plan, near enough to make no difference:

| | estimated cumulative cost |
|---|---|
| with merge join | 1.21680 × 10¹¹ rows |
| without merge join | 1.21684 × 10¹¹ rows |

**0.0026% worse.** Nineteen seconds of planning buys three parts in a hundred thousand.

The mechanism, once you know where to look: merge join is the only rule that demands a *collation*
trait. Demanding it multiplies the number of `RelSubset`s — one per distinct trait set — and every
subset multiplies matching, registration and memo merging for every other rule. The rule does not
merely cost its own 46%; it inflates everyone else's.

**Which is the caveat, and it belongs in the tool's output.** A share of 46% became a 275×
speedup. Shares answer *where the time went*, not *what would happen if this were removed*, and
those differ by two orders of magnitude when an operation creates work for its neighbours. The
report must not be read counterfactually, and right now nothing in it says so.

**Cross-check.** Profiling the fast configuration puts `FilterIntoJoinRule` at 39.7% — a
completely different profile, the merge-join cascade gone, and `ExpandConversionRule` down from
7,674 firings to 665. Consistent with the mechanism above.

### How far this finding actually goes

Less far than the numbers above make it sound, and the limit is worth stating plainly because the
shape of the conclusion — *the program is slow, so delete the expensive feature* — is exactly the
one a share-ranked report invites and exactly the one that is usually wrong.

**The workload has no data.** No rows, no indexes, no pre-existing sort order, because the trial
needed planning cost isolated from execution. But exploiting input that is *already sorted* is the
entire reason merge join exists. In a workload where nothing is ever sorted the rule cannot pay off
under any circumstances, so removing it gives up nothing that was ever available. That is not a
fair fight, and "275× for 0.0026%" reads as a stronger claim about Calcite than the experiment
supports.

**The quality check is weaker than it looks, too.** The 0.0026% is Calcite's own cost model applied
to row counts we invented, on tables containing nothing. It is an estimate about fictional data,
not evidence that the plan is as good.

**What does survive, and it is the general part:** a rule that introduces a new *trait requirement*
multiplies the number of plan alternatives, so its cost is superlinear and it inflates every other
rule's work as well as its own. That is why a 46% share became a 275× speedup, and it does not
depend on the workload being synthetic.

**And the trial's own question is unaffected.** It was never "should Calcite drop merge join" — it
was whether the tool can name a culprit the flame graph cannot, and whether that naming is causal.
It named one the flame graph showed at 0.8% — sixth among the rule classes visible at all — and
removing it changed the runtime by 275×. The
domain claim should be stated narrowly; the tool claim stands.

## 4. Where the two methods disagree, and it is not noise

On the fast configuration, both profilers running in the *same* JVM over the same 40 s:

| rule | JFR (64 frames) | JFR (2048 frames) | ours |
|---|---|---|---|
| `FilterIntoJoinRule` | 27.87% | 31.55% | **39.74%** |
| `JoinCommuteRule` | 14.70% | 13.56% | 12.79% |
| `ProjectMergeRule` | 11.34% | 10.38% | 9.26% |
| `ProjectJoinTransposeRule` | 9.50% | 9.18% | 8.32% |
| `JoinAssociateRule` | 5.20% | 4.80% | 4.38% |

Everything is within 2.5 pp except `FilterIntoJoinRule`, which differs by 12 pp — eleven times its
noise floor of 1.1 pp, in a single shared run, so neither drift nor chance.

**Part of it is stack truncation, measured.** JFR's default depth limit is 64 frames and 2.4% of
stacks hit it. When a stack is truncated it is the *root* end that is lost, so a method that was
merely on the way in loses the sample entirely. The truncated stacks are dominated by
`RexSimplify` frames — which is precisely what `FilterIntoJoinRule` does when it simplifies a
predicate it is pushing into a join. Raising the limit to 2048 removed all truncation and moved
`FilterIntoJoinRule` up by 3.7 pp, in the direction predicted.

A label in a thread-local slot has no depth at all, so this class of error cannot happen to it.

**The remaining ~8 pp is not explained.** Ruled out: a leaked label. Calcite's "after"
notification is *not* inside a `finally`, so a rule that threw would leave our label set and bill
every later sample to it — checked explicitly, the span stack was balanced after all 484 plans.
Remaining suspicion is sampling bias, since C2 strips safepoint polls from counted loops and
`RexSimplify` is full of them, but that is a hypothesis with no measurement behind it and it stays
an open question.

**Worth recording separately: JFR did not deliver the sampling rate it was asked for.** Requested
1 ms; achieved 6.3 ms on one run and 13.7 ms on another. Ours achieved 1.015–1.021 ms and *says
so* in its own output. Over the same 40 s window that is 39,360 samples against 2,669 inside
planning — a 15× difference in evidence.

**And the sampler is visible from outside.** In the combined run, `Sampler.waitUntil` accounts for
55.27% of all JFR execution samples: our spinning sampler is a whole core, exactly as phase 2 said
it would be. On a single-threaded target that is a 2× CPU footprint for the process, which is a
much less comfortable trade than it looks on a 16-thread bench.

## 5. What the instrumentation cost

Two comparisons, interleaved with the order swapped every round, on the fast configuration where
there are enough repeats to say anything (40 rounds each):

| comparison | by mean |
|---|---|
| listener attached but doing nothing, vs nothing | 0.974× |
| labels, vs listener attached but doing nothing | 0.965× |

Both have the wrong sign — the instrumented configuration measures as *faster* — which is how this
machine says "below the floor". On the slow configuration, 6 rounds: 1.021× by mean, with three
rounds faster and three slower.

That is the expected answer rather than a disappointing one. The labels sit on boundaries that
cost hundreds of microseconds; a 2 ns hook on a 428 µs operation is five parts per million. The
bench's hook cost mattered because the bench labels 20 ns operations.

**One methodological catch, and it cost a wrong answer before it was caught.** With a fixed
A-then-B order every round, the labelled configuration came out 6.6% *faster* than the plain one,
consistently, in all four rounds — interleaving alone does not remove a bias that depends on
*position* within the round. Swapping the order every round removed it. This is the fourth time in
this project that a sequential comparison has aliased something onto the effect, and the first
time interleaving was not enough on its own.

## 6. The friction — what the trial says about the tool

This is the deliverable that gets dropped when the finding goes well, so it goes first among the
things to carry into phase 4.

> **Since acted on, in phase 3.75.** Items 1 and 2 are built — `Profiler.enter` / `exit` /
> `expectBalanced`, and the report counts leaks two ways. The fifteen-line `Span` helper below no
> longer exists: `RuleLabeller` calls the library, and the plan-boundary check is one line. Items 6
> and 7 (fold empty operations; the counterfactual warning) are in the report. Items 3, 4 and 5
> remain as recorded — they are properties of what Calcite exposes, not of our API.

**1. There is no enter/exit hook, and lexical placement is the exception in somebody else's code.**
The documented surface is `op(id) { }`, a block. Nothing in Calcite is ours to wrap in a block.
What Calcite offers is two callbacks, before and after. The trial had to write a fifteen-line
`Span` helper against `Profiler.slot()` and the opaque accessors — public, but never presented as
the way to place a label. **`Profiler.enter(id)` / `Profiler.exit()` belong in the library**, with
the stack they imply, and the coarse tier will need exactly the same thing.

**2. Non-lexical placement can leak, silently, in the contaminating direction.** Calcite's "after"
notification is not in a `finally`. If a rule throws, the label stays set and every subsequent
sample is billed to it — no error, no warning, just a wrong number that looks fine. The trial
added its own balance check. **The library should offer one**: a way to assert the span stack is
empty at a known quiet point, and a count of imbalances in the report.

**3. The placement mechanism has a cost of its own, separate from the hook.** Attaching *any*
`RelOptListener` makes Calcite allocate two event objects per rule firing, whether the listener
does anything or not. That cost belongs to the mechanism, not to the profiler, and pricing them
together would have blamed our hook for somebody else's allocation. Any future annotation or agent
surface has the same property and needs the same three-way measurement.

**4. You can only label where the library lets you in.** Calcite exposes exactly one boundary —
around `rule.onMatch`. Everything else in Volcano is unreachable without forking: operand matching
(`matchRecurse`, and the `RelSubset.contains` linear scan that is 24.6% of self time), memo
registration, set merging, and the rule-match digest strings (`computeDigest`, 6.9%). Those are the
*generic* costs, and they are the ones the flame graph is already bad at. So the honest scope of
the fine tier on a third-party library is: **it can attribute time to the domain concepts the
library exposes a hook for, and nothing else.** For Calcite that happened to be enough, because
the rule is the unit anyone would act on. It will not always be.

**5. The report needs a counterfactual warning.** See §3. A 46% share and a 275× speedup are both
true and they are not the same claim.

**6. Zero-hit operations swamp the output.** Forty-one rules were registered, twenty-two never
fired. Half the report is rows of zeros. A fold, or a minimum-share cutoff with a count of what
was folded, and the count matters — silently truncating is what the whole design keeps guarding
against.

**7. Call counts were the most valuable column, and were nearly not there.** See §2. They are what
separates "expensive per firing" from "fired constantly", and no stack profiler has them.

**8. The coarse tier has an obvious shape here, and the trial wanted it.** One plan is a coarse
operation. Per-plan duration varied from 20 ms to 110 ms in the fast configuration and from 7.8 s
to 19.3 s in the slow one, for byte-identical work — a 2.5× spread that nothing in the current
output explains, and precisely what per-instance spans with percentiles are for. The
cross-tabulation would then read: *of the 48 ms median plan, 40% is `FilterIntoJoinRule`*. That is
the sentence the trial wanted to write and could not.

**9. Registration by name worked, unchanged, with no ceremony.** Forty-one operations resolved
once into an identity map keyed by the rule object, because a name lookup per firing would have
cost many times the hook it feeds. Worth saying plainly: the id-caching pattern is not obvious and
should be in the documentation, not discovered.

## 7. Reproducing it

```
./gradlew :trial-calcite:classpathFile
CP=$(cat trial-calcite/build/classpath.txt)

# the growth curve
java -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt --scale --from 3 --to 12 --associate true

# the conventional profile
java -Xmx6g -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt \
     --tables 4 --associate true --warmups 1 --seconds 60 --jfr calcite.jfr
java -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt --analyze calcite.jfr --top 25

# ours
java -Xmx6g -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt \
     --tables 4 --associate true --warmups 1 --seconds 60 --labels true --sampler true

# the finding, and the plans it produces
java -Xmx6g -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt --ab merge --tables 4 --associate true
java -Xmx6g -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt --plan --tables 4 --associate true

# what the instrumentation costs
java -Xmx6g -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt \
     --ab listener --tables 4 --associate true --mergejoin false --rounds 40
java -Xmx6g -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt \
     --ab labels --tables 4 --associate true --mergejoin false --rounds 40
```

Machine: Intel Core Ultra 7 255H, 16 cores, JDK 26, Windows 11. The library itself targets JDK 21. The same caveats as the bench
apply — the clock swings by 2× inside a run and heat soak accumulates across runs.

### Every flag

`--associate` and `--labels`/`--sampler` are opt-*in* here (`--associate true`), unlike the bench,
because the interesting runs are the instrumented ones and the default is the control.

| flag | default | what it does |
|---|---|---|
| `--tables=N` | 10, or 4 in an A/B | tables in the generated join query — the knob that sets planning cost |
| `--associate=true` | off | let the planner reassociate joins, which is what makes the search space large |
| `--seconds=N` | 20 | length of the measured run |
| `--warmups=N` | 3 | plans before measurement starts |
| `--labels=true` | off | place the `RelOptListener` labels |
| `--sampler=true` | off | run the sampler and print the report |
| `--step=MS` | 1.0 | sampling interval |
| `--mergejoin=false` | on | remove `EnumerableMergeJoinRule` — the 275× finding |
| `--jfr=PATH` | none | also record JFR execution samples |
| `--scale --from=N --to=N --cap=MS` | 3, 14, 20000 | the growth curve: plan at each table count until one exceeds the cap |
| `--ab=labels\|tool\|listener\|merge --rounds=N` | 5 rounds | interleaved A/B. `listener` prices attaching a bare `RelOptListener`, which allocates two events per rule firing whether it does anything or not |
| `--plan` | — | print the plan the search produces |
| `--analyze=PATH --top=N --collapsed=PATH` | 25 | read a JFR recording back and rank it, optionally writing collapsed stacks |

---

## Revisited: the coarse tier, on the trial that gave it its shape

[plan.md](plan.md) records *"one plan is a coarse operation"* as where the tier's shape came from.
Until now nothing here measured one. `--coarse true` adds two labels — `plan` around `planOnce()`
and `optimise` nested inside it — beside the fine phase and rule labels that were already there.

```
coarse operation            executions       mean        p50        p90        p99        max  busy/exec  waiting   in flight
plan                             2,751    9.08 ms    7.34 ms   14.68 ms   37.75 ms  100.54 ms    9.08 ms     0.0%      1.00/1
optimise                         2,751    7.73 ms    6.29 ms   12.58 ms   29.36 ms   71.69 ms    7.71 ms     0.0%      1.00/1
  plan was: rule:FilterIntoJoinRule 25.2%, rule:JoinCommuteRule 11.1%, rule:EnumerableSortRule 8.1%,
            rule:EnumerableJoinRule 7.9%, phase:sqlToRel 6.9%, rule:EnumerableMergeJoinRule 6.1%, and 13 more
```

**That second line is the sentence the whole tier was justified by, on code nobody here wrote.**
*Of the 9.08 ms a plan takes, a quarter is `FilterIntoJoinRule`.* Neither tier says it alone: the
fine labels give shares of thread-time with no duration attached, and a span gives a duration with
no idea what filled it.

**The percentiles are what a fine label can never produce.** A mean of 9.08 ms with a p99 of 37.75 ms
and a worst plan of 100.54 ms is a distribution, and planning latency has a long tail — which is a
fact about Calcite that twenty-five seconds of sampling could not otherwise have stated.

### Checked against a stopwatch this harness already had

The loop has bracketed `planOnce()` with two `nanoTime` calls since before the coarse tier existed,
to report *"N plans in T s"*. That makes it a truth rather than a plausibility check, and on
**foreign code**, which the bench cannot be:

| plan | profiler | harness | diff | harness, exact |
|---|---|---|---|---|
| count | 2,751 | 2,751 | **+0.00%** | |
| mean | 9.08 ms | 9.08 ms | −0.05% | 9.08 ms |
| p50 | 7.34 ms | 7.34 ms | **+0.00%** | 6.83 ms |
| p90 | 14.68 ms | 14.68 ms | **+0.00%** | 14.66 ms |
| p99 | 37.75 ms | 37.75 ms | **+0.00%** | 34.92 ms |

Both sides go through the same histogram, deliberately — the exact column is printed beside them so
the quantisation is visible, and it is not what is being checked. What is being checked is whether
the profiler recorded *the same intervals the harness timed*, and it did, to the last bucket.

`waiting 0.0%` and `in flight 1.00/1` are both right: planning is single-threaded and never blocks.

### What it found

**The report tells you to add a coarse label you have already added.** The long-instance detector
says *"`phase:optimise` … wants a coarse label for its per-execution statistics"* in the very run
that is measuring `optimise`'s span at 7.73 ms with percentiles. The two tiers have separate id
spaces and independent names — Calcite registers `phase:optimise` and `optimise` — so nothing
connects them. [ideas.md](ideas.md) item 21, with three candidate fixes.

**`:trial-calcite:run` had been broken and nobody noticed**, because the documented way to launch this
trial is `java -cp` and the Gradle `mainClass` pointed at a package the code is not in. Fixed.

| flag | default | what it does |
|---|---|---|
| `--coarse true` | off | label `plan` and `optimise` in the coarse tier as well, and check the spans against the loop's own timing |

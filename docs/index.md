# The documents

Each one has a job, and nothing is written in two places. Start with [tldr.md](tldr.md) if you have
five minutes, or with [profiler.md](profiler.md) if you want the design.

**Where we are right now.** The fine tier is built, verified and **released as v0.1.0** — Apache-2.0,
one dependency, Java 21+. Verified on our own bench and on **three** foreign codebases: Apache
Calcite, Apache Lucene, and Netty. Phases 1, 2, 3, 3.5 and 3.75 are done; phase 6 is half done —
thread state and per-operation concurrency exist, the whole-application coefficient does not; phase 7
is far enough for a 0.x but has no annotations and no agent.

Since the trials: the three small fixes and the pause after them are **done**, and the pause earned
its place — it caught the new error bound being sound and useless on a thread pool. There is a test
suite now (63 tests, ~3 s) whose expectations are the numbers in findings.md, two code reviews have
been through every file, and `D₂` — the one outright defect the design carried — is fixed.

**Phase 4 — the coarse tier — is done**, same-thread, and checked on all three trials. Contexts,
measured spans with percentiles, the `(fine, coarse)` cross-tabulation, `mean − busy/exec` as the
waiting quantified, and a floor check for the tier boundary that is *exact* where the fine one has to
infer. Verified against truths that are identities rather than estimates: the bench times every one
of its own requests, the Calcite harness has timed every plan since before this tier existed, and in
both cases p50, p90 and p99 agree to **+0.00%**.

**What to do next is [plan.md § Phase 5](plan.md#phase-5--crossing-threads--next-and-the-trials-said-why)**,
which opens with a *"start here"* section written for a session picking this up cold. The short
version: Lucene fans a search across a pool, so a context stays on the calling thread and the work on
the others is reported as **waiting** — 0.0% at one thread against 24.5% at eight, same code, same
label. Propagation fixes that and makes per-operation parallelism a real number rather than 1.0.

**Two things worth knowing before reading anything else**, because they govern the rest:

- **The tier boundary is not a size range.** It comes from what the instrumentation costs, and it
  lands at *an operation under ~1 µs cannot be coarse*. Neither tier has an upper limit.
  [profiler.md](profiler.md#where-the-boundary-is).
- **How accurate this has to be**, which decides what is worth building: an error matters only if it
  can move the ranking or point at the wrong operation, and random noise and uniform bias can do
  neither. [profiler.md](profiler.md#how-accurate-this-has-to-be-and-where-that-budget-goes).

| document | what belongs in it | read it when |
|---|---|---|
| [tldr.md](tldr.md) | the whole project in plain words, no jargon | you want to know what this is |
| [output.md](output.md) | how to read the report — every column, every warning, and what to do about each | you have run it and want to know what you are looking at |
| [profiler.md](profiler.md) | the design and the prior art — and the **tier boundary**: which operations can be coarse, what the fine tier measures, and where it breaks | you want to know how it works and why it is shaped this way |
| [plan.md](plan.md) | work we committed to, phase by phase, rewritten as each closes to say what was actually built | you want to know where we are |
| [findings.md](findings.md) | only things we measured, each with the measurement that produced it | you want to know what is true, and why we believe it |
| [case.md](case.md) | observations of existing tools failing at something, and where they are better than us | you want to know why this exists at all |
| [trial-calcite.md](trial-calcite.md) |  Apache Calcite — the first end-to-end result on foreign code, and where the coarse tier was checked against a stopwatch the harness already had | you want the first end-to-end result on code we did not write |
| [trial-lucene.md](trial-lucene.md) | the same, Apache Lucene — concurrent, the first head-to-head against a timed-wrapper profiler, and where the coarse tier meets the edge of its own tier | you want the second, and the strongest argument for sampling we have |
| [trial-netty.md](trial-netty.md) | the same, Netty — event loops, the first test of the thread-state column on foreign code, and the negative control for the coarse tier's waiting column | you want the third, and what a flame graph does with a handler chain |
| [ideas.md](ideas.md) | things worth doing that we have not committed to | you want to know what was considered and deferred |
| [../README.md](../README.md) | how to use it and how to run the bench | you want to run something |

## The rules that keep them apart

- **findings.md takes no hunches.** A claim without a number is an open question, and it goes in the
  "Open questions" section at the end rather than in the body.
- **case.md is built from observations, not opinions.** Every entry names the tool, what it failed
  at, and where that was seen. It keeps an honest section on where the other tools are better.
- **ideas.md is written before an idea evaporates**, untested and marked as such. When one is taken
  up it is marked *promoted* and the commitment moves to plan.md.
- **plan.md carries no results**, only what we are doing and what "done" means. Results go to
  findings.md and get linked from here.

## Where the code is

| | |
|---|---|
| **`com/minogin/profiler/`** | **the library — everything a user imports, and nothing else** |
| `Profiler.kt` | the slot, the registry, `op { }` / `enter` / `exit`, and the constants that bound them |
| `Sampler.kt` | the sampling thread: the tick loop, the slot walk, the wait strategy |
| `Coarse.kt` | the coarse tier: the context, the span histogram, and `coarse { }` / `enterCoarse` / `exitCoarse` |
| `Report.kt` | what a session collected and how it is rendered, including every verdict and threshold |
| `Duty.kt` | how much of the occupancy was CPU, and the bound that puts on every share |
| **`com/minogin/profiler/bench/`** | **the harness — never shipped, and now unable to leak into the above** |
| `Workload.kt`, `Burn.kt`, `Bench.kt` | a workload whose true answer is known |
| `Main.kt` | the modes, the run, and the bench's own tolerance table |
| `Verify.kt` | the sampler against the truth, and the observer effect |
| `VerifyCoarse.kt` | the coarse tier against the truth: counts, spans against the workers own stopwatches, the cross-tabulation, and parallelism |
| `Print.kt` | every report the bench prints about itself |
| `StackCost.kt` | what a cross-thread stack walk costs |
| **`src/test/kotlin/`** | **the arithmetic, checked without starting anything — `./gradlew test`, ~3 s** |
| `DutyBoundTest.kt` | the bound on every share: four properties, and the five regimes the trials measured |
| `FloorCheckTest.kt` | who gets named as below the floor, including both sides of the 0.35 ns boundary |
| `RegistryTest.kt` | slots, nesting, the balance check, and the one fatal condition in both directions |
| `ReportTest.kt` | shares, coverage, the long-execution floor, and what the report promises to print |
| `CoarseTest.kt` | the histogram brackets what it is given, nesting restores the parent, a leaked context is caught, and the tier boundary from the coarse side |
| `trial-calcite/` | the Calcite trial, in its own module so its dependencies cannot leak into the profiler |
| `trial-lucene/` | the Lucene trial — the corpus, the wrappers that place the labels, and four instrumentation configurations to compare |
| `trial-netty/` | the Netty trial — the HTTP pipeline, the load generator, and the three-way A/B |
| `trial-common/` | shared by every trial and depending on nothing but the JDK: the JFR recording and the collapsed-stack analysis |

## Running the three trials

Each is a separate Gradle module, so nothing a trial needs — Calcite, Lucene, Netty, a logging
binding — can reach the profiler, which depends on nothing but the Kotlin stdlib.

```bash
# the bench — the workload whose true answer is known
# `:run`, with the colon. Plain `run` matches the task in every module, so it runs the Calcite and
# Lucene trials too — with their own defaults, and with no argument you passed.
./gradlew :run --args="--seconds=20"
./gradlew :run --args="--seconds=20 --lock=2000,10"    # with injected blocking
./gradlew :run --args="--seconds=15 --threads=32 --oversubscribe"
./gradlew :run --args="--leakcheck"                    # the one fatal condition, staged on purpose

# Netty — qualification, the labelled run, and the A/B
./gradlew :trial-netty:classpathFile
CP=$(cat trial-netty/build/classpath.txt)
java -cp "$CP" com.minogin.profiler.trial.netty.NettyTrialKt --qualify --seconds=45
java -cp "$CP" com.minogin.profiler.trial.netty.NettyTrialKt --labels  --seconds=45
java -cp "$CP" com.minogin.profiler.trial.netty.NettyTrialKt --ab --rounds=8 --seconds=5

# Calcite — the growth curve, then the labelled run
./gradlew :trial-calcite:classpathFile
CP=$(cat trial-calcite/build/classpath.txt)
java -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt --scale --from 3 --to 12 --associate true
java -Xmx6g -cp "$CP" com.minogin.profiler.trial.calcite.CalciteTrialKt \n     --tables 4 --associate true --warmups 1 --seconds 60 --labels true --sampler true

# Lucene — the index is already built under trial-lucene/index
./gradlew :trial-lucene:classpathFile
java -cp "$(cat trial-lucene/build/classpath.txt)" \
     com.minogin.profiler.trial.lucene.LuceneTrialKt --placement LABEL --seconds 45
```

**Runs on this laptop are not repeatable to better than about 60%.** Throughput falls 2.2× between
a two-second run and a forty-second one, and an A/B that rebuilds anything between arms is dominated
by the rebuilding rather than by the effect — see
[findings.md](findings.md#measurement-technique). Any comparison has to be interleaved, ABBA, and
against something that does not get torn down.

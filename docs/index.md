# The documents

Each one has a job, and nothing is written in two places. Start with [tldr.md](tldr.md) if you have
five minutes, or with [profiler.md](profiler.md) if you want the design.

**Where we are right now.** The fine tier is built and verified, on our own bench and on **three**
foreign codebases: Apache Calcite, Apache Lucene, and Netty. Phases 1, 2, 3, 3.5 and 3.75 are done;
phase 6 is half done — thread state and per-operation concurrency exist, the whole-application
coefficient does not.

**What to do next is [plan.md § What happens next](plan.md#what-happens-next-in-order)**, and it is
three small fixes, a look at what they turn up, and then the coarse tier. The three are not
tidying — each is a number the report gets wrong today, and each is now backed by measurement.

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
| [profiler.md](profiler.md) | the design and the prior art — and the **tier boundary**: which operations can be coarse, what the fine tier measures, and where it breaks | you want to know how it works and why it is shaped this way |
| [plan.md](plan.md) | work we committed to, phase by phase, rewritten as each closes to say what was actually built | you want to know where we are |
| [findings.md](findings.md) | only things we measured, each with the measurement that produced it | you want to know what is true, and why we believe it |
| [case.md](case.md) | observations of existing tools failing at something, and where they are better than us | you want to know why this exists at all |
| [trial-calcite.md](trial-calcite.md) | the fine tier pointed at Apache Calcite — every number from that exercise | you want the first end-to-end result on code we did not write |
| [trial-lucene.md](trial-lucene.md) | the same, pointed at Apache Lucene — concurrent, and the first head-to-head against a timed-wrapper profiler | you want the second, and the strongest argument for sampling we have |
| [trial-netty.md](trial-netty.md) | the same pointed at Netty — event loops, and the first test of the thread-state column on foreign code | you want the third, and what a flame graph does with a handler chain |
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
| `Report.kt` | what a session collected and how it is rendered, including every verdict and threshold |
| `Duty.kt` | how much of the occupancy was CPU, and the bound that puts on every share |
| **`com/minogin/profiler/bench/`** | **the harness — never shipped, and now unable to leak into the above** |
| `Workload.kt`, `Burn.kt`, `Bench.kt` | a workload whose true answer is known |
| `Main.kt` | the modes, the run, and the bench's own tolerance table |
| `Verify.kt` | the sampler against the truth, and the observer effect |
| `Print.kt` | every report the bench prints about itself |
| `StackCost.kt` | what a cross-thread stack walk costs |
| `trial-calcite/` | the Calcite trial, in its own module so its dependencies cannot leak into the profiler |
| `trial-lucene/` | the Lucene trial — the corpus, the wrappers that place the labels, and four instrumentation configurations to compare |
| `trial-netty/` | the Netty trial — the HTTP pipeline, the load generator, and the three-way A/B |
| `trial-common/` | shared by every trial and depending on nothing but the JDK: the JFR recording and the collapsed-stack analysis |

## Running the three trials

Each is a separate Gradle module and none of them can reach the profiler's own dependencies,
because the profiler has none.

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

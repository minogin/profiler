# The documents

Each one has a job, and nothing is written in two places. Start with [tldr.md](tldr.md) if you have
five minutes, or with [profiler.md](profiler.md) if you want the design.

**Where we are right now:** phases 1, 2, 3, 3.5 and 3.75 are done, and so are two trials — Apache
Calcite, and Apache Lucene, which was the first concurrent workload we did not write. Next are the
**remaining trials**, and [plan.md](plan.md#trials-3--the-rest-of-the-list--next) carries the list —
Netty, a compiler, and two deliberate negative controls — with the criteria and the six steps the
two trials taught us about running one.

| document | what belongs in it | read it when |
|---|---|---|
| [tldr.md](tldr.md) | the whole project in plain words, no jargon | you want to know what this is |
| [profiler.md](profiler.md) | the design and the prior art | you want to know how it works and why it is shaped this way |
| [plan.md](plan.md) | work we committed to, phase by phase, rewritten as each closes to say what was actually built | you want to know where we are |
| [findings.md](findings.md) | only things we measured, each with the measurement that produced it | you want to know what is true, and why we believe it |
| [case.md](case.md) | observations of existing tools failing at something, and where they are better than us | you want to know why this exists at all |
| [trial.md](trial.md) | the fine tier pointed at Apache Calcite — every number from that exercise | you want the first end-to-end result on code we did not write |
| [trial-lucene.md](trial-lucene.md) | the same, pointed at Apache Lucene — concurrent, and the first head-to-head against a timed-wrapper profiler | you want the second, and the strongest argument for sampling we have |
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
| `src/main/kotlin/com/minogin/profiler/Sampler.kt` | the fine tier: slots, the hook, the sampling thread, the report |
| `src/main/kotlin/com/minogin/profiler/Duty.kt` | how much of the sampled occupancy was CPU, and the bound that puts on every share |
| `src/main/kotlin/com/minogin/profiler/Workload.kt`, `Burn.kt`, `Bench.kt` | the bench — a workload whose true answer is known |
| `src/main/kotlin/com/minogin/profiler/Main.kt` | the harness that runs it all and checks every claim |
| `trial/` | the Calcite trial, in its own module so its dependencies cannot leak into the profiler |
| `trial-lucene/` | the Lucene trial — the corpus, the wrappers that place the labels, and four instrumentation configurations to compare |
| `trial-common/` | shared by both trials and depending on nothing but the JDK: the JFR recording and the collapsed-stack analysis |

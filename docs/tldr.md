# TL;DR

The whole project in plain words. Everything here is said properly somewhere else — the map is
[index.md](index.md).

## What problem

You have code where the same few operations run billions of times, each taking tens of nanoseconds.
Something is slow. You want to know *which operation* the time went to.

Ordinary profilers answer with **method names from the call stack**, and that is often the wrong
answer. A real example, measured: in Apache Calcite's query planner, twenty different optimisation
rules all run through one inherited method. A flame graph shows that method holding half the time
and cannot say which of the twenty rules it was. Worse, it ranks the wrong one first — the frame it
does show for the expensive rule reads 0.81% where the truth is 46%, because the shared parent
method does the expensive part *after* calling the subclass. Same recording, accurate for one rule
and wrong by 57× for another, with nothing to tell you which is which.

## What we build instead

You put a **label** on the operation you care about — the thing your domain calls an operation, not
the thing the stack calls a method:

```kotlin
val probe = Profiler.register("hashProbe")   // once, at startup

op(probe) { table.find(key) }                // at the call site
```

When the boundary is not a block — a listener, a before/after callback — there is
`Profiler.enter(id)` / `exit()` instead, plus `expectBalanced()`, because that form has no `finally`
and a label left open bills everything after it to the wrong operation.

That writes one integer into a thread-local slot. A separate thread wakes up every millisecond,
reads every thread's slot, and counts what it finds. Afterwards, the count per label is the share of
time. Two numbers matter: **1.7 ns** — what the label costs — and **1.001 ms** — the sampling
interval it actually achieves.

The same Calcite question, answered: `EnumerableMergeJoinRule`, **46% of planning time**. Removing
it took planning from **17.8 s to 64.7 ms** — 275× — for a plan 0.0026% worse by the planner's own
cost model.

## Why not just use a normal profiler

Nothing here beats them at what they are for, and [case.md](case.md) keeps that honest. But four
things they cannot do:

- **the label is your identity, not the JVM's** — a rule instance, a query, a tenant, a shard;
- **counts** — no stack profiler has them, and "1.5 ms × 5,034 firings" and "9 µs × 245,190
  firings" are opposite problems with opposite fixes;
- **no depth limit** — JFR truncates at 64 frames and loses whole samples when it does;
- **it tells you the rate it actually achieved.** JFR was asked for 1 ms and delivered 6.3 ms in one
  run and 13.7 ms in another, without saying so.

## Two sizes of operation, and they need different machinery

|  | **fine** | **coarse** |
|---|---|---|
| size | **50 ns and up, no upper limit** | **~1 µs and up** |
| example | a hash probe, one filter condition | serving a query, planning a statement |
| how it is measured | an integer in a slot, sampled | a real object, allocated and timestamped |
| what you get | share of time, and counts | per-execution duration, percentiles, parallelism |
| cost per execution | ~2 ns | ~40 ns |
| status | **built and verified** | phase 4, not started |

**The two overlap, and the sizes above are not a dividing line.** Below about a microsecond you have
no choice: timestamping an operation that short costs more than the operation, so fine is the only
thing that works — that is the whole reason the fine tier exists. Above a microsecond **both work**,
and the question changes from *how long is it* to *what do I want*. Coarse tells you more. Fine is
twenty times cheaper, and its error does not grow with how often the operation is called, which a
stopwatch's does — that is what made a competing tool rank the wrong Lucene clause first.

Nothing is too *long* to be fine. We measured Apache Calcite's planner rules that way — hundreds of
microseconds each, well into coarse territory — and the numbers agreed with an independent profiler
to about one percentage point, and found a 275× speedup.

Below ~50 ns, do not label the operation at all — label the loop around a hundred of them and
divide. Three reasons, all measured: the instrument is a visible fraction of the work, the sampler
reads such operations 5–9% low, and — the nasty one — the compiler can move work across the
boundaries of adjacent short labels without leaving any trace in the numbers. One demo lost 95% of
an operation that way.

## How we know any of it is true

There is a **bench**: a synthetic workload of twenty operations, from 20 ns to 2 µs, whose true
answer is known by construction. Every claim is checked against it, and every tolerance in the code
was set by measuring rather than by choosing. The rule for [findings.md](findings.md) is that a
claim without a number is a hunch, and hunches have been wrong here more than once — a busy loop the
JIT deleted, a comparison that reported an instrumented run as 15% *faster*, a "bound" that came
out below the thing it was bounding.

Then the same tool was pointed at Calcite, which nobody here wrote, and its answers agreed with
JFR's to about a percentage point. Then at Lucene, where JFR *disagreed* — and the disagreement
turned out to be ours: a label placed on a scorer but not on the factory that built it, losing a
third of the clause that mattered while every number in the report still looked fine. Fixed, and
recorded, because that is the failure mode this design has and a flame graph does not.

## What the report says, and what it refuses to say

Every number comes with what it is worth:

- **share** — where the time went, per label;
- **noise floor** — how wrong pure chance could make that share;
- **calls** and **implied duration per call** — the smell test only you can apply: an operation you
  know is 20 ns showing 500 ns is stalling on something;
- **the CPU duty cycle** — *"threads were on CPU 96.4% of sampled wall time, so at most 3.6 points
  of any share is time a thread was not actually running"*. Sampling counts threads that are
  *inside* an operation, whether they are running or blocked or descheduled. This single number
  bounds how far apart those two things are.

And it stops you when the numbers cannot be right. A label on something under 50 ns is a mistake in
the *code*, identical on every machine and every rerun, so profiling halts within a second and says
which label and what to do. A label on something that turns out to be milliseconds long is only a
*warning*, because that depends on the day's workload — and because Calcite's labels were exactly
that and their numbers were correct.

## Where it stands

Built and verified: the bench, the fine tier, the check of the sampler against known truth,
bounding what a share is worth, and four trials on foreign code — Calcite; Lucene, where eight
clauses of one query were separated that a flame graph could not tell apart at all, on eight
threads, at a cost of 6.5% against 35% for the way a production search engine does it; Netty,
where four handlers sharing one class are four rows here and one indistinguishable blob in a flame
graph; and PostgreSQL over a socket, which is the first workload here that waits for anything.

Released as v0.1.0 — Apache-2.0, one dependency, Java 21+, and a report you can read: what every
column and warning means is [output.md](output.md).

**The coarse tier is built too**: a label around a logical operation — a request, a query —
measures how long each execution took, exactly, and cross-tabulates the fine breakdown underneath it.
That is the one part of the report that is measured rather than sampled, and the only place
percentiles exist.

**And work now keeps its logical operation when it crosses a thread.** Wrap a pool with
`.propagating()` and the pieces of a request are counted as part of it. On Lucene that took the
labelled time falling outside every span from 88.5% to nothing, and two columns follow from it:
`inside`, the threads a request ties up, and `working`, the ones actually on a CPU. Work that
outlives the request that forked it is detected and excluded rather than quietly billed to it.

Not built: the whole-application parallelism coefficient, the annotation and agent surface, JFR
output. The coroutine module was dropped deliberately — the mechanism is the same one, and there is
no coroutine workload here to point it at ([ideas.md](ideas.md) item 25). Virtual threads are
untested and so unclaimed, and the slot registry would not enjoy them. [plan.md](plan.md) has the
order.

**One number carries a warning, and it is worth knowing before you read a report.** `working` is
built on Java thread state, which reports a thread stopped inside a *native* call — a socket read, a
file read — as runnable. Against PostgreSQL the column read 55x more CPU than the machine actually
spent. The report now prints the measured duty cycle beside it and says so; `inside` is unaffected.
The lesson generalises: this tool sees waiting that another *thread* caused, and is blind to waiting
that the *operating system* is doing on your behalf unless the duty cycle catches it.

One thing worth carrying away, because it cost a day to learn: **a share is not a counterfactual.**
The rule holding 46% of the time was worth 275× when removed, because it was creating work for
everything else as well as doing its own. Shares tell you where time went. They do not tell you what
you would save.

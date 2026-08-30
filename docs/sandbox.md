# The sandbox — using the tool, rather than testing it

`sandbox/` is a place to point the profiler at something and see what that is like. It exists
because the trials answer only half the question.

## What it is not

**Not a trial, and nothing measured in it is evidence.** The four trials — Calcite, Lucene, Netty,
PostgreSQL — are somebody else's codebase, and that is precisely what makes their numbers mean
something: the code was not written with this tool in mind, so it cannot have been shaped to suit it.
Anything written here is written by someone who already knows how the profiler works. That is the
same objection that kept the graph-traversal bench and the coroutines module out of the plan
([ideas.md](ideas.md) items 23 and 25), and it applies here too.

If a number from this module ever ends up in [findings.md](findings.md), something has gone wrong.

## What it is for

The trials answer *are the numbers right*. This answers *is the thing usable*:

- does a label go where you want it, or does the code have to be rearranged around it
- does the report read without the documentation open
- does a warning land, or does it look like noise
- what do you reach for that is not there

That question needs a person using the tool, not a workload proving it. It is also the only question
here that cannot be answered by measurement, which is why it gets its own module and its own log
rather than being folded into a trial.

A sibling Gradle module sees only the **public** API — Kotlin's `internal` is scoped to a
compilation — so this sits exactly where a stranger sits.

## Running it

```
gradlew :sandbox:run
```

With the colon. An unqualified `gradlew run` matches the `run` task in every module and launches the
bench and all four trials as well.

## The friction log

Observations from using it, newest first. Raw and unfiltered on purpose: an observation is not yet a
task, and forcing it into one too early is how the interesting half gets lost. When one becomes
something to do, it graduates into [ideas.md](ideas.md) and is marked here as having done so.

---

### The report is a wall of text, and the order is wrong
*2026-08-30, Andrey, second run. **Fixed the same day.***

> "It's a wall of text. Good for AI, very bad for a human. And funny thing is that there comes some
> result, then description and then suddenly coarse tier comes out. No line breaks, no headers."

Exactly right, and the structure was: header, fine table, **fifteen lines of legend**, coarse table,
its legend, warnings. So the reading order was numbers → prose → numbers → prose, with `=` and `-`
rules serving as both table borders and section breaks, and not one blank line in the whole report.
On a short run it printed two table headers, zero rows, and twenty lines explaining columns that had
no data.

The verbosity came from a principle this project repeats often — a number nobody can interpret is
worse than no number — applied without ever asking *where on the page* the explanation belongs.
Those are two different decisions and only the first one had been made.

**Fixed:** named section headings with blank lines, every legend collected at the bottom under
`HOW TO READ THIS`, an empty table replaced by a line saying why it is empty, and rules printed only
where there is something to rule off.

**And two columns were fixed by naming rather than by explaining**, which was Andrey's suggestion and
is the better half of the change: `share` → `occupancy%`, since it is the same quantity as the
`occupancy` column beside it and neither is CPU; and `busy/exec` dropped, since it is `working ×
mean` with both already printed and its name never said it summed over threads. Two legend paragraphs
stopped being necessary rather than being moved. A name that carries its own caveat still works on
the tenth run, which a paragraph does not.

*Not changed:* the 130-column width. I claimed it was wider than most terminals; asked for evidence,
I had none, and the pasted output showed no wrapping at all. It was a complaint I added to a report
that had not made it.

### `p99` printed larger than `max`, on a single execution
*2026-08-30, Andrey, from the first real run in the sandbox. **Fixed the same day.***

```
coarse operation   executions      mean       p50       p90       p99       max
request                     1  713.2 us  720.9 us  720.9 us  720.9 us  713.2 us
```

`mean` and `max` are exact — two timestamps. The percentiles come from the log-bucket histogram and
are reported at the **top** of the bucket the value fell into, because a latency figure may overstate
and must never understate. 713 200 ns lands in bucket 138, spanning `[655.4 µs, 720.9 µs]`, so all
three printed as the ceiling: +1.08% here, against a documented worst case of +12.5%.

All of which is true, documented, and *still nonsense on the face of it*: a 99th percentile cannot be
larger than the maximum. With many executions the rounding disappears into the distribution and
nobody sees it; with one it is the first thing you read.

**Fixed by clamping every percentile to the measured maximum**, which cannot cost the never-below
guarantee — `max` is exact, and the true p-th percentile of a set is never above its true maximum, so
the clamp can only move a value down to something still at or above the truth.

**What it says about the tool beyond this line.** The rounding was documented in three places and
defended with a good argument, and none of that stopped it printing an impossible number. A guarantee
stated at the level of *"at most 12.5% high"* did not catch a violation of *"a percentile is not
bigger than the maximum"* — the second is not a tolerance, it is arithmetic, and it wanted asserting
separately. Two tests now do.

*Also in that row, both honest rather than broken:* `busy/exec 0.0 ns` because a 713 µs execution
against a 1 ms step caught no samples at all, and `inside` / `working` / `in flight` showing `-`
because `instanceTicks` is zero and there is no denominator to divide by.

### A fixed iteration count produces an honest, useless report — and you only find out afterwards
*2026-08-30, from writing the skeleton. Mine, so weight it accordingly.* **Graduated to
[ideas.md](ideas.md) item 27** — with the complication that `start()` cannot know how long the run
will be, so the warning would have to be unconditional.*

The first version of the skeleton ran a fixed 2,000 iterations. On this machine that is 0.2 s, and
the report came back with 26 samples, **19.6% noise** on the only operation, and the duty cycle
reporting itself *"unavailable — no window completed (the run is shorter than 1.000 s)"*.

Everything it said was true and clearly flagged. But the flags arrive **after** the run, and the
first thing a new user does is exactly this: a small loop, to see what happens. The tool knows its
own step and its own duty-cycle window at `start()`; it could say *"a run under about a second will
not produce a duty cycle"* then, rather than at the end.

Fixed by making the skeleton time-bounded, which is the pattern worth copying anyway.

### The report is long for a small program
*2026-08-30, same session. Mine.*

A program with one fine label and one coarse one prints roughly forty lines of legend. Every line
earns its place when there are twenty operations and a reader who has to be stopped from
misinterpreting a share — that is why they are there, and [output.md](output.md) explains each. But
the ratio of explanation to data at this size is stark, and a first impression is formed at this
size.

Not obviously a defect: the alternative — hiding the legend once a reader is assumed to know it — is
how a tool ends up with numbers nobody can interpret. Recorded because the tension is real and the
right answer is not clear.

---

*Add below the line above, newest first. Date each entry, and say who made the observation — an
observation from someone who knows how the tool works is worth less than one from someone who does
not, and the log should let a later reader tell them apart.*

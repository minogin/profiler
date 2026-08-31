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

### Nothing says the tables are sorted, and the coarse one was sorted by the wrong thing
*2026-08-31, Andrey. **Fixed the same day.***

> "now to the FINE OPS: is this table sorted?"

It was - by hits descending, the same order as the `occupancy%` column beside it - and the report
never said so. Neither table did.

**Why a reader cannot just look and see:** at sandbox size there are two rows, and two rows are
consistent with any ordering at all. Registration order and descending order are the same picture. So
the question does not arise on a big report where the shape gives it away; it arises on a small one,
where looking cannot answer it.

The first fix was a heading that said `FINE OPERATIONS - ordered by occupancy%, largest first`, which
Andrey cut immediately as too long. **A marker on the column is the better answer** and it is the same
lesson as `occupancy%` and `mean (jittered)`: put the caveat on the thing it qualifies, not in a
sentence above it. So the sorted column is now headed `Occupancy% v`, in ASCII rather than an arrow
glyph because a Windows console is not UTF-8.

*Found on the way:* the fine header had been two columns out of step with its own rows all along,
because `Occupancy%` never fitted the eight characters it was given. Rebalancing the header to 22/12
to fit the marker fixed that too.

**Then the real question, which was not about display at all:**

> "How should we actually sort coarse tier? Obviously by the WORST operation - the one to improve.
> What is that?"

The coarse table could not take a marker, because **it was sorted by something it did not print** -
`inclusiveHits`, the sampled occupancy. Following the question properly says that was the wrong key
twice over:

- **The right ranking is total time, executions x mean.** Mean alone puts a rare slow operation above
  a frequent one costing ten times more; a percentile ranks by tail, which is a latency question and
  not a cost one.
- **The coarse tier measures that exactly**, since it times every span - so sorting by sampled
  occupancy was adding sampling error to a number already known precisely.

`Total` is now a column, the table is sorted by it, and the marker sits on it. The table grew to 141
columns to fit. That is the 130-column limit going, and the log is where it should go from: it was
defended here as "wider than most terminals", asked for evidence, and had none.

**And the question exposed something the tool cannot yet answer.** That total is *inclusive* - a
parent span contains its children - so in a nest the outermost context always sorts first, and its
total is the sum of what is inside it. "The one to improve" really means self time, and the coarse
tier has no per-context nested accounting to compute it. [ideas.md](ideas.md) item 28, with why it is
not a small change: propagated spans close on another thread, and a type nested inside itself has to
make the same deduplication choice the inclusive walk already makes.

### `Duty cycle` is a term, not an explanation - and the first run only ever sees it fail
*2026-08-31, Andrey. **Fixed the same day.***

```
Duty cycle    unavailable
  Why         the run is shorter than the 1.000 s window
```

> "This is a bit vague for a user - what is Duty cycle?"

The row says why the number is missing and never says what the number was. And this is the version
of it that a first run always gets: the window is a second, the first thing anyone writes is a small
loop, so the *unavailable* branch is where most readers meet the row for the first time - with no
figure beside it to explain itself, and a name that needs the docs open.

Two fixes, and the naming one is the better half again. The printed label is now **`Time on CPU`**,
which carries its own meaning; the code, `Duty.kt` and the design docs keep saying *duty cycle*,
because the term is standard, correct, and threaded through 339 mentions across 24 files including
four trial write-ups. `output.md` ties the two together in one sentence so the searchable word is
still findable. This is the `share` -> `occupancy%` move a second time.

And both failure branches now say what is missing and what it costs:

```
Time on CPU   not measured
  What        how much of the occupancy was really CPU - it is what bounds every share below
  Why         the run is shorter than the 1.000 s window
  Bound       none - read the shares below as occupancy, with nothing limiting how much was waiting
```

*Related but not the same thing:* [ideas.md](ideas.md) item 27 is about this arriving **after** the
run when `start()` already knows it will happen. Still open. That is a question of timing; this was a
question of the row being incomprehensible whenever it did arrive.

### `Sampling` printed 0.969 ms for a 1 ms step
*2026-08-31, Andrey. **Fixed the same day.***

```
Sampling      5 ticks at 0.969 ms x 1 thread - one sample per thread per tick
```

Which looks like the sampler missing its step by 3%. It is not: the interval is drawn within +/-25%
of the step on purpose, so the sampler cannot lock onto a workload whose own period is near the same
value and photograph the same phase every time. Over a long run the mean lands on the step; over five
ticks it is the mean of four draws and visibly does not. Checked against the seed - the four draws
spanning those ticks average 0.969 ms exactly, so it is reproducible rather than a fluctuation.

Nothing was wrong with the number, only with the reading of it, and the line was printing a *measured*
quantity while looking like it was echoing the *requested* one. It now says `0.969 ms mean (jittered)`
- two words, next to the figure that raises the question rather than at the end of the sentence.

The fraction is deliberately not in the message: it is a `Profiler.start` parameter, and printing
`+/-25%` would be a lie for anyone who passes `jitter = 0.0`.

### "3 labelled, 1 unlabelled" does not say what is being counted
*2026-08-31, Andrey. **Fixed the same day.***

```
Samples       3 labelled, 1 unlabelled, over 0.1 s
```

Reads as a count of *labels*, which is the wrong noun. It is counting samples, and a sample is one
photograph of one thread's slot taken once per tick — so on a one-thread run of four ticks there are
four of them, three of which caught the thread inside an `op(...)` label.

The old wording made the split the subject and left the total to be inferred by addition. Now the
total leads and the split qualifies it:

```
Samples       4 taken over 0.1 s - 2 inside a label, 2 outside every label
```

**A second thing the same question exposed, now also fixed:** the reader had been told three lines
lower that the run had `7 ticks` and `1 thread`, and nothing anywhere related those to the sample
count — the arithmetic the whole report rests on. `Sampling` now says *one sample per thread per
tick*. Deliberately not an equation, because a thread that registers partway through contributes
fewer, which is exactly why 7 ticks and 1 thread gave 4 samples.

**And a third:** asked whether unlabelled time is *bad*, the report had no answer. It splits parked
from runnable, which is the distinction that decides it, but never said that parked-and-idle is
normal while runnable-with-no-label is the one to look at. That is in [output.md](output.md) now.

### The report prints characters a Windows console cannot show
*2026-08-30, Andrey, with a screenshot. **Fixed the same day.***

```
CPU duty cycle: unavailable ? no window completed
nothing was sampled ? 4 ticks is too few
```

Em-dashes, arriving as a replacement glyph because a Windows console is not UTF-8 by default. This
was **already recorded** as [ideas.md](ideas.md) item 20, open, filed as cosmetic — and the
screenshot is why it was not: the character that breaks is the one introducing the clause that says
*why*, every time, because that is what a dash is for. A reader loses precisely the explanation.

Sixteen printed strings in the library and fifty-three across the bench and trials. All ASCII now,
with a test that renders every message builder and every optional block and fails on any character
above 127 — because the next em-dash will be typed by somebody who never read the entry.

*Worth noting for the log's own sake:* this is the first item here that was already known and
deferred. Using the tool did not discover it, it **re-prioritised** it — which is a different and
cheaper kind of value than finding something new.

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

**Fixed:** named section headings with blank lines, an empty table replaced by a line saying why it
is empty, and rules printed only where there is something to rule off.

**And then the legend was cut from thirty-seven lines to five.** The first fix only *moved* it to the
bottom, which was my choice by default rather than a decision anybody made — Andrey asked whether it
was still always shown, which is the question that settled it. What stays on is the five things that
will make a reader draw the wrong conclusion; the rest is reference and lives in
[output.md](output.md). `render(legend = true)` prints everything.

A follow-on he also caught: the heading said `OPERATIONS` beside `COARSE OPERATIONS`, making fine the
unmarked default and coarse the exception — the exact asymmetry the `registerFine`/`registerCoarse`
rename had removed from the API an hour earlier, reproduced in the output. Now `FINE OPERATIONS`.

The whole report for a one-operation run is **23 non-blank lines**, against roughly sixty before.

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

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

### `Threads` counted threads the operation had never run on
*2026-09-02, Andrey, on a sandbox with a `main` thread and a pool of 4. **Fixed the same day.***

*"Why does it show 5 threads for work2?"* - and then, when told the number was the run's own thread
count: *"is there a real reason to have the overall thread count in the denominator? I have an app
which runs in 1 thread, then runs highly concurrent stuff, then 1 thread again. Why would I ever be
interested in that number?"*

```
work2   ...   1.83 / 5      <- 4 pool threads
work1   ...   1.00 / 5      <- main only, and it still says 5
```

The denominator was `Report.threads`, the high-water mark of registered slots for the whole run. It
is the pool size **only on a workload where every thread does everything** - which is the bench, and
the bench is where the column was designed. The first workload here with a `main` thread beside a
pool broke it at once: `work1` was measured against four threads that could not have entered it, and
in Andrey's three-phase example both single-threaded phases would be measured against the peak of
the concurrent one.

The fix was cheaper than the argument: every slot already keeps per-operation call counters, so the
threads that called an operation are known exactly. The only gap was threads that exit - `release()`
and `reclaimDeadSlots()` fold their counters into the retired totals and lose who contributed - and
both fold sites already loop over the operations, so counting them there costs nothing and happens
on a thread that is leaving.

```
work2   ...   1.54 / 4
work1   ...   1.00 / 1      <- serial by construction, and now it says so
```

The run-wide count still exists in the header (`x 5 threads`), where it is a fact about the run
rather than one pretending to be about a row.

---

### The floor warning was there, under the wrong heading
*2026-09-02, Andrey, on a 1M-iteration loop in the sandbox. **Fixed the same day.***

Andrey's question was *"I have 8 ns/op and it does not say it's bad - do we really have any bottom
limit?"* We do: `FLOOR_NANOS` is 50 ns and the check had fired. It just printed fifteen lines below
the number it was about, after the coarse table:

```
FINE OPERATIONS
work1   ...   1,000,000 calls   8.0 ns/call     <- the number
COARSE OPERATIONS
request ...
  request was: unlabelled 72.2%, work1 27.8%
-----------------------------------------------------------------
  ! work1: 1,000,000 calls at under 12.4 ns each, below the 50 ns floor.
```

Everything between the row and its warning belongs to the coarse tier, so the warning reads as being
about `request`. **A warning that is present and unfindable is worse than absent** - the reader
concludes the tool has nothing to say about a label that is, in fact, below the floor.

Two causes, and the second was Andrey's: `render()` called `renderCoarse()` before the fine tier's
warnings, and a bare `!` never says the word *warning*. It now gathers all of them into one section,
numbered, with the count in the heading and a cap at ten:

```
WARNINGS (1)
   1. work1: 1,000,000 calls at under 16.7 ns each, below the 50 ns floor.
      The hook is a large fraction of an operation that size, the sampler reads it low by 5-9%,
      ...
```

The stuck-baseline line moved up under the fine table instead, since it is a note that the `Over 1t`
column is read against rather than a warning about anything.

---

### The fold said how many operations it hid, but not which
*2026-09-02, Andrey, reading a sandbox report. **Fixed the same day.***

```
  1 operation never sampled and folded away (none of them ran at all)
```

With one label of a handful missing, the reader has to diff the report against their own source to
find out which one it was - and on a short run, where this line fires most, *which* is the whole
question: a label that never ran is either a code path that did not execute or a label in the wrong
place, and those are different bugs.

The count was there because the line was built for Calcite's twenty-five folded rules, where a list
would be the screenful the fold exists to prevent. But a cap solves that and a bare count does not.
Both branches now name the operations, four at most:

```
  1 operation never sampled and folded away, and never called: decode
```

---

### The empty-table message advised a knob that cannot rescue the run
*2026-09-02, Andrey, running a short loop in the sandbox. **Fixed the same day.***

```
  nothing was sampled - 2 ticks is too few for the sampler to catch anything. Run for longer, or
  lower stepMillis.
```

The question it drew was *"is `stepMillis` effectively chosen or not?"* - and the second half of the
advice does not survive it. Two ticks is a run of about 2 ms. Reaching the fifty ticks the table
wants would need a step of **0.04 ms**: a sampler waking 25,000 times a second, which measures the
sampler rather than the program, and fifty samples would still say nothing. There is no step that
rescues a 2 ms run, so half the sentence pointed at a door that does not open.

Lowering the step *is* real advice, but for a different symptom - an operation that is called and
never caught in a run long enough to be real - and that case already has its own line, in the fold
of zero-hit operations.

The message now says **"Run for longer."** and nothing else, and the README no longer describes the
step as a resolution dial. What bounds the step in each direction, and whether the tool should be
choosing it rather than asking, is [ideas.md](ideas.md) item 34.

---

### `Time on CPU` said the run was too short, on a run twice long enough
*2026-09-01, Andrey, reading a pasted report. **Fixed the same day.***

```
Samples       2,946 taken over 1.6 s
...
Time on CPU   not measured
  Why         the run is shorter than the 1.000 s window
```

1.6 seconds against a one-second window, four lines apart. The report was contradicting its own
numbers - which is worse than saying nothing, because a reader who believes it goes and lengthens a
run that was already long enough.

**The message was wrong and so was the measurement.** Reproduced with two runs identical but for one
thing, whether the worker threads were still alive at `stop()`:

```
=== THREADS DEAD AT stop() ===        === THREADS ALIVE AT stop() ===
Samples  3,178 over 1.7 s             Samples  3,182 over 1.8 s
Time on CPU   not measured            Time on CPU   97.81% of wall time
```

Three things conspired, and none of them is visible from any one place in the code:

1. The first duty sample fires at `start()`, **before any worker has registered a slot** - threads
   are created after the profiler is started - so it establishes no baselines and burns a window.
2. The second sample finds the slots but has no previous reading for them, so it only *becomes* the
   baseline. Still no window.
3. That leaves the sample taken by `finish()` as the first one able to count anything - and
   `finish()` runs at `stop()`, exactly when short-lived workers have already exited.
   `getThreadCpuTime` returns -1 for a dead thread, nothing is counted, and `windows` ends at zero.

So **on any run under about two windows, the only window that can close is the one at `stop()`** -
the one moment guaranteed to fail if the threads finished their work and went home.

**Why nothing caught it.** The bench and all four trials run long-lived pools, so the threads are
always alive at `stop()` and the window always closes. Every measurement of the duty cycle this
project has ever taken came from a shape that cannot exhibit the bug. It took a forty-line program
that spawns two threads and joins them - the most ordinary thing anyone would write - to find it.

**Fixed both halves.** A sample that finds nothing to measure no longer counts as a window boundary,
so the grid starts when there is something to measure and a window closes *mid-run*, while the
threads are alive; the same run now reads `99.90% of wall time, 1 window over 2 threads`. And the
failure message names the condition it found instead of assuming the only cause it knew about -
including *"N of the threads being measured exited before the run ended"*.

*The cost:* the window grid starts marginally later than it used to, so duty figures are computed
over slightly different intervals than the ones in [findings.md](findings.md). No bias in either
direction, but not bit-identical.

### A column deleted and restored inside two hours, and both were right
*2026-08-31 into 09-01, Andrey. **Fixed.***

The concurrency ratio - `thread-time / wall-time`, how many threads were inside an operation at
once - went through `threads`, then `in flight`, then deletion, then back as
`Concurrency / Threads`, in one evening. Worth logging precisely because it looks like churn and is
not.

**Why deleting it was right.** Both names had failed on a reader who wanted exactly that number, and
it is derivable from the two columns beside it - the argument that had already deleted `busy/exec`.
Andrey, after three rounds of naming: *"honestly, i just don't get the whole thing / and this
discussion makes me tired."* A number needing a paragraph before it can be read has not earned a
column.

**Why restoring it was also right**, and it is a better principle than the one that deleted it:

> "otherwise threading is completely missing now (yes, you can look at thread-time and wall time, but
> we should not ask a human to make this)"

*Derivable* is not *available*. `busy/exec` was deleted for being derivable **and** misleading; this
one was only derivable, and a report about a threaded program should not make a person compute
whether it was threaded. The mistake was reading one precedent as covering both cases.

**What made the restored version work** was not the name, it was doing the arithmetic. The two
earlier attempts both printed the number and asked the reader to interpret an abstraction
(*executions in flight*); the third prints it beside the operands it came from.

*One thing settled on the way:* it is `Concurrency`, not `Parallelism`, which Andrey asked for and
accepted the correction on. The sandbox run is the argument - `work1` at `2.00 / 2` concurrency and
`7.2%` runnable, two threads inside and both asleep, so a real parallelism near `0.14`. Calling the
2.00 parallelism would have been false about that very run.

**And then the table needed a band.** Eleven columns is past what anyone reads as a flat list.
Andrey grouped them - `Load`, `Spread`, and a group he could not name: *"this group I don't
understand, postpone it for now"*. It prints as an **unlabelled span**. Naming it on my guess would
have taught a grouping nobody agreed to; an empty band says *these belong together and we have not
settled why*, which is the truth and can be filled in later without moving a column.

### Is `Waiting` part of thread-time, or on top of it?
*2026-08-31, Andrey. **Fixed the same day.***

> "we have thread-time and waiting, but what exactly is 'thread-time minus waiting'?"

It is part of it - `waitingHits / hits`, the same denominator - and nothing in the table said so. A
bare noun beside a duration reads as an independent quantity.

**The fix that did not work, and why it is worth recording:** print it as a duration, `2.68 s` beside
`2.89 s`, so containment is visible in the magnitudes. Andrey killed it in one line - *"does not
matter if it's sec or % - it will still be unclear"* - and he was right. Seconds beside seconds is
exactly as ambiguous as a percentage. **The unit was never the problem; the header was.**

Then his own answer, which is better than the `of which waiting` header I proposed next: print
**both halves**, summing to 100%.

```
Thread-time Runnable / Wait
     2.89 s    7.2% / 92.8%
```

Two shares that add to the whole cannot be read as an addition to it. No header phrasing has to carry
the containment, because the arithmetic does.

**The name cost an argument and was worth it.** Andrey asked for `Run / Wait`; the column says
`Runnable`. The `-able` is the entire distinction - *not blocked*, rather than *executing* - and the
gap is not pedantic: a preempted thread reads `RUNNABLE`, 14-18% of wall time on this machine on a
bench that never blocks, and so does a thread stopped in a native call. That is the JDBC trial
defect, where a number read as CPU was 55x what the machine had spent. Five characters to stop a
reader believing the left half is CPU time.

### The columns were named for the instrument, and the legend outlived them
*2026-08-31, Andrey, from a pasted run. **Fixed the same day.***

> "occupancy - cannot we use thread-time instead? below you say - occupancy IS thread-time"
> "Elapsed - hmmm, what is it? wall time or elapsed sum of all threads...?"

Both correct, and the first is the sharper one: the report **already** called that quantity
thread-time everywhere except in the column - in `Coverage`, on the coarse lines, in half the
warnings. One quantity with two names is worse than either name, and the one that lost was the one
the reader meets first.

`elapsed` failed differently. It is wall clock, not a sum, and that is the *only* reason it is
printed beside a column that is a sum - yet its name did not say which it was, so the pair that
exists to make the distinction did not make it. `Thread-time` and `Wall-time` name the distinction
itself.

**What the same pasted run then exposed, all of it drift rather than design:**

- **Three of the five legend lines named columns that no longer existed**, and the full legend had
  whole paragraphs on `in flight`, `inside` and `working` as columns. A legend is edited when a
  column is, and had not been.
- **The rules printed at three different lengths on one page** - 130 around the fine table, 112
  around the coarse one, 130 again to close - because the tables had been resized while the rules
  stayed pinned to a constant that no longer described either. Each table draws to itself now.
- **`Time on CPU` still said "occupancy"** in both its sub-rows: the block whose job is to bound the
  table, using the word the table had stopped using.
- **`Runnable` in the header repeated `Coverage` to the decimal** - 99.9% both - because nothing was
  parked. It prints only when parked time makes them differ.

None of these are hard. All of them are what a report looks like when it is edited a column at a
time, and none would have been noticed without someone reading the whole page cold.

### The word "parallelism" is not in the report, and it has three columns about it
*2026-08-31, Andrey. **Fixed the same day.***

> "tell me why I don't see any information about `request` parallelism"

The run in question - two threads, each running its own `request`, `work1` sleeping most of its
1.45 s:

```
Coarse operation           Executions    Total v       Mean  Waiting Inside   Working   In flight
request                             2 2906.49 ms 1453.25 ms    91.6%   1.00      0.08      2.00/2
```

Every part of the answer is on that line. `In flight 2.00/2` is the parallelism - two executions at
once over the two threads there were, the most this program can do. `Inside 1.00` says each request
was carried by exactly one thread. `Working 0.08` says that thread was on a CPU 8% of the time.

**The reader could not find it because the report never uses the word**, and because the two things
called parallelism are different questions printed as adjacent columns with no sign that they are
related:

- **within one execution** - `inside` and `working`: does *this request* use more than one thread
- **across executions** - `in flight`: how many requests are in the system at once

The first is a property of the code, the second of the load - a distinction `output.md` makes for `in
flight` and nowhere states as the axis separating it from the two columns beside it. A reader with a
parallelism question has no way to know which of the three answers it, and `inside 1.00` reads as
"no parallelism" when the truthful answer for the program as a whole is "as much as there is."

**Fixed by moving them out of the table and into words.** The first instinct was a separate
`PARALLELISM` table, and it was the wrong one: the problem was never *where* the columns were, it was
that nothing named what they answered, and unlabelled columns are just as unfindable in a new table.
What a prose line can do that a column head cannot is say the word, and say which of the two
questions each number answers:

```
  request: 99.863% of thread-time inside operations, 2.91 s occupancy
  request parallelism: 1.00 thread per execution, 0.08 of it on a CPU; 2.00 executions at once over 2 threads
  request was: work1 98.7%, work2 1.2%, unlabelled 0.0%
```

Precedent for the placement: occupancy and share already live on those lines, put there because the
table was full.

*And it is suppressed on a single-threaded run*, which is the shape of most first runs and where the
three numbers can only restate each other and the `waiting` column. Andrey's question on seeing the
fix - "each coarse op will take a lot of lines?" - which the counts answer: every real trial
registers one or two coarse operations, Calcite two and the other three one each, because a coarse
label is a context and the tier boundary keeps anything under ~1 us out of the tier. Only the
synthetic bench has eight.

*And it paid for itself in width.* The coarse table is **112 columns**, from 141 an hour earlier -
`Total` had pushed it past the old 130 limit and this took back more than `Total` added. The rules
under it are drawn to the table now rather than to a shared constant.

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

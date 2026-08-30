# Reading the report

`Profiler.stop().render()` returns one block of text. This says what every part of it means and,
where a line is a warning, what to do about it.

> **If you change the report, change this file, and change the legend `render()` prints — all
> three.** The legend and this document say the same things on purpose, because a reader with a
> terminal and no browser still needs the short version. Two copies of anything drift: a KDoc in
> this project spent a day describing a function it had been moved away from. `Report.render()`
> carries a marker pointing here.

Everything below is a real run — `./gradlew :run --args="--demo --seconds=12"`, which profiles a toy
workload using nothing but the public API. It is worth reading before your own, because it contains
a mistake on purpose.

---

## The header: is this run worth reading at all

```
86,597 labelled samples over 12.1 s, 11,933 ticks at 1.006 ms, 8 threads
labels cover 87.10 s of the 95.97 s of thread-time observed (90.8%); 8.87 s was outside every label, in 8,815 samples
  of that unlabelled time, 10.1 ms was a thread not runnable (0.1%) and 8.86 s was a thread runnable with no label on it
clock: getThreadCpuTime, resolution 15.625 ms measured, window 1.000 s, dearest walk 621.8 us
threads were on CPU 98.68% of sampled wall time  (10 windows, 8 threads, per window 97.18%..99.35%)
  inside labelled work it was 98.55%, and that is what bounds the shares
at most 1.47 pp of any share is a thread waiting rather than working
so the ranking is trustworthy
```

**Line 1 — how much evidence there is.** Everything downstream is counting, so the sample count is
the precision. 86,597 samples is a lot; a thousand would make every share below a few percent
meaningless. `1.006 ms` is the step the sampler *achieved* against the one you asked for — if it
has drifted far above your `stepMillis`, the sampler could not get scheduled and the run is worth
less than it looks.

**Line 2 — coverage.** *Labels cover 87.10 s of the 95.97 s observed.* The gap is time no label was
open. A low figure is not automatically bad — it means your labels do not cover everything, which
may be exactly what you intended — but it is the first place a misplaced label shows up. Reported in
seconds and not only as a percentage, because *"labels cover 87 s of 96"* is something you can act
on and *"90.8%"* is not.

**Line 3 — what the uncovered time was.** Two entirely different findings look identical in line 2:
work nobody labelled, and threads doing nothing at all. This separates them. A pool that spends its
life parked between tasks reads as a huge unlabelled share and means nothing is wrong.

When the two differ by more than half a point, a fourth line appears giving coverage over *runnable*
occupancy alone, with both sides restricted:

```
  of the thread-time that was runnable at all, labels cover 240.94 s of 284.01 s (84.8%)
```

On Lucene that turns an alarming 59.3% into 84.8%, because most of the shortfall was pool threads
parked between queries. Absent here because this workload never waits, so it would have said the
same thing twice.

**Lines 4–8 — the duty cycle, and the bound it puts on every share below.** This is the part that
makes the report checkable rather than merely plausible.

The sampler counts a thread as inside an operation whether it is running, blocked, parked or
descheduled — that is **occupancy**, not CPU. Occupancy counts waiting in full, which is the right
behaviour for *"why is this slow"*. The catch is that occupancy is only additive across threads when
it is CPU: a hundred threads parked one second on one lock is a hundred thread-seconds of occupancy
and one second of real cost.

So the report measures the gap. *Threads were on CPU 98.68%* is over every registered thread;
*inside labelled work it was 98.55%* is over the labelled samples the shares are actually taken
over, and **that** is what bounds them. The two differ when some threads work while others idle —
in a starvation test the first read 19.40% and the second 96.94%, and only the second is about the
numbers in the table.

*At most 1.47 pp of any share is a thread waiting rather than working* is the error bar: one number
that holds for every row at once, needing nothing to be known about which operation stalled. Then a
verdict:

| verdict | duty | what it means |
|---|---|---|
| *so the ranking is trustworthy* | ≥ 95% | the bound is under a point; real gaps between rows are wider than that |
| *a share is still roughly time, but small gaps between operations are not resolved* | ≥ 75% | trust the top of the list, not adjacent rows |
| *read a share as where threads SIT, not where cycles GO* | < 75% | and beware of adding two shares up, since one wait can be counted once per thread waiting on it |

**And sometimes there is no bound at all:**

```
  nothing here bounds the shares: 34.4% of thread-time was off the CPU while the thread
  still read runnable — a native call, an event loop in a poll, or the scheduler — and
  labels cover 13.9% of these threads, so the worst case is that all of it was inside them
```

This is honest rather than broken. The JVM reports a thread in a native call — an event loop inside
`epoll_wait`, a blocking socket read — as `RUNNABLE`, so the state read cannot see that waiting, and
what cannot be seen has to be assumed worst case. When there is more invisible off-CPU time than
there is labelled time, the assumption swallows everything. **What to do:** put a label around the
waiting, not only around the work. A selector loop with a label on it turns invisible off-CPU into
labelled occupancy and the bound becomes tight again.

---

## The shape of the report

Sections, in order, each with a heading and a blank line before it:

```
(header)            how many samples, what they cover, and the duty cycle that bounds them
FINE OPERATIONS     the fine table, or one line saying why it is empty
COARSE OPERATIONS   the coarse table, present only if you placed a coarse label
(warnings)          anything the run wants to tell you about itself
HOW TO READ THIS    five lines: the things that will make you draw the wrong conclusion
```

**The legend is five lines, not thirty-seven.** `render()` prints only what will actively mislead
you — that `occupancy%` is not CPU, that `waiting` is waiting another thread caused, that
`in flight` is your load and not your code, that a share is not a counterfactual, and that `working`
bounds the speedup from above rather than being it. Everything else — how `noise` is computed, the
convoy arithmetic behind `elapsed`, what the `was:` lines are — is reference, and reference belongs
here, where it is read once instead of skipped thirty times.

`render(legend = true)` prints the full text, and this document is the same content at length.

**The explanations are last on purpose.** They used to sit *between* the two tables, so the report
read numbers, prose, numbers, prose — reported the first time somebody who had not written it tried
to read one: *"a wall of text, good for AI, very bad for a human."* Explaining a column and
interleaving that explanation with the data are two different decisions, and only the first had been
made.

**Two columns were renamed or removed rather than explained.** `share` became `occupancy%`, because
it is the same quantity as the `occupancy` beside it and calling one of them a *share* invented a
distinction that does not exist while hiding the one that does — neither is CPU. `busy/exec` was
dropped: it is `working × mean`, both of which are printed, and its name did not say it summed over
the threads in an execution, which is why it could exceed the span next to it and look like a defect.
A name that carries its own caveat needs no paragraph, and unlike a paragraph it still works on the
tenth run.

## The table

```
operation                     share  occupancy  waiting   elapsed   in flight         calls     hits  noise  impl/call over 1t
flushBatch                  44.250%    38.54 s     0.0%   11.75 s      3.28/8   288,514,362    38319  0.51%   133.6 ns   0.01%
validateRecord              41.222%    35.91 s     0.0%   11.68 s      3.07/8   288,514,362    35697  0.53%   124.5 ns   0.00%
parseRecord                  9.478%     8.26 s     0.3%    6.14 s      1.34/8   288,514,362     8208  1.10%    28.6 ns   0.27%
indexRecord                  5.050%     4.40 s     0.0%    3.73 s      1.18/8   288,514,362     4373  1.51%    15.2 ns   0.05%
```

| column | what it is | what to watch for |
|---|---|---|
| **share** | this operation's slice of all **labelled** samples | the denominator is labelled samples, not every sample — so adding a label somewhere else does not move this one |
| **occupancy** | `hits × step`, as summed thread-time | **absolute**, so unlike share it does not move when a label is added, moved or removed. This is the column to compare between two runs |
| **waiting** | the share of those samples whose thread was parked, blocked or waiting | a thread the scheduler merely preempted still reads runnable, so this is waiting that *another thread* caused. Blind to native waits |
| **elapsed** | wall clock with at least one thread inside | not latency: it is every execution's interval unioned, so it says the operation had *somebody* in it for this long and nothing about any single execution |
| **in flight** | `occupancy ÷ elapsed`, over the threads there were — **executions of this operation running at once**, averaged over the ticks where any were | **a property of your load, not of your code** — see below. It is the number that turns occupancy back into real cost: 100 s of waiting at 15 in flight is a convoy to break up; at 1.7 it is steady contention to design out |
| **calls** | exact, counted by the hook | a share cannot tell *200M calls at 8 ns* from *1000 calls at 1.6 ms*, and those want opposite fixes |
| **hits** | samples that caught this operation | the evidence behind the share |
| **noise** | `1/√hits` — the error chance alone gives | **if two rows differ by less than their noise, they are not ranked, they are tied** |
| **impl/call** | `occupancy ÷ calls` — implied duration per execution | the smell test you can apply and the tool cannot. An operation you know is 20 ns showing 500 ns is stalling on something |
| **over 1t** | occupancy inside executions that outlived a tick | for a label claiming nanoseconds this is four orders of magnitude out. See the verdicts below |

Operations that were never sampled are folded into one line rather than printed as a screen of
zeroes — but a *called* operation with no samples is itself a finding, so the count and the names
are kept.

### `in flight` counts executions, not the threads spent on one of them

This column used to be called `threads`, and for a fine operation the two are the same number — but
they are not the same question, and the name was the wrong one of the two.

A fine operation is atomic and never leaves the thread that entered it: a body that suspends or
hands work off is, by construction, not fine. So one thread inside the label *is* one execution of
it. `3.28/8` above means **3.28 executions of `flushBatch` were running at once, out of 8 threads** —
3.28 unrelated pieces of work, on 3.28 different threads.

What it emphatically does not mean is *"this operation used 3.28 threads"* in the sense of one piece
of work spread over three. That quantity is **parallelism** in its textbook sense — `work ÷ span` for
one execution — and it cannot exist in this tier at all, because it needs an execution to have an
identity that a second thread can be handed, and a fine operation is an integer in a thread's slot.
The two are factors of one product:

```
threads inside an operation = executions in flight  ×  parallelism per execution
```

Here the second factor is 1 by construction, so the product collapses and either reading gives the
same number. It stops collapsing the moment a coarse span can cross a thread, and then eight threads
in a label means either *eight requests, each serial* or *two requests on four threads each* — the
same 8, opposite fixes. The fine tier measures the first factor. Only a coarse context can measure
the second.

### And the first factor is about your load, which is why it is printed as a ratio

Here is the objection this column has to survive: **it is not a property of your code.** Sales bring
twice as many clients tomorrow and it doubles, with not a line changed. That is Little's law,
`L = λ·W` — the mean number in a system is the arrival rate times the time each one spends there.

Two corrections to that, because the loose version of it is wrong in both directions:

**It is not `L`.** Little's law is the mean over *all* time; this column divides by the ticks where
the operation was occupied, not by every tick. Writing `p_active` for the fraction of ticks it was
occupied at all:

```
column = λ · W · parallelism / p_active
```

For a label that is nearly always busy the two coincide. For a rare one the column is several times
`L`, which is the right behaviour — it answers *"when this ran, how many ran at once"* and not *"how
much of the run was this"*, which is what `share` is for.

**And it cannot grow forever.** There are only so many threads, so past saturation the extra arrivals
do not go inside the label — they queue outside it, or `W` grows:

```
threads inside = min( λ · W · parallelism ,  P )
```

So below the ceiling the column tracks your arrival rate, and at the ceiling it stops tracking the
load altogether and reports your pool size. Both regimes are about the deployment. **Neither is
about the operation.**

The ratio is what tells you which one you are looking at, and it is the only part of the column that
survives the objection:

| reading | regime | what it means |
|---|---|---|
| `3.28/8` | below saturation | tracking your load. Not a finding about this operation |
| `7.9/8` | pinned at the ceiling | the pool is inside this label — a finding, but about the pool |

It is in the table at all because `elapsed` cannot be computed without it: `elapsed = occupancy ÷ in
flight` is the one thing that turns summed thread-time back into wall time.

---

## The coarse table

Present only if you placed a coarse label. It answers the question the table above structurally
cannot: **how long did one execution take.**

```
coarse operation           executions       mean        p50        p90        p99        max  waiting inside   working   in flight
----------------------------------------------------------------------------------------------------------------------------------
request                     3,203,348    29.9 us    20.5 us    45.1 us   180.2 us   17.01 ms     0.0%   1.00      1.00      7.96/8
----------------------------------------------------------------------------------------------------------------------------------
  request was: flushBatch 38.7%, validateRecord 37.9%, parseRecord 10.3%, unlabelled 7.2%, indexRecord 5.9%
```

| column | what it is | what to watch for |
|---|---|---|
| **executions** | completed executions, counted exactly | not sampled. If this disagrees with what you think ran, a label is leaking |
| **mean, p50, p90, p99, max** | **measured**, two timestamps per execution | the only numbers in the whole report that are not sampled. Percentiles come from a log-bucket histogram: **at most 12.5% high, never low** |
| **waiting** | that gap as a share | 0.0% here because the demo never blocks. On anything with I/O or a lock it is the finding |
| **inside** | threads in one execution at once, a parked one counted in full | what a request *ties up*. 1.00 here because this demo hands nothing between threads |
| **working** | of those, the ones a sample caught on a CPU | what splitting the work *bought*. `working = inside × (1 − waiting)` |
| **in flight** | executions at once, over the threads there were | the same load-not-code caveat as the fine table's column |
| **`… was:` line** | the cross-tabulation | which fine operations ran under this one. Neither tier produces this alone |

**Why percentiles exist here and nowhere else.** A share is a fraction of time and has no
distribution — that is why the v0.1.0 notes say *no percentiles, ever*, and for the fine tier it
remains true. A coarse execution has two timestamps, so it has a duration, so a thousand of them
have a distribution. The histogram is 320 log buckets, 2.5 KB per type per thread, and a percentile
is reported at the **top** of its bucket so it can be high but never low. Erring upward is the right
direction for a latency figure.

**Why the label goes on a batch and not a pass.** In the demo above one pass is about 700 ns and a
context costs tens of nanoseconds to allocate and stamp, so the label goes around a batch. That is
the tier boundary — `d ≥ max(800 ns, 4 µs × share)` — and it is why the fine tier exists at all.
Put a coarse label on something too small and you are measuring the instrument.

### `inside` and `working` are one measurement answering two questions

They are the same sum over the same ticks, split by whether the thread was on a CPU. Both are here
because the two answers lead to different decisions, and picking one would have thrown away a
question somebody needs.

Take a request that fans out to helpers while its caller waits on the join. Measured on the bench,
one driver against eight helpers:

```
inside   5.24     five threads are in this execution
working  4.24     four of them are doing something
```

**`working` is the speedup answer.** Splitting this request made it about 4× faster than doing it
serially. This is `work ÷ span` — the work-span model's `T₁/T∞`, and what the literature means by
*parallelism*. Invert it through Amdahl to ask whether more threads would help. The caller parked on
its own join contributes nothing here, correctly: it made the request no faster.

**`inside` is the capacity answer.** That sleeping caller is a real thread and is not available for
anything else. With sixteen threads and five tied up per request you can serve three requests at
once, not four. This is also the number that keeps the identity exact —

```
threads inside a coarse type = executions in flight  ×  inside
```

— because `in flight` counts an execution whether or not its threads are running, and a
factorisation has to count both sides the same way.

**They differ by exactly the `waiting` column**, which is why the three sit together:
`working = inside × (1 − waiting)`. A wide gap between them means the request is waiting on itself.

**And `working` is what relates `busy/exec` to the span:** `busy/exec = working × mean`. On a
fanned-out operation `busy/exec` is therefore *larger* than the span — six threads inside a 4 ms
search is 25 ms of thread-time, which is right and looks alarming the first time. The old shortcut
`mean − busy/exec = waiting` only ever held because nothing could cross a thread; the `waiting`
column is the reading that holds either way.

**Both read 1.00 until a context crosses a thread**, as in the demo above: every occupied execution
is occupied by the one thread that created it. That made it a known answer to calibrate the instance
stamping against before propagation existed, and it is still what pins the same-thread case now that
it does.

**Two caveats on `working`, and both are real limits rather than defects.**

*It reads low when the pool is saturated.* What gets measured is
`min(what the code could do, threads actually free)`. On the bench, seven drivers against the same
eight helpers leaves nothing to fan out to and `working` falls back to about 1. That is the truth
about *that run*, not about the code.

*It reads high on anything that waits outside the JVM, and the report will tell you so.*
`working` is built on `Thread.getState`, and **Java reports a thread inside a native call as
`RUNNABLE`** — a socket read, a file read, an `epoll` wait. Measured on PostgreSQL over a socket, the
column read **2.85** while the operating system said the whole process used 1.03 s of CPU in a 20 s
run: **55× more CPU credited than the machine ever spent**. When the measured duty cycle cannot
support the number, the column prints it over its ceiling —

```
inside   working
  3.83  2.83/0.04     ← reads 2.83, the duty cycle supports 0.04
```

— followed by a warning block naming each type. `inside` is unaffected: it counts threads in the
execution whatever they were doing.

*And it reads high as a speedup.* `working` is `work ÷ span` **of the run it measured**, and
parallelising usually costs extra work — per-slice setup, cache pressure, an all-core clock below
single-core boost. Measured on Lucene, the same search at one thread and at eight:

```
1 thread    span 14.04 ms   busy/exec 14.04 ms   working 1.00
8 threads   span  4.01 ms   busy/exec 25.31 ms   working 6.32
```

The speedup is **3.50×**. `working` says **6.32**, because the eight-thread run spends 1.80× more
total CPU to answer the same query. So `working` bounds the speedup from above and can overstate it
by a lot. Only re-running at a different thread count measures what parallelism actually bought —
`ideas.md` item 22, which this column does not replace.

### `N% of the thread-time inside coarse executions was inside one that had ALREADY BEEN CLOSED`

The sibling of the line below and the **opposite fault**, which is why they are stated separately.
That one is attribution *lost* — work that reached no span at all — and wrapping the hand-off brings
it back. This one is attribution *invented*: a thread still working under a request that has already
finished, so the time is billed to an execution that no longer exists.

It happens when work is handed to another thread and never waited for. The request closes, the work
carries on, and everything about it looks plausible — the operation name is right, the numbers are
the right shape, and nothing else in the report can tell. The balance check reads a thread's own slot
and finds it clean; the floor check reads sizes; the line below reads work with no context.

**That time is excluded from every number in the coarse table** rather than folded in. Crediting it
would let `busy/exec` exceed the `mean` span it is supposed to sit inside, which cannot happen and
would read as a finding rather than as a fault.

Two causes, and they want opposite fixes:

- **work was forked and not joined** — propagate only what the request actually waits for. A task
  the request does not join is not part of it, however much it feels like it
- **the span is closed too early** — `Profiler.exit(op)` is running before the work it covers has finished

Under `strict` this stops the session, which is the same treatment a leaked label gets and for the
same reason: both report a number that is not merely imprecise but false. It needs a share *and* a
minimum count to fire, because a helper finishing a few microseconds late is a harmless race and
stopping a correct run over three samples is the loudest wrong answer this tool can give.

### `N% of labelled thread-time was inside NO coarse span`

The one line that can see **work escaping its context**. Nothing else in a single run can: the floor
check sees labels that are too small, the balance check sees contexts left open, and neither sees a
context that is simply not where the work is.

It has two readings and **the report gives both, because it cannot tell them apart**:

- *you bracketed part of your program coarsely and not the rest* — legitimate, and common;
- *work is escaping onto threads your context never reached* — in which case those threads' time is
  missing from the operation's `busy/exec` and shows up as its `waiting`.

What separates them is whether the operations it names are ones you expected to be inside a span.
That is a question about your program, so the report states the measurement and stops.

Measured across the three trials, which is how the threshold was set:

| | outside every span | verdict |
|---|---|---|
| Calcite — one thread, everything under `plan` | silent | correct |
| Netty — synchronous pipeline | 0.0%, 3 ms | below the 1% floor, silent |
| **Lucene at 8 threads** — search fans across a pool | **88.5%**, naming `clause:prefix`, `clause:phrase`, … | the escaped work |
| Lucene at 1 thread — same code, no hand-off | silent | correct |

**It is more sensitive than the `waiting` column**, and that is the point. On the same Lucene run
`waiting` reads 24.5% while this reads 88.5%, because the calling thread is doing plenty of work
itself — the gap in the span understates how much went elsewhere.

Below 1% it says nothing, for the same reason the report does not chase negligible operations: at
that size it cannot be where the work went, and a check that cried wolf there would spend its
credibility.

### The one thing to know before trusting `waiting`

**If your operation hands work to other threads, `waiting` counts that work as waiting.** A context
lives on the thread that created it, so `busy/exec` only ever counts samples taken on that thread.
Measured on Lucene, whose search fans out across a pool — same code, same label:

| threads | mean | busy/exec | waiting |
|---|---|---|---|
| 1 | 14.88 ms | 14.87 ms | **0.0%** |
| 8 | 4.10 ms | 3.10 ms | **24.5%** |

Neither number is wrong: the calling thread really is blocked for a quarter of the search. What the
report cannot say is that the *request* was not idle — it was working, on threads the context never
reached. Until propagation exists, read `waiting` as *"the thread holding this context was not
running"*, which is what it measures, and not as *"this operation was idle"*.

Same-thread operations — a query planner, a synchronous handler chain — are unaffected, and both read
0.0% correctly.

---

## The warnings, and what to do about each

### `! … below the 50 ns floor`

```
  ! parseRecord: 288,514,362 calls at under 29.3 ns each, below the 50 ns floor.
    Label the enclosing loop instead and divide by the iteration count.
```

The label is on something too small for the instrument to describe: the hook is a visible fraction
of it, the sampler reads short operations 5–9% low, and C2 can move work across the boundaries of
adjacent short labels without leaving a trace in the numbers. In the demo this cost an operation
**95% of itself**.

**What to do:** `op(id, times = n) { … }` around the enclosing loop, and the report speaks in your
units. **Not fatal** — it warns and the run finishes, because the check is machine-dependent: the
same label reads 17.8 ns at one thread and 55.4 ns at eight on one laptop.

### `! … under the N a coarse label needs here`

The tier boundary, checked. A coarse label costs about 40 ns per execution to allocate, timestamp
and stamp, and there are two ways that can be too much — **which one bound decides what you are being
told**, because the remedies differ:

| bound | the complaint | example |
|---|---|---|
| `d ≥ 800 ns` | the number would describe the instrument as much as your code | a label on a 200 ns operation |
| `d ≥ 4 µs × share` | the *program* you measured is not the one you started with | a 1 µs operation holding 62% of the run: accurate per execution, but the contexts alone cost over 1% of everything |

**Unlike the fine floor above, this check is exact.** That one has to *infer* an operation's duration
from `hits ÷ calls`, so it carries a statistical bound and a bias allowance to avoid accusing an
innocent label. Here the duration is measured, so no slack is needed. The only sampled input is the
share, and it is taken a standard error low before the second condition can fire.

**What to do:** use a fine label — `op(id) { }` — or move the coarse label outward to a batch of
these. A warning, never fatal.

### `! N executions lasted over a tick`

An operation was caught still running a whole millisecond later. Either it was waiting, or it really
does take that long and belongs in a coarser label — opposite responses, so the report says which:

- *…and N% of those long samples caught the thread parked or blocked — it is waiting, not working.*
- *…and N% caught the thread runnable — the share is honest and the operation wants a coarse label
  for its per-execution statistics.*

Judged against a **machine floor**, so an operation is only named if it is well above what the
machine was doing to everything at once.

### `! N labels were still open at a point the caller said should be quiescent`

**Read this one first when it appears, because it invalidates rows above it.** In the specimen it
says 35,923 — and `flushBatch` sits at the top of the table with 44.25%.

That 44% is manufactured. The demo leaks `flushBatch` on purpose, every thousandth pass, and every
sample taken on that thread after the leak was billed to it. Nothing about the number looks wrong:
it is plausible, it is stable, it is at the top. This line is the only thing in the report that says
so.

**What to do:** `op(id) { }` has a `finally` and cannot leak — prefer it. Where you must use
`enter`/`exit`, call `Profiler.expectBalanced()` at a point the thread should be quiescent. Under
`strict` (the default) the first leak stops the session outright.

### `! N threads exited without Profiler.release() and were reclaimed`

A slot left behind by a dead thread reads as an *idle* thread forever and inflates the denominator
every share is taken over. The sampler reclaims those once a second — but only after a garbage
collection has cleared the thread, so in a process that does not collect they accumulate.

**What to do:** call `Profiler.release()` when a thread finishes.

### `! N threads arrived past the 1024-slot ceiling and were NOT SAMPLED`

Their occupancy is missing from every number above, including the denominators. The sampler watches
at most 1024 threads at once; indexes are recycled as threads die, so this is a limit on
*simultaneous* threads, not on how many the process creates.

### `PROFILING STOPPED`

The session ended early and the numbers below it are evidence for the verdict, not a result. One
condition causes it: a leaked label under `strict`. Pass `strict = false` for labels on code you do
not own and cannot fix — the leak is then still counted and still printed.

---

## The last word, which is not a warning

```
A share is where time went. It is not what removing the operation would save.
```

Printed at the foot of every report because the one time it mattered it was worth a factor of 275.
In the Calcite trial an operation holding 46% of the time turned out to be worth **275× when
removed**, because it was creating work for every other operation as well as doing its own. A
profiler that prints a ranked list invites exactly one response — delete the top row — and the two
questions can be orders of magnitude apart.

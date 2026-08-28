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

## The table

```
operation                     share  occupancy  waiting   elapsed threads         calls     hits  noise  impl/call over 1t
flushBatch                  44.250%    38.54 s     0.0%   11.75 s    3.28   288,514,362    38319  0.51%   133.6 ns   0.01%
validateRecord              41.222%    35.91 s     0.0%   11.68 s    3.07   288,514,362    35697  0.53%   124.5 ns   0.00%
parseRecord                  9.478%     8.26 s     0.3%    6.14 s    1.34   288,514,362     8208  1.10%    28.6 ns   0.27%
indexRecord                  5.050%     4.40 s     0.0%    3.73 s    1.18   288,514,362     4373  1.51%    15.2 ns   0.05%
```

| column | what it is | what to watch for |
|---|---|---|
| **share** | this operation's slice of all **labelled** samples | the denominator is labelled samples, not every sample — so adding a label somewhere else does not move this one |
| **occupancy** | `hits × step`, as summed thread-time | **absolute**, so unlike share it does not move when a label is added, moved or removed. This is the column to compare between two runs |
| **waiting** | the share of those samples whose thread was parked, blocked or waiting | a thread the scheduler merely preempted still reads runnable, so this is waiting that *another thread* caused. Blind to native waits |
| **elapsed** | wall clock with at least one thread inside | not latency: it is every execution's interval unioned, so it says the operation had *somebody* in it for this long and nothing about any single execution |
| **threads** | `occupancy ÷ elapsed` — mean concurrency while it was running | the number that turns occupancy back into real cost. 100 s of waiting at 15 threads is a convoy to break up; at 1.7 it is steady contention to design out |
| **calls** | exact, counted by the hook | a share cannot tell *200M calls at 8 ns* from *1000 calls at 1.6 ms*, and those want opposite fixes |
| **hits** | samples that caught this operation | the evidence behind the share |
| **noise** | `1/√hits` — the error chance alone gives | **if two rows differ by less than their noise, they are not ranked, they are tied** |
| **impl/call** | `occupancy ÷ calls` — implied duration per execution | the smell test you can apply and the tool cannot. An operation you know is 20 ns showing 500 ns is stalling on something |
| **over 1t** | occupancy inside executions that outlived a tick | for a label claiming nanoseconds this is four orders of magnitude out. See the verdicts below |

Operations that were never sampled are folded into one line rather than printed as a screen of
zeroes — but a *called* operation with no samples is itself a finding, so the count and the names
are kept.

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

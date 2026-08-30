# Trial 4 — PostgreSQL over a socket

The first workload in this project with any waiting in it, and it found a defect in a column shipped
four commits earlier.

Calcite plans on one thread and is pure CPU. Lucene's index is page-cached, so its clauses read
`waiting 0.0%`. Netty's request is loopback and `mean − busy/exec` reads 0.0% — [plan.md](plan.md)
calls it the negative control. So the coarse tier's headline claim, *"`mean − busy/exec` is the
waiting, which is the one thing a fine label can never tell you"*, had never been checked against
foreign code whose answer was anything but zero. The only waiting with a known truth anywhere here
was our own `ContendedLock` in the bench.

## The setup

PostgreSQL 17 in a container on port 55432, driven by plain `docker run` rather than Testcontainers —
which would put its own threads inside the process being profiled, and every share in the report is
taken over the threads the sampler can see. Five million rows generated server-side, reused between
runs.

A request fans eight queries across a HikariCP pool and an executor wrapped with `.propagating()`,
then merges. Fine labels separate `acquire` — this program queueing on itself — from `execute`, which
is the database taking its time. Same shape as the Lucene trial, and deliberately so: there the
helper threads compute, here they are stopped.

## What it found

Run 2026-08-30, 20 s, 8 workers, fan-out 8, 1,156 requests at 17.30 ms mean. Clock **201.4%** of
nominal over the window, 13 samples, none failed — and it hardly matters, because the finding is a
ratio between two figures from the same run.

```
wall clock of the measured run                  20.01 s
thread-time available to the workers           160.06 s
CPU the process actually used                    1.03 s   <- the OS, which sees native waits
CPU the profiler attributes to requests         56.99 s   (working 2.85 x 17.30 ms x 1,156)

the profiler's figure is 55.26x the operating system's
```

**`working` counts a thread stopped in a socket read as working.** Java reports a thread inside a
native call as `RUNNABLE`, and `working` is built on thread state, so eight threads doing nothing but
waiting for another process were reported as 2.85 threads on a CPU. The true figure is about 0.05.

The fine tier says the same thing more starkly: `execute` holds **99.972%** of the run at **6.25 ms
per call** and reads **`waiting 0.0%`**.

**The only waiting the tool saw was the Java-level park.** `inside 3.85` against `working 2.85` — the
difference of exactly 1.0 is the calling thread parked in `invokeAll`. Everything the operating
system was waiting for was invisible.

**`inside` is unaffected and correct.** It counts threads in the execution whatever they were doing.
`working` and `waiting` are wrong together, in the same direction, by the same amount.

**And the tool already knew.** Its own duty-cycle header read *"threads were on CPU 0.63% of sampled
wall time"* and *"at most 100.00 pp of any share is a thread waiting rather than working"* — the
phase 3.5 bound going honestly vacuous, in the same report, twenty lines above a column that
contradicted it. The measurement was there; nothing consulted it.

## What was done about it

The coarse table now prints `working` as `2.83/0.04` when the measured CPU duty cycle cannot support
it — the same value-over-its-ceiling idiom `in flight` already uses — with a warning block naming
each type and saying why. It states both readings rather than correcting one, because the column is
right on a CPU-bound operation and it is the reader who knows which they have.

The ceiling is `inside × labelledDuty`, from `getThreadCpuTime`, which counts time on a processor and
does see through the JNI boundary. It is a run-wide figure applied to one operation, exactly as the
fine tier's *"at most N pp of any share"* line already is, so it allows a factor of 1.5 before
complaining. That slack earns its place: Lucene reads `working 6.40` against a ceiling of **6.40**,
and Calcite `1.00` against **0.97** — both at their ceiling, neither warned about.

| | labelled duty | `inside` | `working` | warned |
|---|---|---|---|---|
| PostgreSQL over a socket | **1.02%** | 3.83 | **2.83 / 0.04** | yes |
| Lucene, 8 threads | 96.40% | 6.64 | 6.40 | no |
| Calcite | 96.94% | 1.00 | 1.00 | no |

## What is still open

The warning says the number cannot be trusted; it does not produce a better one. Measuring CPU per
label directly would — `getThreadCpuTime` attributed per operation instead of thread state — but the
report already measures that walk at 130.9 µs, so it cannot run per tick. Recorded in
[ideas.md](ideas.md); it is a larger piece of work than a bound.

## Running it

```
gradlew :trial-jdbc:run --args="--seconds=20"
gradlew :trial-jdbc:run --args="--seconds=20 --propagate=off"   # the pre-phase-5 reading
gradlew :trial-jdbc:run --args="--down"                          # remove the container
```

Needs a running Docker daemon. The first run generates the table and takes a minute or two longer.

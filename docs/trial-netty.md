# Trial 3 — Netty

The third trial, and the first one chosen for its **concurrency shape** rather than its identity
problem. Calcite was single-threaded. Lucene gave eight worker threads all running the same work
flat out. Netty gives a handful of long-lived event loops carrying many short tasks, with time that
is mostly not CPU — which is the first foreign workload where the thread-state column built in
phase 6 has anything to say, and the first that can show it saying the wrong thing.

Harness: [`trial-netty/`](../trial-netty). Status: **done through step 3 — qualified, labelled, and
A/B'd against the uninstrumented workload.**

---

## 1. The workload

An HTTP gateway on loopback, server and load generator in one JVM. Four event loops for the server,
two for the client, sixteen connections with eight requests in flight on each, so the server is
never idle waiting for the client.

The pipeline, in order:

| handler | class | what it does |
|---|---|---|
| `HttpServerCodec` | Netty's | |
| `HttpObjectAggregator` | Netty's | |
| `ExchangeHandler` | own | wraps the request in the object the chain speaks |
| `AuthHandler` | own, **distinct** | bearer-token parse, tenant lookup |
| `RouteHandler` | own, **distinct** | linear scan over an eight-entry route table |
| `policy:geo` | `PolicyHandler` | hashes 24 body bytes |
| `policy:quota` | `PolicyHandler` | hashes 256 |
| `policy:abuse` | `PolicyHandler` | hashes 1,024 |
| `policy:experiment` | `PolicyHandler` | hashes 48 |
| `RenderHandler` | own, **distinct** | builds the JSON response |

**The four policies share one class**, which is the identity problem, and the three distinct classes
are the **control** — a flame graph can name those, and whatever the labels add there is not a
difference the tool invented. [case.md](case.md) keeps an honest section on exactly that.

This is not a contrivance. A chain of configured policy objects sharing one implementation is how
gateways, filter chains and rule engines are ordinarily written.

## 2. Qualification — what a flame graph says about this

Step 1 of [the procedure](plan.md#running-one--what-the-first-two-trials-say-to-do-in-order):
*qualify the candidate before instrumenting it.* JFR at a 1 ms period over the measured window only,
45 s, 9,099 execution samples.

**Throughput is real:** 125,653 req/s, 5.66 M responses in 45 s. (Across runs: 101k–162k, this
laptop's documented 2.2× drift with run length — see [findings.md](findings.md#the-machine).)

### The inclusive view is a spine, exactly as Calcite's was

| frame | inclusive |
|---|---|
| `AbstractChannelHandlerContext.fireChannelRead` | **99.14%** |
| `DefaultChannelPipeline$HeadContext.channelRead` | 98.69% |
| `ByteToMessageDecoder.channelRead` | 98.62% |
| …nine more frames between 96% and 98% | |
| `AuthHandler.channelRead0` | 83.44% |
| `RouteHandler.channelRead0` | 82.83% |
| `PolicyHandler.channelRead0` | 82.44% |

A pipeline is a chain, so **each handler's inclusive time contains every handler after it**. The
numbers are not merely uninformative, they are not even subtractable in the usual way: `AuthHandler`
at 83.44% is mostly the four policies and the renderer downstream of it.

### The four policies are one number — with a qualification, below

> `PolicyHandler.run` — **60.30%**

That is all four policies together as an ordinary flame graph reads it. The
domain has four policies with costs that differ by design — 24, 48, 256 and 1,024 bytes hashed — and
the stack has one frame for the lot. Same shape as Lucene's four `TermQuery` clauses sharing
`TermScorer`, arrived at from a completely different direction.


### But a recursion-aware reading *can* partly separate them — checked, because it would weaken this

Netty's pipeline **nests**: each handler calls `ctx.fireChannelRead` inside its own frame, so the
fourth policy runs with four `PolicyHandler` frames on the stack and the first with one. Counting
occurrences per collapsed stack is a reading an ordinary flame graph does not do but a careful
analyst could, and it has to be tried rather than assumed.

**It works, and it is not nothing.** 8,641 of 10,570 samples carry a `PolicyHandler` frame, and they
sort cleanly by depth (two frames per policy — Kotlin's generic bridge method — so 2/4/6/8 is
chain positions 1/2/3/4):

| chain position | policy | flame graph by nesting | our labels |
|---|---|---|---|
| 1 | `policy:geo` | 1.72% | 3.72% |
| 2 | `policy:quota` | 10.60% | 22.18% |
| 3 | `policy:abuse` | **56.24%** | **68.76%** |
| 4 | `policy:experiment` | **31.43%** | **5.35%** |

*(both columns normalised over the four policies, since the denominators differ)*

**So the claim "a flame graph gives one number for four policies" is too strong, and this document
made it.** With recursion-aware analysis it gives four numbers, gets the expensive one right, and
gets the ranking of the first three right.

**Where it fails, and it fails hard.** Counting nesting depth gives *inclusive-from-that-position*,
not self time. Nothing sits below policies 1–3 except the next policy, so those three come out
roughly self — but everything downstream of policy 4 (the renderer, the response write, Netty's
whole outbound path) is inside four policy frames and lands on `policy:experiment`. It reads
**31.43%** for the **cheapest policy in the chain**, which really holds 5.35%. A reader would go and
optimise a 48-byte hash.

The three clean positions also disagree by about 1.7× in ratio — JFR over-weights the largest and
under-weights the small ones. Not explained. A counted `int` loop has its safepoint polls elided by
HotSpot, so a stack sampler cannot interrupt inside one, and all four policies are counted `int`
loops — that is the obvious suspect and it is **not** established here.

**The honest verdict.** This is a weaker case for the tool than Lucene's, where four `TermQuery`
clauses shared a frame with no positional difference at all and the stack genuinely had nothing.
Here the stack has *something*, and the something is right about the biggest cost and badly wrong
about the last one. Recorded in [case.md](case.md) under where the other tools do better than we
first claimed.

### Self time is somebody else's bounds check

| leaf | self |
|---|---|
| `MemorySessionImpl.checkValidStateRaw` | **59.10%** |
| `WrappedByteBuf.setBytes` | 2.13% |
| `DecimalDigits.uncheckedGetCharsLatin1` | 2.11% |
| `PlatformDependent.hashCodeAsciiSafe` | 1.76% |

Nearly three fifths of the flame graph's self time is the JDK's foreign-memory validity check, which
Netty 4.2's adaptive allocator goes through on buffer access. **Not one of the pipeline's own
handlers appears in the self-time table at all**, at any depth, despite hashing up to a kilobyte per
request four times over at 125,000 requests a second.

Whether that 59% is real or is safepoint bias is *not established here* and should not be asserted:
an earlier run of the same workload put `RefCnt$VarHandleRefCnt.isLiveNonVolatile` at 40–46% in the
same slot. Two different tiny internal methods each taking half the self time between runs is the
signature of bias, but the two runs also differed in how the recording was buffered (below), so this
is a question to settle rather than a finding to bank.

### Depth truncation is a problem here, which was not predicted

14.3% of stacks hit JFR's 64-frame limit, with a **median depth of 58**. The root end is what gets
dropped, so the frames losing samples are the event loop's own entry points — `ThreadExecutorMap$2.run`,
`SingleThreadIoEventLoop.run`.

[plan.md](plan.md) listed depth as the *compiler's* differentiator and expected Netty to test
concurrency instead. Netty has both: a pipeline plus Netty's internal delegation puts twenty frames
above the first line of application code.

### Verdict

**Qualified.** All four criteria hold: hot short operations executed millions of times; the domain's
identity is not the stack's identity (four policies, one frame); genuinely concurrent on foreign
code; and handlers are the extension point, so nothing needs forking.

## 3. A measurement error found in our own harness, before it mattered

The first two qualification runs gave 1,071 samples at 20 s and 1,750 at 60 s — three times the run
for 1.6× the samples. The obvious reading is that JFR throttles its sampler under load, and that
would have gone into [case.md](case.md) as a failure of somebody else's tool.

It was ours. `recordExecutionSamples` built a `Recording` with default settings, which keeps events
in a **wrapping in-memory buffer**, so a long run silently discards its early samples. Written to
disk, the same runs give **3,887 and 12,051** — and the delivered rate is flat, 194/s against 201/s,
which is what a fixed sampling period should look like.

Fixed in [`trial-common`](../trial-common), so both earlier trials get it too. **What this means for
the Lucene and Calcite records:** the rate is constant either way and both workloads are steady, so
the *shares* those trials computed are over a fair sample and stand. Any sample *count* recorded
before the fix is a floor rather than what JFR produced.

Worth stating plainly because the discipline is the point: the run that looked like an indictment of
another tool was an indictment of our harness, and the only reason it was caught is that
"three times the run for 1.6× the samples" is not a shape a fixed period can produce.

## 4. The labels, and the number the flame graph could not give

One label per handler, placed inside the handler's own body — no wrappers, no `enter`/`exit`, the
plain lexical form, because in Netty the extension point *is* our code. The label covers the
handler's own work and **not** the `fireChannelRead` below it, which is what turns an inclusive
number into a self number.

45 s, 7.0 M requests, 156,254 req/s, 26.0 s of labelled occupancy.

| operation | share | occupancy | per call | bytes it hashes |
|---|---|---|---|---|
| `policy:abuse` | **62.169%** | 16.17 s | 2.3 µs | 1,024 |
| `policy:quota` | **20.052%** | 5.22 s | 743.9 ns | 256 |
| `render` | 7.498% | 1.95 s | 278.2 ns | — |
| `policy:experiment` | **4.836%** | 1.26 s | 179.4 ns | 48 |
| `policy:geo` | **3.366%** | 875.7 ms | 124.9 ns | 24 |
| `auth` | 1.216% | 316.2 ms | 45.1 ns | — |
| `route` | 0.862% | 224.2 ms | 32.0 ns | — |

**The flame graph's one number was 60.30%.** It is four numbers, and they span **18×** — from 2.3 µs
down to 125 ns. Every request pays all four, so a reader given the 60% has no way to know that one
policy is most of it and two of them together are under a tenth.

Reproducible: across two runs the shares moved 61.6→62.2, 19.9→20.1, 5.2→4.8, 3.6→3.4.

### The configuration predicts the measurement, to 6.7%

The strongest check available here, and the profiler knows nothing about it. Each policy hashes a
configured number of bytes, so cost per call should be a straight line: a fixed cost plus a rate per
byte. Fitted on the four measured durations:

> **57.2 ns fixed + 3.067 ns per byte hashed**

| policy | effective bytes | measured | predicted | error | its own noise | error ÷ noise |
|---|---|---|---|---|---|---|
| `policy:geo` | 24 | 131.1 ns | 119.6 ns | **+8.8%** | 3.29% | 2.7× |
| `policy:experiment` | 48 | 175.5 ns | 188.5 ns | **−7.4%** | 2.84% | 2.6× |
| `policy:quota` | 248 | 764.6 ns | 762.9 ns | **+0.2%** | 1.36% | 0.2× |
| `policy:abuse` | 800 | 2348.3 ns | 2348.3 ns | **−0.0%** | 0.78% | 0.0× |

The effective byte count is not the configured one: bodies run 128–2,048 bytes and a policy hashes
`min(depth, body)`, so the 1,024-byte policy really averages 800. Using the configured figure would
have made the fit look worse, and that would have been our arithmetic rather than the tool's.

**The two policies that hold real share land on the line exactly — 0.2% and 0.0%.** Those are the
rows anyone would act on, at 20% and 62% of the run.

**The two smallest miss by ~8%, and it is systematic rather than noise.** At 2.6–2.7× their own
noise floors, and the *signs repeat across every run* — `geo` reads high and `experiment` reads low
in all three, at 25 s and at 45 s. Something real is going on and it is small.

The likeliest explanation is the model, not the instrument: a straight line over a 33× range of byte
counts has one intercept to spend on per-call fixed cost, and that cost is a larger fraction of a
131 ns policy than of a 2.3 µs one. A concave true curve would produce exactly this pattern —
the line passing above the smallest point and below the second. *Not verified*, and it is not worth
verifying: by the [accuracy principle](profiler.md#how-accurate-this-has-to-be-and-where-that-budget-goes)
an 8% miss on a label holding 3% of the run cannot change anybody's next move, while the same fit is
exact on the labels that can.

**What the check earns, stated precisely:** the labels' per-call durations are predicted by a
quantity the profiler has no access to, to within 0.2% on the operations that matter. That is the
Calcite and Lucene cross-check in a third form — there we compared against another profiler, here
the workload's own configuration is the second opinion.

**And the first version of this section quoted the wrong number.** It reported "worst residual 6.7%"
from a single run, which read as *the fit is good to 6.7%* when the truth is *the fit is exact where
it matters and systematically off on the two smallest*. A single worst-case figure hid both the
fifteen-fold spread in hit counts and the fact that the misses repeat. The check now prints each
residual against that label's own noise floor.

### The floor check earned its place, on a real label

> `route`: 7,011,646 calls at under 41.0 ns each, below the 50 ns floor.

Correct, and it is a label a reasonable person would place. Route lookup is a scan of eight short
strings and it genuinely is that cheap. Under `strict` this would have stopped the run — which is
the behaviour [ideas.md](ideas.md) item 12 now proposes to downgrade to a warning, and this is a
second instance of the case that argues for it.

## 5. What the thread-state column says about event loops — the prediction, and it was right

Recorded in this document before the run: *the waiting column will read 0%, because a selector wait
happens in native code and a thread in native code reports `RUNNABLE`.*

It reads 0.0% on **every** labelled operation, which is unremarkable — the handlers are pure
computation and genuinely never wait. The finding is in the other 85%:

> labels cover 26.0 s of the 180.00 s of thread-time observed (14.5%); 154.31 s was outside every
> label
>
> **of that unlabelled time, 0.0 ms was a thread not runnable (0.0%)** and 154.31 s was a thread
> runnable with no label on it
>
> threads were on CPU **65.85%** of sampled wall time

Put together: **about 61 seconds of this run was not on a CPU, and the thread-state column reports
zero milliseconds of waiting.** Not a small discrepancy — total blindness, on the workload shape the
column was built for.

The mechanism is exactly as predicted. `Thread.getState()` reports `RUNNABLE` for a thread inside a
native call, and an event loop parked in `epoll_wait`/`WSAPoll` is inside a native call. Java's
thread state cannot see through the JNI boundary.

**What this settles about the two instruments.** They are not two views of one quantity, they are
two different quantities, and each is blind where the other sees:

| | catches | misses |
|---|---|---|
| **thread state**, per operation | waiting another *thread* caused — a lock, a monitor, a park | native I/O waits; the scheduler preempting you |
| **duty cycle**, aggregate only | everything that is not on a CPU, including both of those | which operation it belonged to |

Neither is sufficient and neither is redundant, which was argued in phase 3.5 and is now
demonstrated on foreign code by the strongest possible case: 61 s in one column and 0 ms in the
other, for the same run.

**What it does not mean.** It is not an argument for extending the state read — there is nothing to
extend it with, since the JVM does not know either. It is an argument for the report saying which
kind of waiting it is talking about, and for the duty cycle not being treated as a legacy number now
that a per-operation column exists.

### And per-operation concurrency, on its first foreign workload

Every handler reads **1.00–1.14 threads** inside it at once, against four event loops. That is the
right answer and it is worth stating: the loops are independent, each at a different point in the
pipeline at any instant, so a given handler almost never has two loops in it simultaneously. A
number near 4 would have meant the loops were marching in lockstep, which would be a finding.


## 6. The A/B against the bare workload — and it took four attempts

Step 3 of the procedure: *do not let the instrumentation change the workload.* On Lucene a careless
wrapper made the workload 13.3% slower and moved one clause from seventh to third, with every number
internally consistent. Only a comparison against the uninstrumented run can catch that.

Three arms, interleaved, order reversed every other round — ABBA rather than ABAB, because a
monotonic drift aliases straight onto an alternating order.

| arm | vs inert |
|---|---|
| inert — the branch, no hook | — |
| labels, no sampler | **−0.81%** |
| labels + sampler | **−3.97% ± 1.83%** (1 s.e. over 8 rounds) |

**Readable, at over twice its own standard error** — and this is the first A/B in the project that
has been. Phase 3's hook comparison and Lucene's were both reported as inconclusive because the
machine moved by more than the effect.

**What it says.** The hook is under 1% and inside its own noise; almost all of the cost is the
sampler thread, which spins and therefore takes a core. That is the documented trade and it is
behaving as documented. Nothing about the pipeline's shape changed: the shares reproduce run to run
(61.9/19.9/7.6/4.6/4.0 against 62.2/20.1/7.5/4.8/3.4), so this is not Lucene's failure in miniature.

### The three attempts that failed, which are the more useful part

**1. Raw means, server and client rebuilt per arm.** Inconclusive: within-arm spread 72–88% against
an 8.66% effect. Throughput fell from 164k to 58k req/s across four rounds as the laptop throttled,
so an arm's average was mostly a statement about *when it ran*.

**2. Normalising each arm by its own round's mean — and this produced an impossible answer.**
`labels + sampler` came out **21% faster** than inert. Instrumentation cannot speed a program up, so
the harness was wrong, and it was: `shutdownGracefully()` returns a future with a **default quiet
period of two seconds**, and nothing awaited it. Each arm was being measured while the previous
arm's seven event-loop threads were still winding down. The nonsensical sign is what made it
findable — a plausible-looking 5% would have been believed.

**3. Awaited shutdown, still rebuilt per arm.** Sign sane and the ordering monotonic (0%, −3.2%,
−10.2%), but **−10.16% ± 10.42%** — still inconclusive. Normalising by the round removes drift that
affects a whole round, and the remaining scatter was *between arms inside a round*: fresh sockets,
fresh threads, fresh TCP state every time.

**4. What worked: stop rebuilding anything.** One server, one client, one set of connections, alive
for the whole comparison, with a volatile flag and the sampler the only things moving between arms.
Within-arm spread is still 60–65% — the machine has not improved — but the effect now separates at
±1.83%.

**The lesson, and it generalises past this trial.** The variance that defeated three attempts was not
the machine; it was **the harness tearing itself down and rebuilding between measurements**. The cost
is that the inert arm now carries a volatile read the JIT cannot fold, so it is slightly dearer than
a genuinely bare build — but that read is in all three arms and cancels. Trading a known constant
bias for an order of magnitude less variance was the whole difference between a number and a shrug.



## 7. The friction list — what this trial says about the tool

Step 6 of the procedure, and it is the deliverable that gets dropped when the finding goes well.
Phase 3.75 was built entirely out of Calcite's version of this list.

**1. Nothing needed. That is the finding.** Calcite forced a fifteen-line helper and drove phase
3.75's `enter`/`exit`. Lucene needed careful wrapper delegation. Netty needed **`Profiler.register`
and `op(id) { }`**, and nothing else — because the handler bodies are ours. Third data point, and it
says the placement problem is a property of the *host's* extension points, not of the tool. A
library that hands you a method to implement is the easy case and it is also common.

**2. One label per *configured instance*, and the API gives no help getting it right.** Netty builds
a fresh pipeline per connection, so sixteen connections make sixteen `PolicyHandler` objects per
policy. The id belongs to the policy and all sixteen must share it. Getting this wrong gives sixteen
labels reading a sixteenth each — which does not look like a bug, it looks like a finding. Nothing
in the tool could have caught it. Same class of hazard as Lucene's misplaced label: **plausible and
silent.**

**3. `strict` had to be off, for the second trial running.** `route` is a scan of eight short strings
at 41 ns — genuinely below the floor, correctly flagged, and a label any reasonable person would
place. Under the default it stops the run. Two trials in a row have needed the switch off, which is
the evidence [ideas.md](ideas.md) item 12 now rests on.

**4. The unlabelled fraction needed explaining, and the report could not.** Coverage is 14.5% here.
That reads as *the labels miss most of the run* and it is a shape a first-time user will meet
immediately: on an I/O server most thread-time is not the application's code. The line breaking
unlabelled time into runnable and not-runnable was added *during* this trial because the number was
otherwise unreadable.

**5. There is no independent profiler to check against here, and that is new.** Calcite and Lucene
were both checked against JFR's inclusive shares. On Netty the inclusive view is a spine and the
self-time view does not contain our handlers at all, so **neither is comparable**. The check that
replaced it — fitting the labels against the workload's own configuration — is arguably stronger,
but it only exists because we wrote the workload. **On a target we had not written there would have
been nothing to check against**, and that is a real gap in the method rather than in this trial.
[ideas.md](ideas.md) item 15 is about exactly this and is now better motivated.


## 8. What comes next

1. **Trial 4.** Netty has answered what it was chosen for.
2. **Whether the 59% self-time leaf is safepoint bias**, from section 2 — now with a second reason
   to care: the nesting check disagrees with the labels by ~1.7x on the three clean positions, and
   elided safepoint polls in counted `int` loops is the obvious suspect for both. Still open.
3. **Coverage is 14.5%**, and most of the rest is Netty's own HTTP codec. Whether labels belong on
   somebody else's codec is a real question about what this tool is for, and it is the same question
   the Calcite fork ([ideas.md](ideas.md) item 3) asks from the other end.

## 9. Every flag

| flag | default | what it does |
|---|---|---|
| `--qualify` | — | does this workload qualify as a target at all |
| `--labels` | — | the labelled run and the report a user would get |
| `--ab --rounds=N` | 4 | the three-way interleaved A/B. Rebuilds nothing between arms, which took [four attempts](findings.md#the-machine) to get right |
| `--seconds=N` | 20 | length of each measured window |
| `--threads=N` | 4 | event-loop threads |
| `--connections=N` | 16 | client connections |
| `--inflight=N` | 8 | requests in flight per connection |

`--strict` was never a flag here; the trial passed `strict = false` in code because of the floor
check. That is gone — the labels are lexical `op(id) { }` and cannot leak, so it runs strict.

---

## Revisited: the coarse tier, and the one thing it must not do

A request is the obvious coarse operation here, and one handler at the head of the pipeline brackets
it. Netty propagates `fireChannelRead` **synchronously** on the event loop thread, so every policy,
auth, route, render and the `writeAndFlush` happens inside that one call — no propagation needed,
which is what makes this a phase 4 workload at all. `--labels --coarse`:

```
coarse operation            executions       mean        p50        p90        p99        max  busy/exec  waiting   in flight
request                      3,049,206    14.5 us    14.3 us    20.5 us    36.9 us    3.02 ms    14.4 us     0.0%      2.25/4
  request was: unlabelled 74.3%, policy:abuse 15.7%, policy:quota 5.1%, render 2.2%,
               policy:experiment 1.2%, policy:geo 0.9%, and 2 more
```

### This is a negative control, and that is why it was worth doing

**`mean − busy/exec` is 0.1 µs out of 14.5.** Netty's waiting lives in the selector *between*
requests, outside any span, so a request here is pure CPU and the waiting column must read zero.

That is the failure [plan.md](plan.md) flagged as the one that must not happen: an early design would
have made the CPU column *occupancy*, so `span − CPU` would come out near zero **in exactly the case
where it is all waiting** — the one answer that was wanted, inverted. This trial checks the other
half of that: a tool that mistakes any gap for waiting reads high here, where the true answer is
nothing. It reads 0.0%.

The Lucene revisit is the complementary case, where the same column correctly reads 24.5%. Neither
run alone shows the column works; the pair does.

### And bracketing, which was predicted and had never been seen

`unlabelled 74.3%` is the useful surprise. Run-wide, this trial's labels cover a small fraction of
thread-time and that reads as *"the labels miss most of the run"*, which is alarming and unactionable.
Under a request it becomes **specific**: three quarters of a request is Netty's own codec, aggregator
and write path, which nobody labelled and nobody was ever going to.

[ideas.md](ideas.md) item 13 predicted exactly this — *"bracketing, which the coarse tier gives us for
free … it needs no new mechanism at all"* — and it is right: unlabelled samples taken under a context
are attributed to that context, so the pair matrix already had the answer.

**`in flight 2.25/4`** on four event loops, correctly below the ceiling: the load generator keeps
sixteen connections with eight requests in flight, but a request lasts 14.5 µs, so at any instant
only about two of the four loops are inside one.

| flag | default | what it does |
|---|---|---|
| `--coarse` | off | with `--labels`, wrap each request in a coarse label at the head of the pipeline |

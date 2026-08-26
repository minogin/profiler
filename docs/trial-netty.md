# Trial 3 — Netty

The third trial, and the first one chosen for its **concurrency shape** rather than its identity
problem. Calcite was single-threaded. Lucene gave eight worker threads all running the same work
flat out. Netty gives a handful of long-lived event loops carrying many short tasks, with time that
is mostly not CPU — which is the first foreign workload where the thread-state column built in
phase 6 has anything to say, and the first that can show it saying the wrong thing.

Harness: [`trial-netty/`](../trial-netty). Status: **labelled and measured; the A/B against the bare
workload is still outstanding.**

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

### The four policies are one number

> `PolicyHandler.run` — **60.30%**

That is all four policies together, and it is the single most important number in this section. The
domain has four policies with costs that differ by design — 24, 48, 256 and 1,024 bytes hashed — and
the stack has one frame for the lot. Same shape as Lucene's four `TermQuery` clauses sharing
`TermScorer`, arrived at from a completely different direction.

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

| policy | effective bytes | measured | predicted | error |
|---|---|---|---|---|
| `policy:geo` | 24 | 140.1 ns | 130.8 ns | **+6.7%** |
| `policy:experiment` | 48 | 193.7 ns | 204.4 ns | −5.5% |
| `policy:quota` | 248 | 819.0 ns | 817.7 ns | +0.2% |
| `policy:abuse` | 800 | 2510.5 ns | 2510.5 ns | −0.0% |

Two parameters against four measurements, worst residual **6.7%**. The effective byte count is not
the configured one: bodies run 128–2,048 bytes and a policy hashes `min(depth, body)`, so the
1,024-byte policy really averages 800. Using the configured figure would have made the fit look
worse, and that would have been our arithmetic rather than the tool's.

This is the Calcite and Lucene cross-check in a third form. There we compared against another
profiler; here the workload's own configuration is the second opinion.

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

## 6. What comes next

1. **The A/B against the uninstrumented workload.** Not yet run. The Lucene rule applies — compare
   end-to-end throughput, three ways, and never summed occupancy.
2. **Whether the 59% self-time leaf is safepoint bias**, from section 2. Still open.
3. **Coverage is 14.5%**, and most of the rest is Netty's own HTTP codec. Whether labels belong on
   somebody else's codec is a real question about what this tool is for, and it is the same question
   the Calcite fork ([ideas.md](ideas.md) item 3) asks from the other end.

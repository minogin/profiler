# Trial 3 — Netty

The third trial, and the first one chosen for its **concurrency shape** rather than its identity
problem. Calcite was single-threaded. Lucene gave eight worker threads all running the same work
flat out. Netty gives a handful of long-lived event loops carrying many short tasks, with time that
is mostly not CPU — which is the first foreign workload where the thread-state column built in
phase 6 has anything to say, and the first that can show it saying the wrong thing.

Harness: [`trial-netty/`](../trial-netty). Status: **qualified, not yet instrumented.**

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

## 4. What comes next

Not started. In order:

1. **Place the labels**, one per policy instance plus the three distinct handlers, in the handler
   bodies — which are ours to wrap, so this should be the lexical form as it was on Lucene.
2. **Check the wrapper did not change the workload.** Netty's pipeline has fast paths of its own and
   the Lucene rule applies: a flame graph over the inert instrumentation against one over the bare
   code, comparing end-to-end throughput and not summed occupancy.
3. **The question this trial exists for:** what the thread-state column says about event loops. The
   prediction, recorded before running it, is that it reads **0% waiting** — a selector wait happens
   in native code and a thread in native code reports `RUNNABLE`. If that holds, the column is blind
   to precisely the workload shape it was built for, and that belongs in
   [findings.md](findings.md#thread-state-beside-the-label) as a limit rather than being discovered
   later by a user.
4. **Per-operation concurrency** has its first foreign test here too: four event loops, so a
   handler's `threads` column should sit near the number of loops actually carrying traffic.

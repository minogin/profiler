# The second trial — Lucene, and the first concurrent workload we did not write

The first trial ([trial.md](trial.md)) was one data point, and every design decision for a week
afterwards was extrapolated from it. This is the second. Target: **Apache Lucene 10.4.0**, a scored
`BooleanQuery` over a million documents, searched concurrently across eight segments. The harness
is in [`trial-lucene/`](../trial-lucene) and every number below reproduces from it.

Findings that generalise beyond Lucene are folded into [findings.md](findings.md) and
[case.md](case.md); this file is the trial's own record.

**Why this candidate.** Calcite's identity gap was *many classes behind one inherited method*.
Lucene's is the commoner and sharper shape: **many instances behind one class**. Four `TermQuery`
clauses on four different terms are, to the JVM, one `TermScorer` running one
`ImpactsDISI.advance`. There is no frame that differs between them — not one frame deeper, not
anywhere. Calcite at least had an answer that was recoverable and wrong; here there is no answer at
all.

---

## 1. The candidate qualifies

**The corpus.** A million generated documents, vocabulary of 50,000 terms drawn from a Zipf
distribution, 24 body tokens each, plus a 20-value keyword field and an integer point field. 59 MB
on disk, built in 17.3 s. Merging is switched off and a commit forced every 125,000 documents, so
the index is exactly **8 segments** and the searcher gets exactly 8 slices — one per segment, stated
rather than inherited from a merge policy, because how much of this workload is concurrent is an
experimental parameter.

Zipf is not decoration. It is what makes the question sharp:

| term | docFreq | share of corpus |
|---|---|---|
| `w00002` | 510,135 | 51.014% |
| `w00003` | 413,454 | 41.345% |
| `w00005` | 298,287 | 29.829% |
| `w00020` | 95,840 | 9.584% |
| `w00400` | 5,332 | 0.533% |
| `w08000` | 281 | 0.028% |

**The query**, eight `SHOULD` clauses, scored, top 100:

```
body:w00002  body:w00020  body:w00400  body:w08000
body:"w00003 w00005"  body:w000*  cat:c7  price:[2000 TO 2600]
```

Four term clauses spanning three orders of magnitude of selectivity and sharing one class; a phrase
clause whose cost is in positional confirmation; a prefix clause that rewrites into a hundred terms;
a low-cardinality keyword clause with an enormous posting list and trivial per-document work; and a
point range, which is not a postings scan at all. Five different cost *shapes*, and four clauses
that are the same shape as each other and differ only in which term.

Warm search: **5.69 ms mean** on 8 threads (min 4.13, max 13.65 over 50 runs), 16 logical cores.

### The qualifying test: does a conventional flame graph disappoint?

JFR execution samples over a 20 s uninstrumented run — 10,329 samples, `stackdepth=1024`, **zero
truncation**, median depth 23 and maximum 44. Self time:

| self time | share |
|---|---|
| `Lucene104PostingsReader.sumOverRange` | 10.76% |
| `BlockPostingsEnum.bufferIntoBitSet` | 10.24% |
| `BlockPostingsEnum.advance` | 9.32% |
| `ConjunctionDISI.doNext` | 9.19% |
| `MemorySessionImpl.checkValidStateRaw` | 7.69% |
| `PhraseScorer$1.matches` | 6.49% |
| `ExactPhraseMatcher.advancePosition` | 3.63% |
| `DisiPriorityQueueN.downHeap` | 2.79% |
| `DataInput.readVLong` | 2.52% |
| `PostingDecodingUtil.splitInts` | 2.41% |

Codec internals and iterator plumbing. Correctly identified as hot, and not one row names a clause.
The inclusive view is the usual recursive spine: `IndexSearcher.search` 99.87%,
`MaxScoreBulkScorer.score` 76.47%, `scoreInnerWindowMultipleEssentialClauses` 74.53%,
`scoreNonEssentialClauses` 60.93%.

**What the stacks can and cannot attribute.** Counting every collapsed stack that names a frame
belonging to one clause and to no other — no stack was attributable to two:

| clause | samples | share | |
|---|---|---|---|
| phrase | 2,793 | 27.04% | `PhraseScorer`, `ExactPhraseMatcher` |
| prefix | 2,141 | 20.73% | `MultiTermQueryConstantScoreBlendedWrapper` |
| point | 105 | 1.02% | `PointRangeQuery`, BKD |
| **no clause frame at all** | **5,290** | **51.22%** | |

So a flame graph can identify **three of the eight clauses**, covering 48.8% of the samples. The
other 51.2% contains the four term clauses, the keyword clause, and the coordinating machinery, and
inside it there is nothing whatsoever to tell one term clause from another. Not "hard to tell" —
the frames are identical.

Verdict: qualifies, and for a cleaner reason than Calcite did. Calcite's flame graph pointed the
wrong way; Lucene's simply does not point.

## 2. What the labels say

Placement is by **wrapping**: `Query → Weight → ScorerSupplier → Scorer → DocIdSetIterator`, one
wrapper per clause, each labelled with the clause's identity. 20 s, 93,691 labelled samples, 19,944
ticks at an achieved 1.003 ms, 9 threads.

| operation | share | calls | hits | noise | implied/call | over 1 tick |
|---|---|---|---|---|---|---|
| `clause:prefix` | **48.491%** | 20,825,413 | 45,432 | 0.47% | 2.2 µs | 16.73% |
| `clause:phrase` | **42.657%** | 495,170,832 | 39,966 | 0.50% | 81.0 ns | 2.70% |
| `clause:point` | 2.717% | 44,949,684 | 2,546 | 1.98% | 56.8 ns | 3.18% |
| `clause:term#2` | 2.080% | 39,353,344 | 1,949 | 2.27% | 49.7 ns | 3.39% |
| `clause:term#20` | 1.733% | 13,939,010 | 1,624 | 2.48% | 116.9 ns | 3.33% |
| `clause:cat` | 1.595% | 8,877,472 | 1,494 | 2.59% | 168.8 ns | 3.21% |
| `clause:term#400` | 0.562% | 2,142,738 | 527 | 4.36% | 246.7 ns | 3.61% |
| `clause:term#8000` | 0.163% | 1,038,524 | 153 | 8.08% | 147.8 ns | 1.96% |

Duty cycle 55.85%; 47.8% of occupancy outside any labelled clause.

**The answer nobody could otherwise have.** The four clauses that share a class span 2.080% down to
0.163% — a 13× spread inside a group the flame graph cannot subdivide at all. And the two clauses
worth acting on are the two the user thinks of as convenient shorthand: a prefix that expands to a
hundred posting lists, and a phrase.

**The counts column separates two opposite problems.** `prefix` and `phrase` hold almost the same
share and are nothing alike: prefix is 20.8 M calls at 2.2 µs, phrase is 495 M calls at 81 ns — a
24× difference in call count in one direction and a 27× difference in unit cost in the other. The
fix for one is to stop expanding the prefix; the fix for the other is to stop calling it. No stack
profiler has this column.

**The long-instance detector fired, on foreign code, correctly.** `clause:prefix` — 6,645 executions
outlived a tick, 16.7% of its occupancy against a 3.3% machine floor. That is the bitset build, and
it is genuinely a long operation, so the verdict is right.

## 3. The mistake that cost a third of the prefix clause

The first version of the wrappers labelled the *product* — the scorer and its iterator — and left
every factory call bare, on the reasoning that constructing a scorer is setup and the work is in the
scan. It reported:

| | first placement | after the fix | JFR's own view |
|---|---|---|---|
| `clause:prefix` | 32.211% | **48.491%** | 20.73% of all samples |
| `clause:phrase` | 57.470% | 42.657% | 27.04% of all samples |
| outside any clause | 60.7% | 47.8% | 51.22% |

That is true of a term clause and badly false of a prefix clause, which rewrites into a hundred
terms and unions their postings into a bitset *before a single document is scored*. The single
hottest complete stack in the entire baseline recording — 9.05% of all samples — is exactly that:

```
BooleanScorerSupplier.bulkScorer → RewritingWeight$1.get → rewriteInner
  → DocIdSetBuilder.add → FixedBitSet.or → BlockPostingsEnum.intoBitSet → bufferIntoBitSet
```

`ScorerSupplier.get`, not `Scorer.nextDoc`.

**The finding is not "we made a mistake".** It is that the report gave no sign of one. The shares
summed to 100%, every clause had a plausible number, the ordering looked sensible, and the answer
was a third low on the clause that mattered. What caught it was step 4 of the recipe — check against
something independent, and where the two disagree, that is the finding. Nothing internal to the tool
could have caught it, because from the tool's point of view the time really was outside every label.

**Placement by wrapping means labelling the factory as well as the product**, and the general form
of that rule is worth stating: *a label belongs on the whole lifecycle of the thing it names, and a
framework that separates construction from use will separate the cost too.*

## 4. Where the two methods disagree, and it is not noise

The second independent check is **Elasticsearch's approach**: wrap every scorer and put
`System.nanoTime()` on both sides of every call, which is what `ProfileWeight` / `ProfileScorer`
do. Same wrappers, same boundaries, same run length — only the instrument differs.

| clause | timed | sampled |
|---|---|---|
| phrase | **48.85%** | 42.657% |
| prefix | **40.70%** | 48.491% |
| point | 3.35% | 2.717% |
| term#2 | 3.07% | 2.080% |
| term#20 | 1.80% | 1.733% |
| cat | 1.53% | 1.595% |
| term#400 | 0.54% | 0.562% |
| term#8000 | 0.17% | 0.163% |

**The two methods rank the top two clauses in opposite orders.** Six clauses agree to within a
point; the two that matter are swapped.

The explanation was proposed before it was fitted: the timing instrument charges a fixed cost per
*call*, and phrase makes 524.5 M calls against prefix's 24.6 M — 21× as many. Fitting one free
parameter, a fixed nanosecond cost per instrumented call, against all eight clauses:

**Best fit: 21.50 ns per call, RMS residual 0.09 percentage points.**

| clause | timed | corrected | sampled | diff |
|---|---|---|---|---|
| phrase | 48.85% | 42.62% | 42.66% | −0.04 |
| prefix | 40.70% | 48.41% | 48.49% | −0.08 |
| point | 3.35% | 2.61% | 2.72% | −0.11 |
| term#2 | 3.07% | 2.26% | 2.08% | +0.18 |
| term#20 | 1.80% | 1.80% | 1.73% | +0.06 |
| cat | 1.53% | 1.55% | 1.59% | −0.04 |
| term#400 | 0.54% | 0.58% | 0.56% | +0.02 |
| term#8000 | 0.17% | 0.17% | 0.16% | +0.01 |

Eight clauses, one parameter, every residual under 0.2 points. The instrument accounts for **14.3 s
of the 83.0 s it reports — 17.3%** — and that 17.3% is not spread evenly: it lands almost entirely
on the clause with the most calls, which is precisely the clause it then promotes to first place.

For scale, a dependent pair of `System.nanoTime()` calls measured **36.6 ns** in isolation on this
machine (idle, single thread). The fitted 21.5 ns is lower, which is what one would expect when the
two calls are separated by real work rather than chained — the fit is an empirical reconciliation,
not a measurement of `nanoTime`, and it is offered as such.

**This is the strongest single result of the trial**, because the timing approach is not a straw
man. It is what a production search engine actually ships, and on this workload it names the wrong
clause first.

## 5. What the instrumentation cost

Step 3 of the recipe: price the mechanism apart from the hook. Interleaved, order swapped every
round, 24 rounds of 20 searches each, n=480 per configuration.

| configuration | mean | median | min | vs baseline |
|---|---|---|---|---|
| `NONE` — no wrapper | 4.806 ms | 3.878 ms | 3.382 ms | — |
| `INERT` — wrapped, measuring nothing | 4.990 ms | 4.005 ms | 3.383 ms | **+3.84%** |
| `LABEL` — wrapped and labelled | 5.120 ms | 4.199 ms | 3.603 ms | **+6.54%** |
| `TIME` — wrapped and timed | 6.505 ms | 5.641 ms | 4.919 ms | **+35.36%** |

So: **wrapping alone is 3.84%, our label adds 2.7 points on top of it, and the timing approach adds
31.5 points on top of it.** Without the three-way split our hook would have been charged the whole
6.54%, which is what happened to Calcite's listener before it was measured apart.

At roughly 267,000 labelled calls per search, the label's 2.7 points work out to **3–6 ns per
labelled call** in this workload, against 1.7 ns for the hook measured in isolation on the bench.
The difference is the wrapper's own indirection between the call site and the hook, and it is the
honest number to quote for placement by wrapping.

An earlier run of the same comparison caught the machine's clock dropping by 2.5× between rounds 4
and 5 — all four configurations moved together, which is exactly what interleaving is for and what
a sequential comparison would have charged to whichever configuration was running at the time.

## 6. The naive wrapper, measured rather than warned about

Lucene 10 gives a `DocIdSetIterator` four ways to be fast in bulk — `intoBitSet`, `docIDRunEnd`,
`Scorer.nextDocsAndScores`, `ScorerSupplier.bulkScorer` — and every one has a working default on the
base class that falls back to a doc-at-a-time loop. A wrapper that overrides only the obvious
methods still compiles, still returns the right documents, and still produces a report.

`Placement.NAIVE` is that wrapper, so the cost can be measured instead of asserted.

| | careful | naive |
|---|---|---|
| throughput vs no wrapper | −1.7% (noise) | **+13.28% slower** |
| `clause:term#20` share | 1.733% | **4.940%** |
| `clause:term#20` calls | 13,939,010 | **556,342,404** |
| `clause:term#20` rank | 7th | **3rd** |
| `clause:term#20` implied/call | 116.9 ns | 8.2 ns |
| `clause:phrase` share | 42.657% | 39.419% |
| `clause:cat` calls | 8,877,472 | 304,772,978 |

Forty times the iterator calls, a share nearly tripled, and a rank moved from seventh to third — on
a query Lucene would never have run that way.

**The careful wrapper, by contrast, does not perturb the code path at all.** JFR over the inert
configuration against JFR over the bare one:

| | no wrapper | inert wrapper |
|---|---|---|
| `MaxScoreBulkScorer` inclusive | 76.49% | 76.85% |
| `scoreNonEssentialClauses` | 60.93% | 60.61% |
| `intoBitSet` | 19.19% | 19.49% |
| `nextDocsAndScores` | 9.87% | 10.46% |
| `DefaultBulkScorer` | 0.00% | 0.00% |

Block-max pruning survives, both bulk APIs survive, and the fallback bulk scorer never appears.

**And `strict` caught the naive wrapper by itself, in one second**, without anybody knowing what was
wrong:

```
PROFILING STOPPED - this label could not have produced a correct number:
  clause:term#2: 5,115,899 calls at under 23.9 ns each, below the 50 ns floor.
```

That is the floor check doing exactly the job it was designed for, on foreign code, against a
mistake nobody anticipated when it was written.

## 7. Where the tool is wrong about itself: the floor check is not machine-independent

`strict` also stopped the **careful** placement, at 998 ticks, naming `clause:term#2` at "under
27.5 ns". The settled twenty-second number for that same label is **49.7 ns**, above the floor.

The check is documented as safe to fire early because "a label below the floor is a property of the
placement, not of the run — identical on every machine and every rerun". Measured against run
length, on the same machine in the same process:

| run length | searches/s | `term#2` implied/call | `term#2` share | `phrase` implied/call | `phrase` share |
|---|---|---|---|---|---|
| 2 s | 234.5 | 18.1 ns | 1.692% | 42.9 ns | 40.182% |
| 5 s | 151.0 | 31.2 ns | 1.940% | 68.0 ns | 42.300% |
| 10 s | 128.6 | 41.0 ns | 1.894% | 77.1 ns | 42.330% |
| 20 s | 114.9 | 54.1 ns | 2.070% | 88.3 ns | 42.350% |
| 40 s | 108.8 | — | — | — | — |

Throughput falls by **2.2×** from a two-second run to a forty-second one, same process, same work.
This is a laptop throttling under sustained load. Every implied per-call duration rises with it,
roughly in proportion — and **every share stays put**, moving by under 0.4 points across the whole
range.

Two conclusions, and the second is a defect:

- **Share is invariant to the machine slowing down. Implied per-call duration is not.** That is a
  useful characterisation of what each column is worth and it could not have come from the bench or
  from Calcite, where a single plan took seconds.
- **The floor check's early-firing argument does not hold.** Its looseness allowance covers
  statistical noise in the sample count, not a 2× change in the machine's clock between second one
  and second twenty. On this hardware it will stop a correct placement roughly as readily as an
  incorrect one, and it did.

Nothing has been changed in response yet; the fix belongs in a phase, not in a trial. See
[plan.md](plan.md) and [ideas.md](ideas.md).

## 8. The friction — what this trial says about the tool

**1. `op(id) { }` came back, and `enter`/`exit` was not needed once.** Calcite's only boundary was a
pair of callbacks with no `finally`, which is why phase 3.75 built the non-lexical form. Lucene's
extension point is a wrapper, and a wrapper method body is *a block we own* — so the lexical form
works everywhere, its `finally` is written by the compiler, and there is no leak to check for. Two
libraries, two shapes, and the library needs both. This is the second data point that phase 3.75 was
right, arriving from the opposite direction.

**2. The report cannot tell you a label is in the wrong place.** §3 is the whole argument. A
placement that misses a third of a clause produces a report that looks completely healthy. The only
defence found so far is an independent measurement, which means the tool should make cross-checking
easy rather than assume nobody will need it.

**3. Wrapping can silently change the workload, and the counts column is the tell.** §6. Nothing in
the naive report is marked wrong, but `term#20` at 556 M calls and 8.2 ns each is not a plausible
row, and neither is `cat` at 304 M. **An implied per-call duration far below the floor is evidence
about the placement, not just about the label** — which is what the floor check already believes,
and it was right here.

**4. Half the time is outside every label, and that is the ceiling on this kind of placement.**
47.8% of occupancy is in `MaxScoreBulkScorer`'s coordination, the collector, the priority queue and
weight construction. Calcite's item 4 said the fine tier "can attribute time to the domain concepts
the library exposes a hook for, and nothing else". Lucene exposes more hooks than Calcite and the
figure is *worse*, because a query's cost genuinely is half coordination. Worth saying plainly: on
this workload the tool explains where 52% of the time went, and the flame graph is still the only
thing with anything to say about the rest.

**5. The duty cycle's bound is technically true and practically useless here.** 55.85% CPU produced
"at most 79.06 points of any share is occupancy that was not CPU" — a bound wider than every share
it applies to. It is honest and it is what the design promised, and on this workload it tells the
reader nothing. Why the duty cycle sits at 56% with eight busy search threads on sixteen cores is
itself unexplained and is [an open question](findings.md#open-questions).

**6. The depth argument did not come up.** Maximum stack depth 44, zero truncated samples. JFR's
64-frame limit is a real failure mode and it is not this workload's. Recorded because the honest
version of a list of advantages includes the ones that did not apply.

**7. The concurrency worked and produced no incident.** Eight worker threads plus the main thread,
labels entered and left on pool threads, no imbalance, no slot exhaustion, no leaked spans, and
shares that reproduce across run lengths to within 0.4 points. The first concurrent foreign workload
and it was uneventful, which is the result one wants and not a result one can assume.

## 9. Reproducing it

```bash
./gradlew :trial-lucene:classpathFile
CP=$(cat trial-lucene/build/classpath.txt)

# build the corpus once (~17 s, 59 MB into trial-lucene/index)
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt --build

# does the candidate qualify
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt --qualify

# the conventional profile
java -XX:FlightRecorderOptions=stackdepth=1024 -cp "$CP" \
  com.minogin.profiler.trial.lucene.LuceneTrialKt \
  --placement NONE --sampler false --jfr lucene.jfr --seconds 20
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt \
  --analyze lucene.jfr --top 16 --collapsed lucene.collapsed

# ours
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt --placement LABEL --seconds 20

# the Elasticsearch-style alternative, and the naive wrapper
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt --placement TIME --sampler false --seconds 20
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt --placement NAIVE --seconds 20
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt --placement NAIVE --strict true --seconds 20

# what any of it costs
java -cp "$CP" com.minogin.profiler.trial.lucene.LuceneTrialKt \
  --ab true --modes NONE,INERT,LABEL,TIME --rounds 24 --per 20
```

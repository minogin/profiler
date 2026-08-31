# General

## Language
* Project language is English.
* I might use Russian when talking to you, but all the code, comments, etc. should be in English
* Answer me in the same language I ask you in.

## Your behaviour
* Use simple terms
* Explain things in simple ways
* Provide examples, when you explain things

## How we collaborate
* Never start long-running operations without my consent
* Explain to me the code you generated briefly after every turn
* Try to be concise when explaining things
* The process:
  * We discuss things first
  * I ask questions, you give advices
  * Then you propose the implementation
  * Only when I confirm I like it, you code.
* If I ask a question, first answer briefly. If you have any concerns or think the question itself is
  wrong tell me after the brief answer.
* Do not write novels where it's not needed. 
* Do not add "worth mentioning", "One thing I'd flag rather than assume" and similar shit
  every time, when it is not really important. Do not distract me with minor things. 
* **First decide whether there is a choice at all.** A question whose answer the evidence settles is
  not a decision, it is a finding — state it and act on it. Only what the evidence cannot settle is
  mine: scope, ordering, priorities, taste, an API's shape, whether something is its own commit.
  Manufacturing options around a settled answer, or padding one with a consequence that changes
  nothing about what to do, costs me more energy than getting it wrong would.
* **Default to one concrete proposal, not a menu.** Show me the actual thing - rendered, specific -
  with the one real trade-off named, and let me push on it. Two turns of that beats three options
  every time, because your three options are usually three variations on your own first idea: if the
  framing is wrong, none of them helps, and picking one locks the wrong framing in before I have seen
  anything. Almost everything good in the report came from me rejecting the menu and describing the
  shape I wanted.
* **Use the question UI only for genuinely divergent directions** - a scope call, an ordering, what
  ships and what does not, work that is wasted if you build the wrong one. Not for design details,
  wording or naming. When you do ask, the options must be actually different, not shades of the same
  idea. Never bury a real choice in a paragraph, which is what this rule originally existed to stop.

## Keeping the docs
Each doc has one job. Write to the right one as you go, not at the end.
* `docs/case.md` — **whenever a session shows an existing tool failing at something**, record it:
  which tool, what it failed at, and where it was observed. This is the running argument for why
  this project exists, so it must be built out of observations, not opinions. Keep the "where the
  other tools are better" section honest and current too.
* `docs/ideas.md` — **whenever an idea comes up that we do not act on immediately**, record it
  before it evaporates. Untested is fine; unwritten is not.
* `docs/sandbox.md` — **whenever using the tool is awkward**, log it: a label that would not go where
  it was wanted, a column that had to be re-read, a warning that looked like noise, something reached
  for that was not there. Raw observations, newest first, dated, and saying who noticed — an
  observation from someone who already knows the tool is worth less than one from someone who does
  not. They graduate into `ideas.md` when they become something to do. Nothing measured in
  `sandbox/` is evidence and none of it belongs in `findings.md`: it is our code, so it inherits our
  assumptions, which is the same reason items 23 and 25 were not built.
* `docs/plan.md` — work we committed to, and **whenever a decision is made that a later reader could
  not reconstruct from the code**, record it and why: a rename, a dependency or toolchain bump, an API shape, something
  deliberately not built. The test is simple — if the only trace is a commit message, it is not
  written down. Commit messages are searched by people who already know what they are looking for;
  the docs are read by people who do not. This rule exists because the project was renamed and the
  reasoning behind it lived nowhere else for six commits.
* `docs/findings.md` — only things we measured, with the measurement.

## Do not spend time on unnecessary builds
* If you only changed the docs, do not run the build and test.

## Always trace the CPU clock alongside any performance measurement
**Before** starting a bench run, a trial, an A/B, or a series of runs, start the processor
performance probe in the background and leave it running for the whole measurement:

```powershell
Get-Counter '\Processor Information(_Total)\% Processor Performance'
```

Sample it every second or two into a timestamped file, and report the clock **beside** the numbers
it produced — not afterwards, and not only when something looks wrong.

**`Get-Counter` fails intermittently, so wrap every sample in a retry.** Not `-ErrorAction
SilentlyContinue` on its own: that skips the sample and leaves a hole in the trace exactly where
something interesting may have been happening, and the hole is silent. Retry a few times, and if it
still fails, write the failure into the file so the gap is visible rather than invisible:

```powershell
$v = $null
foreach ($try in 1..3) {
  try { $v = (Get-Counter '\Processor Information(_Total)\% Processor Performance' -ErrorAction Stop).CounterSamples[0].CookedValue; break }
  catch { Start-Sleep -Milliseconds 200 }
}
$line = if ($null -eq $v) { "FAILED" } else { [math]::Round($v, 1) }
Add-Content -Path $out -Value "$((Get-Date).ToString('HH:mm:ss'))`t$line"
```

* This machine's clock swings by **2x within a single run** and tracks load, and **heat soak
  accumulates across runs**: the same configuration decayed from 23M to 12M calls/s after an hour of
  load, and idling restored it. A duration measured without the clock beside it cannot be
  interpreted, and a series of runs without it cannot be compared to each other at all.
* So a run that disagrees with another run is not evidence of anything until the clock trace says
  the two ran at the same speed. Several hours have gone into chasing differences that were the
  machine changing speed.
* `Get-Counter` was tested as a way of *keeping* the CPU boosted and did not reproduce here — see
  `docs/findings.md`, "The probe does not keep the CPU warm". Run it for the trace, not for that.
* Use per-core (`(*)`) rather than `(_Total)` when the question is about which cores the work landed
  on — this laptop is hybrid, and performance and efficiency cores read very differently.
* **Never swap instruments in the middle of a measurement, and stop the old one before starting the
  new one.** Improving the probe halfway through a campaign left two of them polling the machine
  being measured, which contaminated the runs that overlapped.
* **Two runs that differ are not a finding.** The bench fails its own self-check about nine times in
  ten once the machine is warm, so a difference between a handful of runs is noise. Ten per arm,
  alternating, before drawing a conclusion — three runs once "showed" a defect that twenty runs
  disproved. 
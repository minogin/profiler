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

## Keeping the docs
Each doc has one job. Write to the right one as you go, not at the end.
* `docs/case.md` — **whenever a session shows an existing tool failing at something**, record it:
  which tool, what it failed at, and where it was observed. This is the running argument for why
  this project exists, so it must be built out of observations, not opinions. Keep the "where the
  other tools are better" section honest and current too.
* `docs/ideas.md` — **whenever an idea comes up that we do not act on immediately**, record it
  before it evaporates. Untested is fine; unwritten is not.
* `docs/findings.md` — only things we measured, with the measurement.
* `docs/plan.md` — only work we committed to.

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
# General

## Language
* Project language is English.
* I might use Russian when talking to you, but all the code, comments, etc. should be in English
* Answer me in the same language I ask you in.

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
# Diary 058: Naming and organization review

**Date:** 2026-04-14
**Status:** Review + minimal action. Most items are "observed, not fixed" — with
reasons.

## Purpose

Triggered by the question "is it time to review whether names reflect the
domain well?" after landing diary 057's `TurnResult → TurnRecord` fix.
Walks the stack top-down, records every name or organizational choice that
stood out, and decides — case by case — whether to fix now, defer, or
accept.

The honest conclusion up front: **nothing is actively confusing once you
scope the fix.** Each item is either cosmetic, already-fixed-by-055, or
bigger than the gain warrants. This diary is mostly a *decision log* so
future contributors don't re-discover the same observations without
knowing we already weighed them.

## Findings

### ✅ Names that reflect the domain well

- `BattleState`, `BattleEvent`, `Pokemon`, `PokemonState`, `Species`,
  `Move`, `TurnChoice`, `Volatile`, `SideCondition`, `Hazard`, `Ruleset`
  — direct Pokemon vocabulary.
- `Phase`, `resolve()` — "resolve" is the canonical verb in Pokemon
  mechanics ("the move resolves next").
- `ChoiceProvider`, `FaintReplacementProvider` — "provider" cleanly
  signals "someone/something that answers when asked."

### ⚠️ Observed but deferred (with reasons)

| Item | Observation | Decision |
|------|-------------|----------|
| `TurnPipeline.Result` | Nested "Result" is domain-agnostic. | **Defer.** Diary 055 replaces it with a sealed `TurnResolution`. Renaming now is throwaway. |
| `TurnChoice.UseMove.switchTo: Int?` | Field only meaningful for self-switch moves; present on every `UseMove`. | **Defer.** Diary 055 removes it in favor of mid-turn prompts. |
| `MoveEffect` variant naming (`SelfSwitch`, `StatBoost`, `SetVolatile`, `SetHazardOnOpposingSide`, `ClearHazardsOnUserSide`, `UserStatBoost`) | Mix of noun phrases and verb phrases; not fully consistent. | **Accept.** Fix would touch every phase branch for stylistic gain. Revisit if a future refactor is already in the area. |
| `engine/src/main/kotlin/com/pokemon/battle/engine/...` subpackage | `.engine.engine` path redundancy. The module name + subpackage both say "engine." | **Accept.** 50+ files, hundreds of import lines, purely cosmetic. An IDE-driven mass rename if it ever bites hard. |
| `com.pokemon.battle.loop` package | Holds `BattleLoop`, `TurnRecord`, `BattleResult`, `ChoiceProvider`, `FaintReplacementProvider` — "loop" undersells it. Could be `runner` or `orchestration`. | **Accept.** Package rename is disruptive; "loop" is accurate enough. |
| `model` / `engine` / `data` package split | `BattleState` in `engine`, `Pokemon` in `model`, `Pokedex` in `data`. The lines are felt: `Species` is loaded as data but shaped as model; `BattleState` is both a model and an engine concept. | **Accept for now; note for future.** This is a design question, not a rename. If a future diary needs to unify them, use this entry as prior art. |
| `FaintReplacementProvider` | Long name. Only trigger for replacement is a faint today. | **Accept.** Specificity > brevity when the extra word documents intent. If other triggers appear, we *add* a general `ReplacementProvider` interface rather than rename. |

### ✅ Already-fixed naming collisions

- `BattleLoop.TurnResult` → `TurnRecord` (diary 057, committed
  `47ad9cd`). Clears the path for diary 055's `TurnResolution`.

## Actionable now: nothing mechanical

This diary records the review without committing code changes. The lesson
from scoping each item: **"actively confusing" was the wrong framing.**
Once the collision with 055 was resolved (diary 057), the remaining items
are either cosmetic, pre-obsoleted by 055, or design-shape questions
rather than names.

Naming reviews are useful *even when they produce no renames* — the log
of "we looked at this and decided not to" prevents the next contributor
from re-doing the analysis.

## Process note

When I categorized items as "actively confusing" during the review, that
framing pushed toward action. Scoping each item revealed the framing
was overreach: nothing genuinely confused anyone, just slightly rubbed.
**For future reviews:** prefer categories like *"observed"*, *"cleaner
alternative exists"*, *"collision"*, over emotive adjectives like
"confusing" or "smell." Emotive framing makes deferral feel like
capitulation even when it's the right call.

## Related

- **Diary 057** — the one rename that *was* worth doing (collision, not
  cosmetic).
- **Diary 055** — naturally obsoletes several items above when it lands.
- **CLAUDE.md** "Do the names match the domain?" bullet in the review
  checklist — this diary is an application of that bullet.

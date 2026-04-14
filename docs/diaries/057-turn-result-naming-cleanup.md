# Diary 057: Turn-result naming cleanup

**Date:** 2026-04-14
**Status:** Complete (2026-04-14). `BattleLoop.TurnResult` renamed to `TurnRecord`; `TurnResolution` now available for the sealed-type role originally proposed.

## The conflation

Three conceptually distinct "results" exist in the turn-resolution stack,
two of which share the "Result" name. This was latent before; diary 055's
plan to introduce a sealed `TurnResult` would have made it a three-way
collision. Fixing now so 055 can start from clean names.

| Today | What it represents | Bad name? |
|-------|--------------------|-----------|
| `BattleLoop.TurnResult` | **Log entry** for one completed turn (turnNumber, events, replacementEvents). Read by renderers and tests. | Yes — "Result" implies a primary output, but this is a *history entry*, not the turn's direct output. |
| `TurnPipeline.Result` | **Direct output** of one pipeline run (finalState, events). No post-KO bookkeeping. | Marginal — the nested qualifier (`TurnPipeline.Result`) makes it clear in use. Leave alone for now; 055 will reshape this anyway. |
| *(proposed)* 055's sealed type | **Resumability signal** — did the turn complete, or is it paused awaiting input? | Would clash with `BattleLoop.TurnResult`. |

## The fix

- Rename `BattleLoop.TurnResult` → **`TurnRecord`**. Captures the
  "history-log entry" semantics: a record of what happened on a resolved
  turn. `BattleResult.turnHistory: List<TurnRecord>` reads naturally.
- Leave `TurnPipeline.Result` alone. Nested, unambiguous in use, and 055
  is about to replace it with a sealed `TurnResolution` type.
- Diary 055 will use **`TurnResolution`** (sealed, variants `Completed`
  and `NeedInput`) — the verb form that matches `Phase.resolve` /
  `TurnPipeline.resolve`. No collision with either existing name.

## Scope

- `engine/src/main/kotlin/com/pokemon/battle/loop/BattleLoop.kt` — 4 line changes
- No test touches (tests use `.turnHistory` + field access, never the type
  name — verified by grep).
- `BattleRenderer.kt` uses `for (turnResult in result.turnHistory)` — the
  loop variable name is local; no dependency on the type name.
- Docs: `docs/architecture.md` and older diaries (010, 014) mention
  `TurnResult`. Update only if they become misleading; older diaries are
  history, not current docs.

## Plan

- [ ] Rename `data class TurnResult` → `data class TurnRecord` in
      BattleLoop.kt
- [ ] Update the three internal references in BattleLoop.kt to match
- [ ] Run `./gradlew test ktlintCheck detekt` — must be green with no
      other changes
- [ ] Update `docs/architecture.md` if the old name appears in the
      description of the game loop

## Why do this before 055 Phase 1

- 055 will introduce `TurnResolution`. If `TurnResult` still means
  "history entry," the sealed `TurnResolution` conceptually sits beside
  it with different semantics. Clean.
- If we *didn't* rename first, 055 would either collide on `TurnResult`
  or introduce a third name to sit alongside the mis-named one. Both
  worse than a cheap rename now.

## Related

- **Diary 055** — consumes the clean names. Starts after this lands.
- **Diary 010** — the original game loop design; used "TurnResult"
  without yet knowing how layered the output would become.

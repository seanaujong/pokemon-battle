# Diary 062: Replace resume-detection heuristics with explicit paused-slot

**Date:** 2026-04-14
**Status:** Complete (2026-04-14). Two event-scanning helpers (`slotAlreadyActed`, `slotIsMidSelfSwitch`) replaced with one `pausedSlot` helper + an inlined `any { MoveAttempted }` check in `stepForSlot`. Nested-pause correctness bug eliminated. All 246 tests green; CLI U-turn flow unchanged.

## The problem

`MoveExecutionPhase` detects resume state by scanning `partialTurnEvents`
for patterns:

```kotlin
private fun slotAlreadyActed(priorEvents, slot): Boolean {
    val lastAttempt = priorEvents.indexOfLast { it is MoveAttempted && it.attacker == slot }
    if (lastAttempt < 0) return false
    val pauseAfter = priorEvents.drop(lastAttempt).any { it is TurnPausedForInput }
    return !pauseAfter
}

private fun slotIsMidSelfSwitch(priorEvents, slot): Boolean {
    val lastAttempt = priorEvents.indexOfLast { it is MoveAttempted && it.attacker == slot }
    if (lastAttempt < 0) return false
    val afterAttempt = priorEvents.drop(lastAttempt + 1)
    val paused = afterAttempt.any { it is TurnPausedForInput }
    if (!paused) return false
    val switchedIn = afterAttempt.any { it is SwitchIn && it.slot == slot }
    return !switchedIn
}
```

Two helpers, each O(n) over partial events, pattern-matching positions of
`MoveAttempted`, `TurnPausedForInput`, and `SwitchIn` to infer "where is this
slot in its move lifecycle."

### Why it's a real (not just aesthetic) problem

Consider a hypothetical nested-pause scenario — a mechanic that pauses *after*
a self-switch completes (e.g., a Baton Pass receiver with a mid-turn ability
prompt). After the second resume:

- Slot P1 has `MoveAttempted`, `TurnPausedForInput`, `TurnInputResolved`,
  `SwitchOut`, `SwitchIn`, then another `TurnPausedForInput`.
- `slotAlreadyActed(priorEvents, P1)`: last `MoveAttempted` is at index 0;
  "drop(lastAttempt).any { TurnPausedForInput }" is true → returns `false`.
- So the phase re-runs P1 from the top, re-emitting damage and switch events.
  **Duplicate events; silent corruption.**

This isn't hypothetical forever — any second mid-turn prompt in the same
phase triggers it. Baton Pass, Revival Blessing, or a gimmick that pauses
after its effect all fall into this category.

The heuristic was fine when only one mechanic (U-turn) could pause, and only
once per turn. As a repository convention it's a latent bug.

## The fix

Stop inferring phase progression from event patterns; read it directly.

### Approach

The paused slot is always derivable from the latest `TurnPausedForInput`
event in `partialTurnEvents` (its `request.userSlot` for a
`SwitchTargetRequest`). Extract that into a one-liner helper:

```kotlin
private fun pausedSlot(priorEvents: List<BattleEvent>): Slot? {
    val lastPause = priorEvents.filterIsInstance<TurnPausedForInput>().lastOrNull()
    return (lastPause?.request as? SwitchTargetRequest)?.userSlot
}
```

Then the two helpers collapse:

- `slotIsMidSelfSwitch(events, slot)` → `pausedSlot(events) == slot`
- `slotAlreadyActed(events, slot)` → `slot has MoveAttempted in events AND is
  not the paused slot`

On a fresh turn (no `TurnPausedForInput`), `pausedSlot` returns null and
`slotAlreadyActed` returns false for every slot (none have `MoveAttempted`
yet either). Correct.

On a resume, the paused slot is explicit. Slots *before* the paused one in
speed order have `MoveAttempted` (they finished); the paused slot is
handled via the resume branch; slots *after* haven't moved yet. Correct for
any number of pauses.

### Why this beats the current heuristic

| Concern | Current heuristic | New model |
|---------|-------------------|-----------|
| Single U-turn pause | correct | correct |
| Nested pauses | broken (false positives) | correct |
| Code complexity | two helpers, interacting | one derivation, two trivial checks |
| What the code *says* | "infer from position of markers" | "which slot is paused, according to the last pause event" |

### Scope

Mechanical; no architecture change:

- Rewrite `slotAlreadyActed` and `slotIsMidSelfSwitch` in
  `MoveExecutionPhase`.
- Extract the `pausedSlot` helper.
- Existing tests (U-turn pause + resume) should pass unchanged.
- Optional: add a test that exercises the nested-pause case by constructing
  a `PipelineState` with two `TurnPausedForInput` events (extensibility
  test; no mechanic currently produces this).

Not in scope:
- Changing `PipelineState` shape.
- Changing `ControlEvent` hierarchy.
- Removing `partialTurnEvents` — still needed for render & response
  extraction.

## Why not deferring

The CLAUDE.md preflight #4 (diary backlog scan) caught this as the one
remaining structural concern after 061 shipped. Backlog review showed:

- **058** — still aesthetic.
- **060** — still no forcing function.
- **062** (this one) — genuine correctness concern for future mechanics.

Small surface, concrete benefit, code is fresh in my head from 061. The
alternative ("defer until Baton Pass ships") means another diary I have to
remember, and I'd have to relearn this code when I return. Tackle now.

## Related

- **Diary 055** — Phase 2 introduced the mechanism; Phase 2 diary discussed
  the heuristic as "fragile but workable for singles with one pause."
- **Diary 061** — Surfaced this concern during the state-split refactor,
  deferred to this diary.
- If a second mid-turn-prompt mechanic (Baton Pass, etc.) lands *before*
  this diary is implemented, the bug fires.

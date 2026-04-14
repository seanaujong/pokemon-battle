# Diary 036: GimmickState Stub — Establish the Seam Now

**Date:** 2026-04-13
**Status:** Complete

## Goal

Establish the `GimmickState` seam from diary 030's "do now" list, *without* implementing
any actual gimmick (Mega/Z/Dynamax/Tera). Every future mechanic that involves a
once-per-battle transformation will consume this seam; shipping it now means the first
real gimmick is a feature addition, not a structural retrofit.

Also ship the `Ruleset` interface scoped specifically to **gimmick budget policy** — the
area where different formats diverge (Champions: one gimmick total per battle; National
Dex: one per kind; Gen 9 VGC: Tera only). Per diary 030's addendum: engine holds raw
history, ruleset decides legality.

## Scope

**In:**
- `GimmickKind` enum (MEGA, Z_MOVE, DYNAMAX, TERA)
- `UsedGimmick(kind, slot, turn)` data class — raw usage record
- `BattleState.gimmicksUsedBySide: Map<Side, List<UsedGimmick>>` with helper accessors
- `GimmickUsed` event
- `Ruleset` interface with `canUseGimmick(kind, priorUsage)` as the first decision
- `NoGimmicksRuleset` (default; disallows all — matches today's behavior)
- `PokemonChampionsRuleset` (one gimmick total per battle)
- `NationalDexRuleset` (one per kind per battle)
- `Gen9VgcTeraRuleset` (Tera only, once per battle)
- `BattleState.ruleset: Ruleset = NoGimmicksRuleset`
- Rendering branch for `GimmickUsed` event

**Out (deferred to real-gimmick diaries):**
- Actual Mega/Z/Dynamax/Tera mechanics (stat changes, movepool swap, type override)
- `TurnChoice.UseMove.gimmickFlag` (gimmick declaration on move choice)
- Consumption hook inside `MoveExecutionPhase` (no move triggers a gimmick today)

## Design

### Why `gimmicksUsedBySide: Map<Side, List<UsedGimmick>>` instead of a field on `Side`?

Our `Side` is an enum (SIDE_1, SIDE_2) — pure identity. Per-side state lives on
`BattleState` alongside `sideConditions`. This matches diary 024's pattern.

### Why `Ruleset` scoped to gimmick budgets only?

Rulesets will eventually decide: legal moves, banlists, format-specific calc tweaks, win
conditions, gimmick budgets, ... We should NOT try to design the whole interface up
front. Ship one method (`canUseGimmick`) that we actually need, let the interface grow
with demand.

### Default is restrictive

`NoGimmicksRuleset.canUseGimmick(...) = false`. This matches current engine behavior
(no gimmicks implemented → none legal). If a caller wants gimmicks enabled, they
explicitly opt into a different Ruleset at `BattleState` construction.

### Example Ruleset implementations

```kotlin
// Current default — no gimmicks allowed
object NoGimmicksRuleset : Ruleset

// Pokemon Champions / modern VGC: one gimmick total
object PokemonChampionsRuleset : Ruleset {
    override fun canUseGimmick(kind, priorUsage) = priorUsage.isEmpty()
}

// Smogon National Dex: one per kind
object NationalDexRuleset : Ruleset {
    override fun canUseGimmick(kind, priorUsage) = priorUsage.none { it.kind == kind }
}

// Gen 9 VGC current: Tera only
object Gen9VgcTeraRuleset : Ruleset {
    override fun canUseGimmick(kind, priorUsage) =
        kind == GimmickKind.TERA && priorUsage.isEmpty()
}
```

## Plan

### Step 1: Gimmick types
- [x] `GimmickKind` enum
- [x] `UsedGimmick(kind, slot, turn)` data class

### Step 2: Ruleset interface + default implementations
- [x] `Ruleset` interface with `canUseGimmick(kind, priorUsage)`
- [x] `NoGimmicksRuleset`, `PokemonChampionsRuleset`, `NationalDexRuleset`, `Gen9VgcTeraRuleset`

### Step 3: BattleState integration
- [x] `gimmicksUsedBySide: Map<Side, List<UsedGimmick>>` field
- [x] `gimmicksUsedBy(side: Side): List<UsedGimmick>` helper
- [x] `ruleset: Ruleset = NoGimmicksRuleset` field
- [x] `withGimmickUsed(used: UsedGimmick)` helper
- [x] `canUseGimmick(kind: GimmickKind, side: Side): Boolean` helper that consults ruleset

### Step 4: Event
- [x] `GimmickUsed(side, kind, slot)` event; apply() records via `withGimmickUsed`

### Step 5: Rendering
- [x] TextRenderer branch for `GimmickUsed`

### Step 6: Tests
- [x] Recording a gimmick appears in `gimmicksUsedBy(side)`
- [x] NoGimmicksRuleset rejects all
- [x] PokemonChampionsRuleset: first gimmick allowed, second rejected regardless of kind
- [x] NationalDexRuleset: one Mega + one Z allowed; second Mega rejected
- [x] Gen9VgcTeraRuleset: Tera allowed, Mega rejected
- [x] BattleState.canUseGimmick returns the ruleset's answer

## Success criteria

- Plumbing exists and is type-safe
- Rulesets differ in their budget rules and the helpers reflect that
- No engine code hardcodes a specific gimmick budget policy
- All 200 existing tests pass — stub is purely additive

## What this unlocks

Future diaries can add gimmick mechanics (Mega Evolution, Tera) by:
1. Adding a `UseMove.gimmickFlag: GimmickKind?` field
2. In MoveExecutionPhase, if the flag is set: check `state.canUseGimmick(...)` and emit
   `GimmickUsed` + gimmick-specific state change (e.g. Tera type override, Mega stat swap)
3. No changes to Ruleset, BattleState shape, or existing hooks

That's the whole point of shipping the seam now.

## Related

- **Diary 030** — architectural twists; this implements meta-lessons 2 and 10
- **Diary 028** — data-shape divergence; gimmicks extend the model with optional fields
  (the "extend, don't fork" principle)

# Diary 005: Stat-Changing Moves

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement stat-changing moves (Swords Dance, Nasty Plot, Growl, etc.) and the `StatChanged` event. This validates that stat stages flow correctly through the damage calc and speed ordering.

## Decisions

1. **`MoveEffect` as a list on `Move`.** Moves carry `effects: List<MoveEffect>` defaulting to empty. Damaging moves process damage first, then effects. Status moves skip damage and just process effects. Extensible to multi-effect moves (Flare Blitz = damage + recoil + burn chance) later.

2. **`MoveTarget` on `Move`.** The move's primary target determines the phase's control flow:
   - `OPPONENT` + power > 0: run damage calc against opponent, then process effects
   - `OPPONENT` + power == 0: skip damage, process effects against opponent (Growl, Thunder Wave)
   - `SELF`: skip damage, process effects on self (Swords Dance, Calm Mind)

3. **`StatChanged` event** with `StatType` enum. Single event type naming the stat and stage change. `apply()` uses `StatStages.withChange()` which clamps to -6..6.

4. **`MoveCategory.STATUS`** added. Informational — the phase branches on target + power, not on category.

5. **Stage clamping** in `StatStages.withChange()`. Silently clamps — boosting past +6 keeps it at +6.

## Plan

### Step 1: New types (done)
- [x] `MoveTarget` enum: `SELF`, `OPPONENT`
- [x] `StatType` enum: `ATTACK`, `DEFENSE`, `SPECIAL_ATTACK`, `SPECIAL_DEFENSE`, `SPEED`
- [x] `MoveEffect` sealed interface with `StatBoost(stat, stages)`
- [x] `StatChanged(target, stat, stages)` event with clamping `apply()`
- [x] `Move` updated with `target: MoveTarget` and `effects: List<MoveEffect>`
- [x] `MoveCategory.STATUS` added
- [x] `StatStages.withChange(stat, stages)` helper with clamping

### Step 2: MoveExecutionPhase handles effects (done)
- [x] `executeMove` branches: if target is OPPONENT and power > 0, run damage calc
- [x] After damage (or instead of), iterate `move.effects` and emit events
- [x] `resolveEffect` dispatches on `MoveEffect` type

### Step 3: Swords Dance tests (done)
- [x] Swords Dance raises user's attack by 2 stages
- [x] Boosted attack increases damage on the following turn (~2x ratio)
- [x] Stat stage multiplier at +2 = 2.0x confirmed

### Step 4: Growl tests (done)
- [x] Growl lowers opponent's attack by 1 stage
- [x] Reduced attack decreases damage
- [x] Stage clamping at +6 and -6 works correctly
- [x] StatChanged event applies clamping via StatStages.withChange

### Step 5: Existing tests (done)
- [x] All 18 existing tests pass unchanged (damaging moves have empty effects by default)

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | `./gradlew compileKotlin` | PASS |
| 2 | `./gradlew compileKotlin` | PASS |
| 3-4 | `./gradlew test` — 8 new stat change tests | PASS |
| 5 | `./gradlew test` — 18 existing tests unchanged | PASS |
| All | 26 tests total, 0 failures | PASS |

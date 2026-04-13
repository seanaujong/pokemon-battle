# Diary 012: Architectural Debt Cleanup

**Date:** 2026-04-13
**Status:** Complete

## Goal

Address the three architectural debt items and two gen-specific leaks identified in architecture.md. This is a refactoring diary — no new features, all 66 tests must continue to pass.

## Items

### 1. Split BattleEvent across files (mechanical)

16 event types in one 193-line file. Split by concern within `engine/` package:

- `BattleEvent.kt` — sealed interface + core events (MoveOrderDecided, MoveAttempted, MoveFailed, DamageDealt, PokemonFainted)
- `StatusEvents.kt` — StatusApplied, StatusCleared, StatusDamage
- `WeatherEvents.kt` — WeatherDamage, WeatherTick, WeatherSet
- `SwitchEvents.kt` — SwitchOut, SwitchIn
- `StatEvents.kt` — StatChanged, VolatileChanged
- `AbilityEvents.kt` — AbilityTriggered, AbilityBlocked
- `ItemEvents.kt` — ItemHealing

### 2. Extract per-target resolution from MoveExecutionPhase

The damage loop in `executeMove` (ability check → damage → faint) becomes a dedicated function. Status checks in `checkStatusThenExecute` become a separate helper. This brings MoveExecutionPhase down from 159 lines to ~80, with clear sub-functions.

### 3. Make DamageCalc injectable

`calculateDamage` is a free function with gen-specific rules (burn penalty, STAB, formula). Make it a `fun interface` that phases inject:

```kotlin
fun interface DamageCalculator {
    fun calculate(attacker: PokemonState, defender: PokemonState, 
                  move: Move, roll: (IntRange) -> Int, spreadModifier: Double): DamageResult
}
```

The current implementation becomes `GenVDamageCalculator`. `MoveExecutionPhase` takes a `DamageCalculator` parameter instead of calling a free function. Tests can inject a simpler calculator.

### 4. Make speed calculation injectable

`PokemonState.effectiveSpeed()` contains paralysis logic. Extract to a `fun interface`:

```kotlin
fun interface SpeedResolver {
    fun effectiveSpeed(pokemon: PokemonState): Double
}
```

The current implementation becomes `GenVSpeedResolver`. `resolveMoveOrder` takes a resolver. `PokemonState.effectiveSpeed()` stays as a convenience but delegates to the default resolver.

Actually — this might over-engineer it. The speed function is 3 lines. Making it injectable adds a parameter to `resolveMoveOrder`, which flows up to `MoveOrderPhase` and `MoveExecutionPhase`. That's a lot of plumbing for a 3-line function.

**Alternative:** Just move the paralysis modifier from `PokemonState` to `resolveMoveOrder` and `MoveExecutionPhase` where it's used. The function stays simple; the gen-specific rule moves to the phase layer.

### 5. Effects intermediate state tracking

The effects loop at the end of `executeMove` doesn't apply events between effects. Refactor to track `currentState` across effect events, same pattern as the damage loop.

## Plan

### Step 1: Split BattleEvent
- [ ] Create event files by concern
- [ ] Move event classes to appropriate files
- [ ] Keep sealed interface in `BattleEvent.kt`
- [ ] Compile and test

### Step 2: Extract per-target resolution
- [ ] Move damage-per-target loop to a `resolveDamagePerTarget` function
- [ ] Move status checks to a `resolveStatusCheck` function
- [ ] MoveExecutionPhase.resolve() becomes a clean orchestrator
- [ ] Compile and test

### Step 3: Injectable DamageCalc
- [ ] `DamageCalculator` fun interface in engine
- [ ] Rename current function to `GenVDamageCalculator` implementing it
- [ ] `MoveExecutionPhase` takes `DamageCalculator` parameter
- [ ] Default to `GenVDamageCalculator`
- [ ] Compile and test

### Step 4: Move paralysis speed to phase layer
- [ ] Remove paralysis modifier from `PokemonState.effectiveSpeed()`
- [ ] Add paralysis check in `resolveMoveOrder` and speed comparisons
- [ ] Or: make `effectiveSpeed` take a modifier function
- [ ] Compile and test

### Step 5: Effects intermediate state
- [ ] Track `currentState` across effect events in `executeMove`
- [ ] Apply each effect event before processing the next
- [ ] Compile and test

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | BattleEvent split — 7 files | PASS |
| 2+5 | MoveExecutionPhase extraction + effects state | PASS |
| 3 | Injectable DamageCalculator | PASS |
| 4 | SpeedResolver extraction | PASS |
| All | 66 tests, 0 failures | PASS |

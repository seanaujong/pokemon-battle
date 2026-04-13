# Diary 004: Status Mechanics — Paralysis, Sleep, Freeze

**Date:** 2026-04-12
**Status:** Complete

## Goal

Implement the status conditions that prevent or modify action: paralysis (speed halving + chance to skip), sleep (can't act, turn counter), and freeze (can't act, chance to thaw). This exercises the `MoveFailed` event which exists but has never been emitted.

## How randomness works per status

| Status | At infliction | Per turn |
|--------|--------------|----------|
| Paralysis | Nothing special | 25% chance to skip (`chanceCheck`) |
| Sleep | Set counter 1-3 (`chanceCheck` at infliction) | Decrement counter (deterministic) |
| Freeze | Nothing special | 20% chance to thaw (`chanceCheck`) |

Sleep's randomness is front-loaded: the duration is decided when sleep is inflicted, then it counts down deterministically. Paralysis and freeze are per-turn coin flips.

## Decisions

1. **Paralysis speed halving** lives in `PokemonState.effectiveSpeed()`. It already applies stat stages — paralysis is just another modifier. This means move ordering automatically reflects paralysis without `resolveMoveOrder` needing to know about it.

2. **Chance checks** use a `ChanceCheck` type alias: `(percentChance: Int) -> Boolean`. Default implementation rolls `(1..100).random() <= percent`. Tests pass `{ true }` or `{ false }` for deterministic results. This stays separate from the damage `roll: (IntRange) -> Int` parameter — different kinds of randomness, different interfaces.

3. **Sleep turn counter** is a `Volatile.Sleep(turnsRemaining: Int)`. This follows the existing `Volatile.Confusion(turnsRemaining: Int)` pattern. `StatusCondition.SLEEP` says *what* you have, the volatile tracks *how long*. Keeps `PokemonState` tight and sets the precedent for toxic poison (`Volatile.ToxicCounter(turnCount: Int)`) later. The counter is set at infliction time (1-3 turns, rolled via `chanceCheck` or similar), not per-turn.

4. **Sleep counter decrement is an event after all.** Originally planned as internal, but the counter must be persisted in immutable state — events are the only mechanism. `VolatileChanged(target, old, new)` handles this generically. The log shows `VolatileChanged` + `MoveFailed("asleep")` when sleeping, or `StatusCleared` when waking.

5. **StatusCleared event** is generic: `StatusCleared(target, status)`. Its `apply()` clears both the status and any related volatile (e.g., `Volatile.Sleep`).

6. **Status checks** happen in `MoveExecutionPhase.checkStatusThenExecute()`, after the faint check and before `MoveAttempted`. Order: fainted → sleep → freeze → paralysis → attempt move.

7. **Fire-type thaw** deferred.

## Plan

### Step 1: ChanceCheck type, VolatileChanged, and StatusCleared events (done)
- [x] `ChanceCheck` type alias + `defaultChanceCheck` in engine package
- [x] `VolatileChanged(target, old, new)` event — generic volatile update
- [x] `StatusCleared(target, status)` event — clears status and related volatiles
- [x] `Volatile.Sleep(turnsRemaining: Int)` added
- [x] Compile check passes

### Step 2: Paralysis (done)
- [x] `effectiveSpeed()` applies 0.5x when status is PARALYSIS
- [x] `MoveExecutionPhase` takes a `chanceCheck: ChanceCheck` parameter
- [x] 25% skip chance before move attempt
- [x] Emits `MoveFailed(attacker, "fully_paralyzed")` when triggered
- [x] Tests: speed halving, speed order reversal, always-skip, always-act

### Step 3: Sleep (done)
- [x] `checkStatusThenExecute` decrements volatile counter
- [x] Counter > 0: emits `VolatileChanged` + `MoveFailed("asleep")`
- [x] Counter hits 0: emits `StatusCleared`, then acts normally
- [x] Tests: counter decrement, wake after N turns, multi-turn sleep sequence

### Step 4: Freeze (done)
- [x] 20% thaw chance per turn via `chanceCheck`
- [x] Thaw fails: `MoveFailed("frozen")`
- [x] Thaw succeeds: `StatusCleared(FREEZE)` then acts
- [x] Tests: frozen with failed thaw, frozen with successful thaw

### Step 5: Integration test (done)
- [x] P1 paralyzed (speed 100 → 50), P2 asleep with Sleep(2) (speed 55)
- [x] P2 goes first (faster after paralysis), but is asleep
- [x] P1 acts (paralysis skip doesn't trigger)
- [x] Full event sequence validated

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | `./gradlew compileKotlin` | PASS |
| 2 | `./gradlew test` — paralysis tests | PASS |
| 3 | `./gradlew test` — sleep tests | PASS |
| 4 | `./gradlew test` — freeze tests | PASS |
| 5 | `./gradlew test` — integration test | PASS |
| All | 18 tests total (8 existing + 10 new), 0 failures | PASS |

## Design surprise

**Sleep counter can't be "internal."** We originally planned to handle the counter decrement inside the phase without emitting an event. But because state is immutable, the only way to persist a changed counter is through an event's `apply()`. This led to adding `VolatileChanged` — a generic event for updating any volatile's state. It's actually cleaner: the event log now shows the counter ticking down, which is useful for debugging sleep duration bugs.

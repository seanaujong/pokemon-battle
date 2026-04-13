# Diary 009: Switching — Bench, Voluntary Switch, Faint Replacement

**Date:** 2026-04-13
**Status:** Complete

## Goal

Add team rosters with a bench, voluntary switching as a turn choice, and faint replacement between turns. This is the foundation for the multi-turn game loop.

## Switching scenarios

| Scenario | When it happens | Scope |
|----------|----------------|-------|
| Voluntary switch | Start of turn, before moves | This diary |
| Faint replacement | After a Pokemon faints, between turns | This diary |
| Forced switch (Roar, Whirlwind) | During move execution | Deferred — needs move effect trigger |
| Self-switch (U-turn, Volt Switch) | After move damage | Deferred — needs move effect trigger |

Forced and self-switch are deferred but the architecture must accommodate them. Both happen during `MoveExecutionPhase` and would be triggered by `MoveEffect` subclasses — the bench is accessible from `BattleState`, so the phase can emit switch events.

## Design

### Bench in BattleState

```kotlin
data class BattleState(
    val slots: Map<Slot, PokemonState>,
    val bench: Map<Side, List<PokemonState>>,
    val field: FieldState = FieldState(),
    val turn: Int = 1
)
```

The bench is per-side. When a Pokemon switches in, it moves from `bench[side][index]` to `slots[slot]`. The outgoing Pokemon moves to the bench. Volatiles are cleared on switch-out (that's what volatiles are — temporary conditions cleared on switch).

### New events

```kotlin
SwitchOut(slot: Slot)
  → apply: moves slot's PokemonState to bench, clears volatiles

SwitchIn(slot: Slot, benchIndex: Int)
  → apply: moves bench[side][index] to the slot, removes from bench
```

Split into two events so the log shows both "Charizard, come back!" and "Go, Blastoise!" as separate messages, matching the games. Also allows future switch-in triggers (entry hazards, Intimidate) to fire between `SwitchOut` and `SwitchIn`.

### TurnChoice.Switch

```kotlin
data class Switch(val benchIndex: Int) : TurnChoice
```

The player picks which bench Pokemon to send in. The index refers to `bench[side]`.

### Switch timing

In the games, voluntary switches resolve before moves (at a pseudo-priority of +6). This means:

1. **SwitchPhase** runs before `MoveExecutionPhase`
2. It processes all `TurnChoice.Switch` choices, emitting `SwitchOut` + `SwitchIn` events
3. `MoveExecutionPhase` then sees the new Pokemon in the slots

The pipeline becomes: `MoveOrderPhase` → `SwitchPhase` → `MoveExecutionPhase` → `EndOfTurnPhase`

Actually — `MoveOrderPhase` currently orders all slots. Switching slots shouldn't appear in the move order. The order phase should only include slots that chose a move. Slots that chose to switch are handled by `SwitchPhase` first.

Wait — `resolveMoveOrder` already handles this: it calls `choices.choiceFor(slot)` and the `as? TurnChoice.UseMove` check returns null for switches, so switch slots are excluded from the order. This already works.

### Faint replacement

When a Pokemon faints during a turn, the player must send in a replacement before the next turn. This isn't a turn choice — it happens between turns. Options:

A. The game loop detects fainted slots after `TurnPipeline.resolve()` and collects replacement choices, then applies `SwitchIn` events to the state before the next turn.

B. A `FaintReplacementPhase` at the end of the pipeline that handles it.

Option A is cleaner — faint replacement is a game loop concern, not a turn resolution concern. The pipeline resolves the turn; the game loop handles between-turn bookkeeping.

But we don't have a game loop yet. For now: provide a `switchIn(state, slot, benchIndex)` utility function that the game loop (or tests) can call between turns. It returns the updated state.

### Volatile clearing on switch-out

When a Pokemon switches out, its volatiles are cleared. Status conditions (burn, poison, etc.) persist. This is what distinguishes volatiles from status.

`SwitchOut.apply()` handles this: clears the slot's volatiles, clears stat stages (stat boosts don't persist through switches), then moves the Pokemon to the bench.

## Plan

### Step 1: Bench in BattleState
- [ ] Add `bench: Map<Side, List<PokemonState>>` to `BattleState`
- [ ] Default to empty bench: `bench = emptyMap()`
- [ ] Update factory methods: `singles()` and `doubles()` get optional bench params
- [ ] New factory: `BattleState.withTeams(...)` for setting up full teams
- [ ] Compile check — existing tests use default empty bench

### Step 2: SwitchOut and SwitchIn events
- [ ] `SwitchOut(slot)` — clears volatiles, clears stat stages, moves Pokemon to bench
- [ ] `SwitchIn(slot, benchIndex)` — moves bench Pokemon to slot, removes from bench
- [ ] Tests: switch out clears volatiles and stat stages, switch in places correct Pokemon

### Step 3: TurnChoice.Switch
- [ ] Add `Switch(benchIndex: Int)` to `TurnChoice` sealed interface
- [ ] `resolveMoveOrder` already skips non-UseMove choices — verify

### Step 4: SwitchPhase
- [ ] New phase that runs before `MoveExecutionPhase`
- [ ] Processes all `TurnChoice.Switch` choices
- [ ] Emits `SwitchOut` + `SwitchIn` for each switching slot
- [ ] Pipeline becomes: `MoveOrderPhase` → `SwitchPhase` → `MoveExecutionPhase` → `EndOfTurnPhase`

### Step 5: Faint replacement utility
- [ ] `applyFaintReplacement(state, slot, benchIndex): BattleState` — applies `SwitchIn` without `SwitchOut` (the fainted Pokemon doesn't go to bench)
- [ ] Or just use `SwitchIn` event directly — the fainted Pokemon stays fainted wherever it is

### Step 6: Tests
- [ ] Voluntary switch: P1 switches, P2 attacks the new Pokemon
- [ ] Switch clears volatiles: Pokemon with +2 attack switches out, replacement has +0
- [ ] Switch clears stat stages but not status: burned Pokemon switches out, comes back later still burned
- [ ] Faint replacement: Pokemon faints, replacement enters, next turn works
- [ ] Doubles switch: one slot switches while the other attacks
- [ ] Move order: switching slot is excluded from move ordering

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | `./gradlew compileKotlin` — bench compiles, existing tests unaffected | PASS |
| 2-3 | `./gradlew compileKotlin` — events and choice compile | PASS |
| 4 | `./gradlew compileKotlin` — SwitchPhase compiles | PASS |
| 5-6 | `./gradlew test` — all existing + new tests pass | PASS |
| All | 52 tests total (43 existing + 9 new), 0 failures | PASS |

## Bug found during implementation

**`resolveMoveOrder` included switching slots.** The `mapNotNull` block treated `TurnChoice.Switch` as a valid choice (with priority 0) instead of excluding it. Fixed by casting to `TurnChoice.UseMove` early — non-move choices are now properly excluded from move ordering.

## Future accommodation

| Scenario | How it works with this foundation |
|----------|-----------------------------------|
| Roar/Whirlwind | `MoveEffect.ForceSwitch` triggers `SwitchOut` + random `SwitchIn` during `MoveExecutionPhase`. Bench is accessible from state. |
| U-turn/Volt Switch | `MoveEffect.SelfSwitch` triggers `SwitchOut` + `SwitchIn` after damage. Same events, different trigger. |
| Entry hazards (Stealth Rock) | A future `SwitchInPhase` or hook after `SwitchIn` events. The event exists; triggers layer on top. |
| Intimidate | Same as entry hazards — trigger on `SwitchIn`, emit `StatChanged` for opponents. |
| Baton Pass | Special case of switch-out that *doesn't* clear volatiles/stat stages. Could be a flag on `SwitchOut` or a separate `BatonPass` event. |

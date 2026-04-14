# Diary 024: Speed Control — Trick Room, Tailwind, Fake Out

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement the three most important speed control mechanics in competitive play.
Speed control is the core of VGC strategy — controlling who moves first determines
games.

## Mechanics

### Trick Room

- STATUS move, -7 priority (goes last)
- Reverses speed order for 5 turns — slower Pokemon move first
- Stored as a field condition (like weather)
- `SpeedResolver` checks for Trick Room and inverts the comparison

This is the true test of `SpeedResolver` as an injectable. `GenVSpeedResolver`
needs to read field state to check for Trick Room.

**Architecture impact:** `SpeedResolver` currently takes only `PokemonState`. It
needs `BattleState` (or at least `FieldState`) to check Trick Room. This is a
signature change to the fun interface.

### Tailwind

- STATUS move, sets a side condition for 4 turns
- Doubles the speed of all Pokemon on that side
- First *side condition* — a concept we don't have yet

**Architecture impact:** `FieldState` currently has weather (global). Tailwind is
per-side. Need `sideConditions: Map<Side, Set<SideCondition>>` on `BattleState`
or a new field. `SpeedResolver` checks for Tailwind on the Pokemon's side.

### Fake Out

- PHYSICAL move, +3 priority
- Always flinches the target
- Only works on the first turn after the Pokemon switches in
- Needs turn tracking: "has this Pokemon acted since switching in?"

**Architecture impact:** Flinch is `Volatile.Flinch` (already defined). The
"first turn only" restriction needs a `Volatile.FirstTurn` or a turn counter
on `PokemonState`. Fake Out checks this before executing.

## Design

### Side conditions (new concept)

```kotlin
enum class SideCondition { TAILWIND, LIGHT_SCREEN, REFLECT, ... }

data class BattleState(
    val slots: Map<Slot, PokemonState>,
    val bench: Map<Side, List<PokemonState>>,
    val sideConditions: Map<Side, Map<SideCondition, Int>>,  // condition → turns remaining
    val field: FieldState,
    val turn: Int,
)
```

New events: `SideConditionSet(side, condition, turns)`,
`SideConditionExpired(side, condition)`.

### SpeedResolver signature change

```kotlin
fun interface SpeedResolver {
    fun effectiveSpeed(pokemon: PokemonState, state: BattleState): Double
}
```

Now takes full state for Trick Room and Tailwind checks. This changes
`resolveMoveOrder`, `SwitchPhase`, and `MoveExecutionPhase` — all callers
pass state.

## Plan

### Step 1: Side conditions on BattleState ✅
- `SideCondition` enum (TAILWIND for now)
- `BattleState.sideConditions: Map<Side, Map<SideCondition, Int>>`
- Helper `sideConditionsFor(side)` and `withSideCondition(side, condition, turns)`
- Events: `SideConditionSet`, `SideConditionTick`, `SideConditionExpired`
- Decrement in EndOfTurnPhase.tickSideConditions

### Step 2: SpeedResolver signature change ✅
- `fun effectiveSpeed(pokemon, slot, state)` — added state access
- GenVSpeedResolver now multiplies by tailwind (2.0x if active on that side)
- SimplifiedSpeedResolver ignores the new params
- Callers updated: resolveMoveOrder, SwitchPhase, tests

### Step 3: Trick Room (field condition + inverted speed) ✅
- `FieldState.trickRoomTurnsRemaining: Int` (0 = inactive)
- New `MoveEffect.SetTrickRoom(turns)` (toggles: clears if active, else sets)
- New `TrickRoomSet(turnsRemaining)` event
- `resolveMoveOrder` inverts the speed-tiebreak comparator when Trick Room active
  (priority brackets still resolve normally — higher first)
- TRICK_ROOM move registered with priority -7
- Tick down in EndOfTurnPhase.tickTrickRoom

### Step 4: Tailwind (side condition + doubled speed) ✅
- New `MoveEffect.SetSideConditionOnUserSide(condition, turns)`
- TAILWIND move registered
- GenVSpeedResolver reads `state.sideConditionsFor(slot.side)` for TAILWIND → 2x speed

### Step 5: Fake Out (flinch + first-turn restriction) ✅
- New `Volatile.JustSwitchedIn` added on SwitchIn (SwitchPhase + self-switch in MoveExecutionPhase)
- Cleared at end of turn via EndOfTurnPhase.clearJustSwitchedIn
- New `Move.requiresJustSwitchedIn: Boolean` flag (lightweight precondition)
- MoveExecutionPhase checks the flag at the start of executeMove; fails with `FailReason.NOT_FIRST_TURN` if missing
- FAKE_OUT move registered with priority +3, physical, 40 power, flinch effect, `requiresJustSwitchedIn = true`

## Architecture notes

- **Three scopes of state now established**: per-Pokemon (`Volatile`), per-side (`SideCondition`),
  per-field (`FieldState`). Each has its own lifecycle and events.
- **Trick Room lives as a sort-order inverter**, not a speed multiplier. Keeps speeds positive
  and composable; the inversion is a single comparator flip in resolveMoveOrder.
- **Move.requiresJustSwitchedIn is a lightweight precondition flag**, not the move-behavior
  registry. Per diary 029, we only build the registry at 3+ shape-A/B/C moves. Fake Out is
  our first — the flag is a pragmatic stand-in. When Counter/Pursuit arrive, we'll
  transition to the full registry and this flag folds into a `preconditionFails` hook.
- **Tailwind doesn't stack with itself** — the registry just overwrites the turn counter.
  In real games, re-using Tailwind while active also fails; our current impl just resets.
  Noted as a small fidelity gap.

## Tests: 200 total (189 → 200, +11 new in SpeedControlTest.kt)

- Trick Room: inverts order, sets the field, -7 priority, ticks down
- Tailwind: sets side condition, doubles speed via resolver, lets slower Pokemon outspeed, expires after countdown
- Fake Out: works with JustSwitchedIn, fails without, JustSwitchedIn lifecycle (granted on switch-in, cleared end of turn)

## Validation

Each step: `./gradlew test` — all existing + new tests pass

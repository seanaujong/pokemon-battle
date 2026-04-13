# Diary 024: Speed Control — Trick Room, Tailwind, Fake Out

**Date:** 2026-04-13
**Status:** Not started

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

### Step 1: Side conditions on BattleState
### Step 2: SpeedResolver signature change
### Step 3: Trick Room (field condition + inverted speed)
### Step 4: Tailwind (side condition + doubled speed)
### Step 5: Fake Out (flinch + first-turn restriction)

## Validation

Each step: `./gradlew test` — all existing + new tests pass

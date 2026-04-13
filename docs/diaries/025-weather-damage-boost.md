# Diary 025: Weather Damage Boost

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement weather-based damage modifiers for moves: Rain boosts Water 1.5x and reduces
Fire to 0.5x; Sun does the inverse. Hail and Sandstorm don't modify move damage (only
deal residual damage, already implemented).

This is a small detour from the items roadmap because it's:
- Cheap (a multiplier in `GenVDamageCalculator`)
- High-impact (changes most damage outputs in weather)
- Gen-stable (this rule has been the same since Gen 2)

## Design

`GenVDamageCalculator.calculate` doesn't currently know about field state. It needs
the active weather to apply the modifier. Options:

A. Add `weather: Weather?` parameter to the `DamageCalculator` interface
B. Pass `FieldState`
C. Pass full `BattleState`

A is minimal — calculator only needs the weather, not the whole field/state. Pass
`null` when there's no weather. Same approach as we did for `isCritical` in diary 019:
extend the interface, update all callers (no defaults on fun interface methods).

## Modifier rules

| Weather | Water moves | Fire moves | Other types |
|---------|-------------|-----------|-------------|
| Rain    | 1.5x        | 0.5x      | unchanged   |
| Sun     | 0.5x        | 1.5x      | unchanged   |
| Hail    | unchanged   | unchanged | unchanged   |
| Sandstorm | unchanged | unchanged | unchanged (defensive +50% SpDef for Rock is a separate rule, defer) |

## Plan

### Step 1: Extend DamageCalculator interface
- [x] Add `weather: Weather?` parameter
- [x] `GenVDamageCalculator` applies the multiplier
- [x] `SimplifiedDamageCalculator` ignores it
- [x] `calculateDamage` convenience function defaults to `null`
- [x] Update all callers in `MoveExecutionPhase` to pass `state.field.weather`

### Step 2: Tests
- [x] Rain boosts Water move damage
- [x] Rain reduces Fire move damage
- [x] Sun boosts Fire, reduces Water
- [x] Hail/Sandstorm don't affect Water/Fire damage
- [x] No weather (null) is unchanged
- [x] Simplified calculator ignores weather

## Validation

| Step | Validation |
|------|-----------|
| 1 | `./gradlew compileKotlin` |
| 2 | `./gradlew test` — 147 tests pass |

## Decisions made

- **Keep `weather: Weather?` nullable** rather than introducing `Weather.CLEAR` sentinel.
  Walked through both options (see conversation). Nullable wins for consistency with
  `StatusCondition?`, `Ability?`, `Item?` — all "absent" domain states are null.
- **Weather is a calculator parameter, not a field-state hook.** The calculator stays
  decoupled from `BattleState`; callers thread `state.field.weather` in. Mirrors how
  `isCritical` is passed in as a rolled input.
- **`weatherDamageModifier` is a top-level function, not a calculator method.** Enables
  direct testing without constructing a whole PokemonState.

## Deferred

- **Sandstorm SpDef boost** for Rock-type defenders (1.5x) — Gen 4+, defensive-side
  modifier. Belongs in damage calc but adds another branch.
- **Strong-weather variants** (Heavy Rain, Harsh Sunlight, Delta Stream) — Gen 6 primal
  reversion mechanic. Out of scope.

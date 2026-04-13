# Diary 016: Overridable Types on PokemonState

**Date:** 2026-04-13
**Status:** Not started

## Goal

Add a type override on `PokemonState` so a Pokemon's effective types can differ
from its species types during battle. This enables Terastallization, Camomons,
and other type-changing mechanics.

## Design

### The change

```kotlin
data class PokemonState(
    ...
    val typeOverride: List<Type>? = null,  // null = use species.types
    ...
) {
    val effectiveTypes: List<Type> get() = typeOverride ?: pokemon.species.types
}
```

One field, one derived property. Default null preserves all existing behavior.

### Who reads types?

Currently, types are read from `pokemon.species.types` in three places:

1. **`DamageCalc`** — type effectiveness against the defender, STAB check for the attacker
2. **`EndOfTurnPhase`** — weather immunity (sandstorm vs Rock/Ground/Steel)
3. **`TypeAI`** — scoring moves by effectiveness and STAB

All three should read `effectiveTypes` instead of `pokemon.species.types`.

### New event

`TypeChanged(slot, newTypes)` — for mid-battle type changes (Terastallization).
Not needed for team-building-time changes (Camomons sets `typeOverride` at construction).

## Plan

### Step 1: Add typeOverride to PokemonState
- [ ] Add `val typeOverride: List<Type>? = null`
- [ ] Add `val effectiveTypes: List<Type>` derived property
- [ ] Compile check — existing tests unaffected (null default)

### Step 2: Update type readers
- [ ] `GenVDamageCalculator` reads `effectiveTypes` for defender and attacker
- [ ] `EndOfTurnPhase` weather immunity reads `effectiveTypes`
- [ ] `TypeAI` scoring reads `effectiveTypes`
- [ ] Compile and test — all 96 tests pass unchanged

### Step 3: TypeChanged event
- [ ] `TypeChanged(slot, newTypes)` with `apply()` setting `typeOverride`
- [ ] Compile check

### Step 4: Tests
- [ ] Camomons: set typeOverride at construction, verify effectiveness changes
- [ ] Mid-battle type change: apply TypeChanged, verify damage calc uses new types
- [ ] STAB changes: attacker with overridden types gets STAB on new type's moves

## Validation

| Step | Validation |
|------|-----------|
| 1-2 | `./gradlew test` — all 96 existing tests pass |
| 3-4 | `./gradlew test` — new type override tests pass |

# Diary 017: Injectable Type Chart

**Date:** 2026-04-13
**Status:** Not started

## Goal

Make the type effectiveness chart injectable, following the same `fun interface`
pattern as `DamageCalculator` and `SpeedResolver`. This enables Inverse Battles
and any format that modifies type matchups.

## Design

```kotlin
fun interface TypeChart {
    fun effectiveness(attackingType: Type, defendingTypes: List<Type>): Double
}
```

The current `typeEffectiveness` free function becomes the default implementation
(`StandardTypeChart`). `GenVDamageCalculator` takes a `TypeChart` parameter
instead of calling the free function directly.

An `InverseTypeChart` flips the results:
- Super-effective (2x, 4x) → not very effective (0.5x, 0.25x)
- Not very effective → super-effective
- Immune (0x) → neutral (1x)

### Who reads type effectiveness?

1. **`GenVDamageCalculator`** — type multiplier in damage formula
2. **`TypeAI`** — scoring moves by effectiveness
3. **`Effectiveness.from(multiplier)`** — derives the enum from the number (unchanged)

The calc already takes injectable parameters. TypeAI would take an optional
`TypeChart` for formats where the AI needs to reason about modified matchups.

## Plan

### Step 1: TypeChart fun interface
- [ ] `TypeChart` interface with `effectiveness(Type, List<Type>): Double`
- [ ] `StandardTypeChart` wrapping current `typeEffectiveness` function
- [ ] `InverseTypeChart` that flips the result
- [ ] Compile check

### Step 2: Inject into DamageCalculator
- [ ] `GenVDamageCalculator` takes `TypeChart` (default `StandardTypeChart`)
- [ ] Or: `MoveExecutionPhase` passes chart to calc
- [ ] Compile and test — all existing tests pass

### Step 3: Tests
- [ ] Inverse: Fire vs Water is super-effective (normally not very effective)
- [ ] Inverse: Ground vs Flying is neutral (normally immune)
- [ ] Standard chart unchanged
- [ ] Full inverse battle: run a turn, verify inverted effectiveness in events

## Validation

| Step | Validation |
|------|-----------|
| 1-2 | `./gradlew test` — all existing tests pass |
| 3 | `./gradlew test` — inverse type chart tests pass |

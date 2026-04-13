# Diary 018: Second Gen Implementation

**Date:** 2026-04-13
**Status:** Not started

## Goal

Build a second set of phase implementations to prove multi-gen support is real,
not theoretical. This validates that injectable interfaces (`DamageCalculator`,
`SpeedResolver`, `TypeChart`) and separate phase implementations actually work
when you build a different gen.

## Which gen?

**Option A: Gen I** — radically different. No held items, no abilities, no special
split (Special stat = both SpAtk and SpDef), different crit formula, different
type chart (no Dark/Steel/Fairy). Interesting but requires model changes (no
SpAtk/SpDef distinction).

**Option B: Pokemon Champions** — recent game with modified rules. Changed status
durations, different paralysis rate, rebalanced type matchups. Same model, different
numbers.

**Option C: A custom "simplified" gen** — designed to exercise the injectables without
needing real game data. E.g., no STAB, no burn penalty, flat 5% paralysis rate.
Fast to implement, clearly tests the architecture without needing to research real
game mechanics.

**Recommendation:** Option C. The goal is proving the architecture, not implementing a
real gen accurately. A simplified gen with intentionally different rules makes the
differences visible and testable.

## Design

### SimplifiedDamageCalculator
- No STAB
- No burn penalty
- Simple formula: `(power * atk / def) * typeEffectiveness * roll / 100`

### SimplifiedSpeedResolver
- No paralysis modifier (paralyzed Pokemon has normal speed)

### SimplifiedEndOfTurnPhase
- Burn does 1/8 max HP (instead of 1/16)
- No weather damage at all

### Pipeline assembly

```kotlin
val simplifiedPipeline = TurnPipeline(listOf(
    MoveOrderPhase(speedResolver = SimplifiedSpeedResolver),
    SwitchPhase(speedResolver = SimplifiedSpeedResolver),
    MoveExecutionPhase(
        damageCalculator = SimplifiedDamageCalculator,
        speedResolver = SimplifiedSpeedResolver
    ),
    SimplifiedEndOfTurnPhase()
))
```

Same event types, same `BattleState`, same `BattleLoop`. Different rules.

## Plan

### Step 1: SimplifiedDamageCalculator
- [ ] Implement with no STAB, no burn penalty, simpler formula
- [ ] Test: same setup produces different damage than GenV

### Step 2: SimplifiedSpeedResolver
- [ ] No paralysis modifier
- [ ] Test: paralyzed Pokemon goes first (if faster) unlike GenV

### Step 3: SimplifiedEndOfTurnPhase
- [ ] Burn does 1/8 (double GenV's 1/16)
- [ ] No weather damage
- [ ] Test: burn damage is doubled, sandstorm deals no damage

### Step 4: Full battle comparison
- [ ] Same teams, same choices, run with GenV pipeline and Simplified pipeline
- [ ] Verify different outcomes (different damage, different speed ordering)
- [ ] Render both — visible difference in the text output

## Validation

| Step | Validation |
|------|-----------|
| 1-3 | `./gradlew test` — new gen tests pass |
| 4 | `./gradlew test` — comparison test passes |
| All | All existing GenV tests still pass unchanged |

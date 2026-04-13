# Diary 019: Critical Hits

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement critical hit mechanics. A critical hit deals 1.5x damage and ignores
the defender's positive stat stages. This replaces the hardcoded `critical = false`
in `MoveExecutionPhase` and exercises the `ChanceCheck` system.

## Design

### How crits work (Gen VI+)

- Base crit rate: 1/24 (~4.2%)
- Moves with high crit ratio: 1/8 (~12.5%)
- +1 crit stage (Scope Lens, Super Luck): 1/8
- +2 crit stage: 1/2
- +3 or higher: guaranteed

For now: implement the base 1/24 rate using `ChanceCheck`. High crit ratio
moves and crit stages can come later as `MoveEffect` and item/ability modifiers.

### What a crit does

1. Damage multiplied by 1.5x
2. Ignores defender's positive Defense/SpDef stat stages (negative stages still apply)
3. Ignores attacker's negative Attack/SpAtk stat stages (positive stages still apply)
4. The `DamageDealt` event already has a `critical: Boolean` field — the renderer
   already checks it for "A critical hit!" text

### Where it lives

In `GenVDamageCalculator` — the crit roll and multiplier are gen-specific rules.
The `SimplifiedDamageCalculator` can skip crits entirely (always `critical = false`).

The `DamageCalculator.calculate()` signature doesn't need to change — `DamageResult`
already returns `critical: Boolean`.

But wait — the crit roll needs `ChanceCheck`. Currently the damage calculator doesn't
have access to it. The crit roll happens *during* damage calculation (it affects the
multiplier), so either:

A. Pass `ChanceCheck` to the calculator
B. The phase rolls the crit and passes a boolean to the calculator
C. The calculator has its own RNG

Option B keeps the calculator pure (deterministic given inputs) and the phase handles
randomness — consistent with how `roll` works. The phase rolls `isCritical` and passes
it to the calculator.

### Changes needed

1. `DamageCalculator.calculate()` gains `isCritical: Boolean` parameter
2. `GenVDamageCalculator` applies 1.5x multiplier and ignores stat stages when crit
3. `MoveExecutionPhase.resolveDamage()` rolls crit via `chanceCheck` and passes to calc
4. `SimplifiedDamageCalculator` ignores the parameter (always non-crit)
5. Tests with `chanceCheck = { _, _ -> true }` to force crits

## Plan

### Step 1: Update DamageCalculator interface
- [ ] Add `isCritical: Boolean` parameter
- [ ] Update `GenVDamageCalculator` — 1.5x multiplier, ignore stat stages
- [ ] Update `SimplifiedDamageCalculator` — ignore parameter
- [ ] Update convenience `calculateDamage` function
- [ ] Compile check — existing callers break, fix them

### Step 2: Roll crits in MoveExecutionPhase
- [ ] In `resolveDamage`, before each target: roll crit with `chanceCheck(4, FailReason...)`
- [ ] Wait — `ChanceCheck` uses `FailReason` which is for move failures, not crits. Need a
      different approach. Maybe `roll(1..24) == 1` using the existing `roll` parameter.
- [ ] Pass `isCritical` to damage calculator
- [ ] `DamageDealt` event gets the correct `critical` value

### Step 3: Tests
- [ ] Test: forced crit deals more damage than non-crit
- [ ] Test: crit ignores defender's +6 defense (damage same as neutral defense)
- [ ] Test: renderer shows "A critical hit!" on crit events
- [ ] Test: SimplifiedDamageCalculator ignores crit flag

## Validation

| Step | Validation |
|------|-----------|
| 1 | `./gradlew compileKotlin` |
| 2 | `./gradlew compileKotlin` |
| 3 | `./gradlew test` — all tests pass |

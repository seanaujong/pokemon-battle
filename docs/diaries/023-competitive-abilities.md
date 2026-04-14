# Diary 023: Competitive Abilities — Sturdy, Emergency Exit, Red Card

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement abilities that trigger during damage resolution at different points.
Each exercises a new hook in the damage pipeline.

## Abilities by trigger point

| Ability | When | Effect | Architectural need |
|---------|------|--------|-------------------|
| Sturdy | Pre-damage-apply | Survive OHKO at 1 HP from full health | Modify damage amount before `DamageDealt.apply()` |
| Emergency Exit | Post-damage-apply | Forced switch when HP ≤ 50% | Post-damage trigger → switch events mid-execution |
| Red Card (item) | Post-damage-apply | Force *attacker* to switch out | Post-damage trigger → switch on *opponent's* side |

## Design considerations

### Sturdy — damage modification

Sturdy modifies the damage *amount* before it's applied. If the Pokemon is at
full HP and the damage would KO, reduce damage to `currentHp - 1`.

This happens between `calculateDamage` returning a result and emitting
`DamageDealt`. Currently `resolveDamage` emits the event directly. Need a
modification step:

```
result = calculator.calculate(...)
adjustedAmount = checkSturdy(defender, result.damage)
emit DamageDealt(amount = adjustedAmount)
```

New informational event: `AbilityActivated(slot, ability)` — "Sturdy held on!"

### Emergency Exit — forced switch after damage

After `DamageDealt` is applied, check if the defender's HP dropped below 50%
and it has Emergency Exit. If so, trigger a forced switch.

This requires the same switch infrastructure as U-turn (diary 021) — emit
`SwitchOut` + `SwitchIn` mid-execution. But the replacement choice comes from
the *defender's* side, not the attacker's.

### Red Card — force opponent switch

After being hit, the Red Card holder forces the *attacker* to switch to a
random bench Pokemon. The item is consumed.

This combines item consumption (diary 022) with forced switching on the
opponent's side. The attacker doesn't choose — a random bench Pokemon is sent in.

## Plan

### Step 1: Sturdy (pre-apply damage modification) ✅
- Mirrored `interceptIncomingDamage` onto `AbilityEffect` (same shape as Focus Sash's)
- `DamageAdjustment` moved out of `engine/item/` to shared `engine/` package
- SturdyEffect returns `consumed = false` (abilities don't consume)
- MoveExecutionPhase: ability intercept checked before item intercept (Sturdy wins over Focus Sash)
- Emits `AbilityTriggered` after the damage event for rendering

### Step 2: Emergency Exit (post-apply forced self-switch) ✅
- Mirrored `onHpThresholdCrossed` onto `AbilityEffect`
- Hook signature extended with `state: BattleState` for bench access (Sitrus now ignores the new param)
- EmergencyExitEffect emits `AbilityTriggered` + clearing + `SwitchOut` + `SwitchIn`
  targeting the first available bench Pokemon (limitation: real games let the player choose)
- Switch-in ability triggers via `resolveSwitchInAbility` on the post-switch state

### Step 3: Red Card (post-apply forced opponent switch) ✅
- New `onHolderTookDamage(holder, holderSlot, attacker, attackerSlot, state, damage)` hook
  on `ItemEffect` — generalized "on-hit" hook for Red Card today, Rocky Helmet / Jaboca
  Berry tomorrow
- RedCardEffect emits `ItemConsumed` + forces attacker's first-available bench switch
- If attacker has no bench, Red Card does not trigger and stays held (matches real game)

## Refactors along the way

- Extracted `resolveDamagePerTarget`, `thresholdEvents`, `onHitEvents` from `resolveDamage`
  to keep the method under cyclomatic/length thresholds
- `AbilityEffect` / `ItemEffect` hit TooManyFunctions threshold; both suppressed with
  rationale (each hook is genuinely a distinct capability)

## Limitations noted

- **Forced switches pick the first available bench.** Emergency Exit and Red Card both
  use `bench.indexOfFirst { !it.isFainted }`. Real games let the player choose (for
  EmergencyExit) and pick randomly (for Red Card). A `ForcedSwitchProvider` interface on
  the pipeline would lift this — deferred until we care enough.

## Tests

7 new in `CompetitiveAbilitiesTest.kt` covering:
- Sturdy survives KO hit at full HP (+ AbilityTriggered emission)
- Sturdy does nothing below full HP
- Emergency Exit triggers when HP crosses 50% threshold + replacement comes in
- Emergency Exit does nothing without bench
- Red Card forces attacker switch after damaging hit
- Red Card does nothing if attacker has no bench
- Red Card does not trigger on non-damaging move

Total: 182 → 189 tests, all passing.

## Validation

Each step: `./gradlew test` — all existing + new tests pass

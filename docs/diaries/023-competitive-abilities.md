# Diary 023: Competitive Abilities — Sturdy, Emergency Exit, Red Card

**Date:** 2026-04-13
**Status:** Not started

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

### Step 1: Sturdy (pre-apply damage modification)
### Step 2: Emergency Exit (post-apply forced self-switch)
### Step 3: Red Card (post-apply forced opponent switch)

## Validation

Each step: `./gradlew test` — all existing + new tests pass

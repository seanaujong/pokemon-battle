# Diary 022: Competitive Items — Choice, Life Orb, Focus Sash, Eviolite

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement the most-used held items in competitive play. Each exercises a different
integration point in the damage pipeline.

## Items by trigger point

| Item | When it triggers | What it does |
|------|-----------------|--------------|
| Choice Band/Specs/Scarf | Damage calc / speed calc | 1.5x Atk/SpAtk/Speed, locks into one move |
| Life Orb | After damage dealt | 1.3x damage, user loses 10% max HP |
| Focus Sash | Before damage applied | Survive any hit at 1 HP from full health (consumed) |
| Eviolite | During damage calc | 1.5x Def/SpDef for not-fully-evolved Pokemon |
| Sitrus Berry | After HP drops below 50% | Restore 25% max HP (consumed) |

## Design considerations

### Item consumption

Focus Sash and Sitrus Berry are consumed after use. Need a way to remove an item
from `PokemonState`. New event: `ItemConsumed(slot, item)` that sets `item = null`.

### Choice locking

Choice items lock the user into their first move. After using a move, set
`Volatile.ChoiceLocked(move)`. The AI/CLI must respect this — only the locked move
is a valid choice on subsequent turns. Cleared on switch.

### Life Orb self-damage

After dealing damage, the user loses 10% max HP. This is a post-damage effect on
the *attacker*, not the target. Needs a new event timing in `resolveDamage` —
after the target takes damage, check if the attacker has Life Orb.

### Eviolite eligibility

Eviolite only works on not-fully-evolved Pokemon. This requires knowing if a species
can evolve — a data concern. For now: trust the setup (if the Pokemon has Eviolite,
it's eligible). Validation belongs in team building, not the engine.

### Integration with DamageCalculator

Choice Band/Specs and Eviolite modify effective stats during damage calculation.
The calculator already reads `PokemonState.item` — it just doesn't check it yet.
These are gen-specific modifiers, so they belong in `GenVDamageCalculator`.

Choice Scarf modifies speed — belongs in `GenVSpeedResolver`.

## Plan

### Step 1: Item consumption event ✅
- `ItemConsumed(slot, item)` event added to `ItemHealing.kt` (colocated with other item events).
  On apply, sets `PokemonState.item = null`.

### Step 2: Focus Sash (pre-apply damage modification) ✅
- Pre-DamageDealt check in `resolveDamage`: if defender has Focus Sash, is at full HP, and
  `result.damage >= defender.currentHp`, cap damage at `currentHp - 1` and emit `ItemConsumed`.
- No PokemonFainted event fires (Pokemon survives).
- 4 tests in `FocusSashTest.kt`.

### Step 3: Life Orb (post-damage attacker self-damage) ✅
- 1.3x `attackerDamageModifier` in GenVDamageCalculator
- After damage loop: 10% max HP recoil via `afterUserMoveDamage` hook
- Fires once per move (not per-target for spread)
- 8 tests in LifeOrbTest.kt
- Triggered the diary-026 registry refactor mid-flight

### Step 4: Choice items (stat boost + move locking) ✅
- Choice Band: 1.5x physical `attackerDamageModifier`
- Choice Specs: 1.5x special `attackerDamageModifier`
- Choice Scarf: 1.5x `speedModifier` (new hook on ItemEffect, wired into GenVSpeedResolver)
- All three emit `Volatile.ChoiceLocked(move)` via `afterUserMoveDamage` after first damaging move
- Lock clears on switch-out via existing volatile-clearing in SwitchPhase
- 9 tests in ChoiceItemsTest.kt
- Kotlin `by ChoiceItem(...)` delegation shares the three implementations cleanly
- Enforcement of the lock is a choice-layer concern (AI/UI reads the volatile and restricts move selection); the engine just publishes it

### Step 5: Eviolite (defensive stat boost in calc) ✅
- `defenderDamageModifier` returns 1/1.5 (≈0.667) for Physical/Special moves
- Eligibility (not-fully-evolved species) is trusted at team-build time; the engine doesn't gate it
- 2 tests in EvioliteAndSitrusTest.kt

### Step 6: Sitrus Berry (HP threshold trigger) ✅
- New `onHpThresholdCrossed(holder, slot, previousHp, currentHp)` hook on ItemEffect
- Wired into `MoveExecutionPhase.resolveDamage` immediately after each `DamageDealt` apply
- Triggers once, at most, when HP drops from above 50% to at-or-below 50%
- Emits `ItemHealing` + `ItemConsumed`
- 3 tests in EvioliteAndSitrusTest.kt

## Notes

- **Item-enum modeling smell noticed — addressed.** TextRenderer's `when (event.item)`
  branches originally forced unreachable branches for items that don't emit the relevant
  event type (e.g. Leftovers in `ItemConsumed`). Diary 026's item registry refactor
  eliminated this by delegating rendering to per-item `renderHealing/Consumed/Damage`
  methods on `ItemEffect`. Each item provides only the strings it needs.
- **Choice lock is state-only, not enforcement.** The engine publishes
  `Volatile.ChoiceLocked(move)` after the holder uses a damaging move; it does not prevent
  future `TurnChoice.UseMove(otherMove)` from executing. Enforcement belongs in the AI /
  UI / choice-validation layer that reads the volatile and restricts selection. The engine
  doesn't need a validation phase for this.
- **New hooks introduced during 022:** `speedModifier(holder)` on both ItemEffect and
  AbilityEffect (used by Choice Scarf, future Swift Swim); `onHpThresholdCrossed(holder,
  slot, previousHp, currentHp)` on ItemEffect (used by Sitrus, future pinch berries);
  `move: Move` parameter added to `afterUserMoveDamage` (Life Orb ignores it, Choice
  items use it to emit the lock).
- **6 items registered total:** Leftovers, Focus Sash, Life Orb, Choice Band/Specs/Scarf,
  Eviolite, Sitrus Berry. Each is one file, one registry entry.

## Validation

Each step: `./gradlew test` — all existing + new tests pass

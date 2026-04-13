# Diary 022: Competitive Items — Choice, Life Orb, Focus Sash, Eviolite

**Date:** 2026-04-13
**Status:** In progress — Focus Sash done

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

### Step 3: Life Orb (post-damage attacker self-damage)
### Step 4: Choice items (stat boost + move locking)
### Step 5: Eviolite (defensive stat boost in calc)
### Step 6: Sitrus Berry (HP threshold trigger)

## Notes

- **Item-enum modeling smell noticed.** TextRenderer's `when (event.item)` branches for both
  `ItemHealing` and `ItemConsumed` have cross-branches that are unreachable (Leftovers in
  ItemConsumed, Focus Sash in ItemHealing) because one `Item` enum spans all behaviors.
  As items grow, these unreachable branches multiply. Revisit when 5+ items exist; possibly
  split into behavior-specific enums or use event-specific item subtypes.

## Validation

Each step: `./gradlew test` — all existing + new tests pass

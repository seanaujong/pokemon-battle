# Diary 026: Item Effect Registry — Pulling Items Out of the Damage Calc

**Date:** 2026-04-13
**Status:** Complete

## Background

Mid-way through diary 022 (competitive items), after adding Focus Sash and Life Orb,
we paused on a user observation: **Life Orb exists in multiple Pokemon gens, but we're
hardcoding `if (attacker.item == Item.LIFE_ORB) 1.3 else 1.0` inside
`GenVDamageCalculator`.** If we ever add a GenIV calculator (Gen 4 also has Life Orb),
we'd copy-paste the check. The same problem already existed for Focus Sash (hardcoded in
`MoveExecutionPhase`) and Leftovers (hardcoded in `EndOfTurnPhase` and
`SimplifiedEndOfTurnPhase` — already duplicated!).

That's the architectural fault line: **item behavior was scattered across callers, tightly
coupled to specific gens.** Scaling to 5 gens would mean O(items × gens) of scattered
`if` branches.

## Cross-gen damage calc landscape (from the planning discussion)

|  | Gen 3 | Gen 4 | Gen 5 | Gen 6 | Gen 7 |
|---|---|---|---|---|---|
| **Phys/Spec split** | By type | **By move** (data-layer change) | By move | By move | By move |
| **Crit multiplier** | 2x | 2x | 2x | **1.5x** | 1.5x |
| **Type chart** | No Fairy | No Fairy | No Fairy | **Fairy added** | Same as 6 |
| **Life Orb / Focus Sash** | ❌ | ✅ | ✅ | ✅ | ✅ |
| **Eviolite** | ❌ | ❌ | ✅ | ✅ | ✅ |
| **Prankster affects Dark** | — | ✅ | ✅ | ✅ | **❌** (Gen 7) |

Key observations:
- ~90% of the damage formula is identical across adjacent gens
- Gen 3 is a data-layer divergence (physical/special by type not by move)
- Most cross-gen differences are **what items/abilities exist**, not **how the formula works**
- Adding more gens without a registry means copying ~90% of the calc and changing a few `if` branches

## Design decision: registry + per-item effect objects

Borrowing from a common pattern for this kind of plugin-style extensibility:

1. **`ItemEffect` interface** — declares hooks into the damage/turn pipeline. Each hook
   defaults to no-op. Hooks we need so far:
   - `attackerDamageModifier` — multiplier on holder-dealt damage (Life Orb)
   - `defenderDamageModifier` — multiplier on holder-received damage (Eviolite future)
   - `interceptIncomingDamage` — modify damage before it lands + flag for consumption (Focus Sash)
   - `afterUserMoveDamage` — post-damage effects (Life Orb recoil)
   - `endOfTurn` — end-of-turn effects (Leftovers, future berries)
   - `renderHealing` / `renderConsumed` / `renderDamage` — per-event text

2. **Per-item object** — each item's behavior lives in one file (`LifeOrbEffect.kt`,
   `FocusSashEffect.kt`, `LeftoversEffect.kt`). Pure, isolated, testable.

3. **`ItemRegistry`** — maps `Item` enum values to their `ItemEffect`. Callers look up
   the effect and invoke the relevant hook.

4. **Callers become generic** — `GenVDamageCalculator`, `MoveExecutionPhase`,
   `EndOfTurnPhase`, `TextRenderer` no longer switch on `Item` values. They consult the
   registry and act on whatever comes back.

## Why this is the right track

- **Adding a new item is now ONE file + one registry entry.** Before: edit calc, edit
  phase, edit renderer, update multiple `when` branches, add unreachable branches to
  other `when`s.
- **Gen-specific registries become the seam for multi-gen support.** A `GenIVItemRegistry`
  would register a subset (no Eviolite); Gen 3 would register very few. The calc and phase
  code stays untouched.
- **Behavior is colocated with identity.** Life Orb's damage boost AND recoil AND
  rendering all live in one file.
- **Unreachable-branch smell is gone.** The render `when (event.item)` switches that
  had to enumerate every item for each event type — each with 2+ unreachable branches —
  are replaced with `ItemRegistry.effectFor(event.item)?.renderHealing(...)`. Each item
  provides only the text that applies.

## What changed

- **New package** `com.pokemon.battle.engine.item`:
  - `ItemEffect.kt` — interface + `DamageAdjustment` helper
  - `ItemRegistry.kt` — singleton map
  - `LeftoversEffect.kt`, `FocusSashEffect.kt`, `LifeOrbEffect.kt` — the three current items
- **Callers now delegate:**
  - `GenVDamageCalculator.calculate` — computes `attackerItemMod * defenderItemMod` via registry
  - `MoveExecutionPhase.resolveDamage` — uses `interceptIncomingDamage` for defender's item,
    `afterUserMoveDamage` for attacker's item
  - `EndOfTurnPhase.itemEffects` — one-liner: `ItemRegistry.effectFor(item)?.endOfTurn(...)`
  - `SimplifiedEndOfTurnPhase.itemEffects` — same (duplication eliminated)
  - `TextRenderer.renderItemHealing/Consumed/Damage` — registry lookup

## Behavior-preserving refactor

All 159 existing tests pass without modification. No new tests needed — this is
infrastructure.

## What this unlocks (next steps)

- **Choice items** (Band/Specs/Scarf) — adds `attackerDamageModifier` (1.5x atk/spatk) and
  probably a new hook for speed + move-locking volatile on first use
- **Eviolite** — adds `defenderDamageModifier` (1.5x def/spdef) — the hook already exists
- **Sitrus Berry** — adds a new "on HP threshold crossed" hook
- **Gen-specific registries** — when we add a second calc family, Gen registries become
  the natural seam

## Why this refactor belongs here, not later

Doing this after 3 items is right-sized:
- Before: no repetition, over-engineering for one item
- After 3: pain visible (unreachable branches, duplicated Leftovers logic, hardcoded
  checks), refactor is still small (~3 files to extract)
- After 10 items: refactor is painful; 10 files to rewrite

The user caught this *while watching the Life Orb edit land*. That kind of mid-work
architectural intuition is exactly when to pause and refactor — the cost is lowest and
the motivation is freshest.

## Lessons

1. **Hardcoded `when(enum)` switches inside shared engine code are a warning sign** when
   the enum is expected to grow and each value has non-trivial behavior. The natural
   replacement is a strategy/registry pattern.
2. **Naming conveys architectural promises.** `GenVDamageCalculator` implies "this is
   Gen 5." But putting cross-gen items (Life Orb has been 1.3x in every gen it's existed
   in) inside a "GenV" class is misleading. Either rename or pull the cross-gen stuff out.
3. **Unreachable branches in exhaustive `when`s** are a code smell. Adding a new enum
   value shouldn't force noise elsewhere. The fix is usually delegation, not more `else`
   branches.
4. **Refactoring at the right moment.** Not too early (speculation), not too late (sunk
   cost). Three items was the sweet spot — enough to see the pattern, few enough to still
   extract cheaply.

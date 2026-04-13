# Diary 006: IVs, EVs, and Natures

**Date:** 2026-04-12
**Status:** Not started

## Goal

Extend the stat formula to include Individual Values (IVs), Effort Values (EVs), and Natures. This closes the gap between our damage calculations and the worked examples in the docs, which assumed specific IV/EV spreads.

## Background

The current stat formula is simplified:
```
HP:  (2 * base * level) / 100 + level + 10
Stat: (2 * base * level) / 100 + 5
```

The full Gen V+ formula is:
```
HP:  ((2 * base + iv + ev/4) * level) / 100 + level + 10
Stat: (((2 * base + iv + ev/4) * level) / 100 + 5) * nature
```

Where:
- **IVs** range 0-31 (genetic potential, randomly determined)
- **EVs** range 0-252 per stat, 510 total cap (training investment)
- **Nature** is a 1.1x boost to one stat and 0.9x penalty to another (or neutral)

## Questions to resolve

1. **Where do IVs/EVs live?** On `Pokemon` (they're fixed for a specific Pokemon, don't change in battle).

2. **Nature representation** — enum with stat modifiers? There are 25 natures (5 neutral). Options:
   - Full `Nature` enum with `boosted: StatType?` and `penalized: StatType?`
   - Preference: enum with companion function `modifier(nature, stat): Double` returning 0.9, 1.0, or 1.1

3. **Default values** — what should the defaults be for Pokemon created without explicit IVs/EVs?
   - 31 IVs across the board (perfect, common in competitive)
   - 0 EVs (no training)
   - Neutral nature
   - This way existing tests don't break, and the numbers shift to be higher (closer to the examples)

4. **EV validation** — enforce the 510 total cap? Or just per-stat 0-252?
   - Per-stat validation is easy. Total cap is a team-building concern, not a battle concern.
   - Preference: validate per-stat only. The battle engine trusts the Pokemon is legal.

## Plan

### Step 1: Extend Pokemon with IVs and EVs
- [ ] Add `ivs: StatBlock` and `evs: StatBlock` to `Pokemon` (default: 31 IVs, 0 EVs)
- [ ] `StatBlock` data class: hp, attack, defense, specialAttack, specialDefense, speed
- [ ] Per-stat validation: IVs 0-31, EVs 0-252

### Step 2: Add Nature
- [ ] `Nature` enum with all 25 natures
- [ ] `Nature.modifier(stat: StatType): Double` returning 0.9, 1.0, or 1.1
- [ ] Add `nature: Nature` to `Pokemon` (default: a neutral nature, e.g., HARDY)

### Step 3: Update stat formulas
- [ ] `calcMaxHp(base, level, iv, ev)` with the full formula
- [ ] `calcStat(base, level, iv, ev, nature, statType)` with the full formula
- [ ] Update `PokemonState.maxHp` and `PokemonState.effectiveSpeed()` to pass through IVs/EVs/Nature
- [ ] Update `DamageCalc` to use the full formula

### Step 4: Update worked examples
- [ ] Specify the IV/EV/Nature spreads that produce the stats in `example-simple.md` and `example-extended.md`
- [ ] Verify the damage numbers now match (or update them)
- [ ] Update existing tests to use explicit IVs/EVs that produce expected values

### Step 5: Test
- [ ] Test: Pokemon with 31 IVs, 252 EVs, boosting nature has higher stats than base
- [ ] Test: nature modifier applies correctly (Adamant boosts Attack, lowers SpAtk)
- [ ] Test: EV investment changes damage output measurably
- [ ] Re-run all existing tests to verify nothing breaks

## Validation

| Step | Validation |
|------|-----------|
| 1-2 | `./gradlew compileKotlin` — new types compile |
| 3 | `./gradlew compileKotlin` — updated formulas compile |
| 4-5 | `./gradlew test` — all tests pass with updated numbers |

## Open design questions

- **Stat calculation site:** Right now `calcStat` is a free function in `Stats.kt`. With IVs/EVs/Nature it needs more inputs. Should it become a method on `Pokemon`? e.g., `pokemon.calcStat(StatType.ATTACK)`. This would centralize the "what are this Pokemon's actual stats" question.
- **Backwards compatibility:** Existing tests create Pokemon without IVs/EVs. Default to 31/0/neutral so they still work, but the damage numbers will change (higher stats = higher damage). Tests that assert on specific damage values will need updating.

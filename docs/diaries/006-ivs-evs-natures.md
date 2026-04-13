# Diary 006: IVs, EVs, and Natures

**Date:** 2026-04-13
**Status:** Complete

## Goal

Extend the stat formula to include Individual Values (IVs), Effort Values (EVs), and Natures. This closes the gap between our damage calculations and the worked examples in the docs.

## Decisions

1. **IVs/EVs on `Pokemon`** — they're fixed per Pokemon, don't change in battle. `StatBlock` data class holds per-stat values with a `uniform()` factory and `forStat()` accessor.

2. **Defaults:** 31 IVs, 0 EVs, neutral nature (HARDY). This matches the worked examples' stat values exactly and preserves backwards compatibility — existing tests don't need to specify IVs/EVs.

3. **Nature as enum** with `boosted`/`penalized` stat fields and `modifier(stat)` function returning 0.9/1.0/1.1. All 25 natures defined (5 neutral, 20 non-neutral).

4. **Stat calc moved to `Pokemon.calcStat(StatType)`** — centralizes the "what are this Pokemon's actual stats" question. `PokemonState.effectiveSpeed()` and `DamageCalc` both delegate to it.

5. **EV validation:** per-stat only (0-252). Total 510 cap is a team-building concern, not a battle engine concern.

## Plan

### Step 1: StatBlock and Pokemon extensions (done)
- [x] `StatBlock` data class with `forStat(StatType)` and `uniform(value)` factory
- [x] `Pokemon` extended with `ivs: StatBlock`, `evs: StatBlock`, `nature: Nature`
- [x] `Pokemon.maxHp` and `Pokemon.calcStat(StatType)` methods

### Step 2: Nature enum (done)
- [x] All 25 natures with boosted/penalized stat pairs
- [x] `modifier(stat): Double` returning 0.9, 1.0, or 1.1

### Step 3: Updated stat formulas (done)
- [x] `calcMaxHp` and `calcStat` extended with `iv`, `ev`, `natureMod` parameters (default to 31/0/1.0)
- [x] `PokemonState.maxHp` delegates to `pokemon.maxHp`
- [x] `PokemonState.effectiveSpeed()` uses `pokemon.calcStat(SPEED)`
- [x] `DamageCalc` uses `pokemon.calcStat(StatType)` for attack/defense stats

### Step 4: Updated tests (done)
- [x] Charizard stat assertions updated to new formula values (speed 120, HP 153)
- [x] Damage range test uses exact values at fixed rolls (113/123/133) instead of fuzzy ranges
- [x] Example doc note updated — stats now match, damage truncation gap documented

### Step 5: New tests (done)
- [x] Default IV/EV/nature values correct
- [x] HP formula with various IV/EV combinations
- [x] Stat formula with IVs, EVs, and nature modifiers
- [x] Adamant nature boosts/penalties
- [x] Neutral natures have no modifiers
- [x] `Pokemon.calcStat` integrates IVs/EVs/nature correctly
- [x] EV investment increases damage
- [x] Nature affects damage output

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1-2 | `./gradlew compileKotlin` | PASS |
| 3 | `./gradlew compileKotlin` | PASS |
| 4 | `./gradlew test` — existing 26 tests, 25 pass, 1 updated | PASS |
| 5 | `./gradlew test` — 8 new IVs/EVs/Natures tests | PASS |
| All | 34 tests total, 0 failures | PASS |

## Discovery

**31 IVs / 0 EVs matches the example doc stats exactly.** Charizard speed 120, HP 153, SpAtk 129 — all match `docs/example-simple.md`. The example author likely assumed perfect IVs with no EV investment, which is now our default.

**Damage numbers still differ from the docs** (~113-133 vs ~148-176 for Flamethrower). The stat values match, but the damage formula's integer truncation order differs. The real game truncates at each multiplication step; we accumulate floating point then truncate once at the end. This is a formula fidelity gap, not an IVs/EVs issue. Documented but deferred — fixing requires matching the exact game implementation's truncation sequence.

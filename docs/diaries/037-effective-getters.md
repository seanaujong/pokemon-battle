# Diary 037: Effective-Getter Expansion

**Date:** 2026-04-14
**Status:** Complete

## Goal

Finish the last item on diary 030's "do now" list: extend `PokemonState`'s `effective*`
getter pattern from types to ability (and document the path for stats / moveset when
they're first needed).

## Rationale

Per diary 030, *stats/types/ability/moveset must always be derived, never cached at
switch-in*. Every mid-battle transformation — Mega, Tera, Dynamax, form changes, Gastro
Acid, Neutralizing Gas, Trace, Role Play, Simple Beam — needs the read site to look up
"what's the holder's effective X right now?" not "what was it when they switched in?"

`PokemonState` already does this for types:

```kotlin
val typeOverride: List<Type>? = null
val effectiveTypes: List<Type> get() = typeOverride ?: pokemon.species.types
```

Set by future `TypeChanged` events (Terastal, Soak, Forest's Curse). The getter is the
read seam.

**Today** we only need the seam for `ability`. No mechanic suppresses abilities yet, but
Mega Evolution (diary 030 twist #8), Gastro Acid, Neutralizing Gas, and ability-swap
moves all demand it. Shipping the seam means those additions are one-line events, not
refactors.

## Scope

**In:**
- `PokemonState.abilityOverride: Ability? = null` field
- `PokemonState.effectiveAbility: Ability? = abilityOverride ?: ability` getter
- Migrate all `AbilityRegistry.effectFor(pokemon.ability)` call sites to use
  `effectiveAbility`

**Out (deferred):**
- `effectiveBaseStats` — requires restructuring `Pokemon.calcStat()` to accept an override
  map or adding `statOverrides: Map<StatType, Int>`. Needs a concrete consumer (Mega /
  Dynamax) to justify the design. Document the plan without building.
- `effectiveMoveset` — we don't track moveset on `PokemonState`; moves come from
  `TurnChoice.UseMove`. Z-moves and Dynamax Max moves would need the seam, but they're
  in the "first real gimmick" diary, not this stub.

## Plan

### Step 1: Add the getter
- [x] `abilityOverride: Ability? = null` field on `PokemonState`
- [x] `effectiveAbility` computed property

### Step 2: Migrate call sites
- [x] `AbilityRegistry.effectFor(pokemon.ability)` → `AbilityRegistry.effectFor(pokemon.effectiveAbility)`
- [x] Any other `pokemon.ability` read that affects behavior (not just display)

### Step 3: Test
- [x] With `abilityOverride = Ability.KLUTZ`, holder's item is suppressed even if base
      ability is different
- [x] With `abilityOverride = null`, behavior matches base ability (regression check)

## Where stats live — documented plan, not built

When Mega Evolution or Dynamax demands stat-override, the shape will be:

```kotlin
// On PokemonState
val baseStatOverrides: Map<StatType, Int> = emptyMap()

// On Pokemon (or as a function):
fun effectiveCalcStat(state: PokemonState, stat: StatType): Int =
    state.baseStatOverrides[stat]?.let { calcStatWithBaseOverride(it, stat) }
        ?: calcStat(stat)
```

Migrate `DamageCalc` and `SpeedResolver` from `pokemon.calcStat(...)` to
`state.effectiveCalcStat(pokemon.pokemon, stat)`. Mega's stat swap becomes one event
emitting a map; base `calcStat` stays unchanged.

Not building today because no mechanic uses it yet — speculative plumbing would add
review surface without payoff.

## Success criteria

- Adding a future ability-suppressing mechanic (Neutralizing Gas, Gastro Acid) requires
  one event + one override write, no engine branching
- All 207 existing tests pass — this is additive
- 2 new tests demonstrate the override seam works

## Related

- **Diary 030** — meta-lesson 4 ("stats/types/ability/moveset always derived")
- **Diary 027** — ability registry (the callers being migrated)
- **Diary 036** — gimmick stub (sibling work from diary 030's do-now list)

# Diary 027: Ability Registry

**Date:** 2026-04-13
**Status:** Complete

## Goal

Extract ability behavior from `SwitchInAbility.kt`, `MoveExecutionPhase.abilityBlockingMove`,
and `isWeatherImmune` into an `AbilityEffect` interface + `AbilityRegistry`, mirroring the
item registry from diary 026.

This is the second registry extraction, applying the same pattern that worked for items.

## Why ability is the next registry

Same shape as items, stronger signal:
- Already 10 abilities, diary 023 adds 3+ (Sturdy, Emergency Exit, Red Card)
- Real Pokemon has ~300 abilities; we'll keep growing
- Cross-gen: Sand Veil changed in Gen 6, Prankster got Dark immunity in Gen 7, new abilities every gen
- Hooks into **many** pipeline points: switch-in (Intimidate, Drizzle), move immunity
  (Levitate, Sap Sipper, Lightning Rod), weather immunity (Sand Veil set, Ice Body),
  status block (future), speed mods (future), damage mods (future)
- Currently scattered across 3 files; adding one ability means editing 2-3 callers

## `AbilityEffect` interface

```kotlin
interface AbilityEffect {
    val ability: Ability

    /** Fired on switch-in (Intimidate → drop opponent Atk; Drizzle → set rain). */
    fun onSwitchIn(state: BattleState, slot: Slot): List<BattleEvent> = emptyList()

    /** True if this ability blocks the incoming move type-wise (Levitate vs Ground, Flash Fire vs Fire). */
    fun blocksMove(defender: PokemonState, move: Move): Boolean = false

    /** Absorb-and-buff variants — events to emit when blocking (Sap Sipper, Lightning Rod). */
    fun onMoveAbsorbed(defender: PokemonState, slot: Slot, move: Move): List<BattleEvent> = emptyList()

    /** Grants immunity to weather damage (Sand Veil, Ice Body). */
    fun blocksWeatherDamage(weather: Weather): Boolean = false

    /** Attacker-side damage modifier (future: Sheer Force, Tough Claws). */
    fun attackerDamageModifier(attacker: PokemonState, move: Move): Double = 1.0

    /** Defender-side damage modifier (future: Thick Fat, Heatproof). */
    fun defenderDamageModifier(defender: PokemonState, move: Move): Double = 1.0

    /** Render a switch-in ability trigger. */
    fun renderTriggered(pokemonName: String): String = ""
}
```

Hooks mirror `ItemEffect` where applicable; ability-specific hooks (`onSwitchIn`,
`blocksWeatherDamage`, `blocksMove` + `onMoveAbsorbed`) cover ability shapes.

## Abilities to migrate

- `BLAZE`, `OVERGROW`, `TORRENT` — pinch-type-boost: `attackerDamageModifier` returns 1.5x
  for matching-type moves when user is at or below 1/3 max HP (activated in this diary)
- `SAND_VEIL`, `SAND_RUSH`, `SAND_FORCE`, `SNOW_CLOAK`, `ICE_BODY` — `blocksWeatherDamage`
- `INTIMIDATE` — `onSwitchIn` (drop opponent Atk by 1 stage)
- `DRIZZLE` — `onSwitchIn` (set rain for 5 turns)
- `LEVITATE` — `blocksMove` (Ground-type damaging moves)

## Plan

### Step 1: Create `engine/ability/` package
- [x] `AbilityEffect.kt` — interface
- [x] `AbilityRegistry.kt` — singleton map

### Step 2: Extract each existing ability
- [x] `IntimidateEffect.kt`, `DrizzleEffect.kt`, `LevitateEffect.kt`
- [x] `WeatherImmunityEffects.kt` — 5 weather-immune abilities
- [x] `PinchTypeBoostEffects.kt` — Blaze/Overgrow/Torrent (activate if dormant)

### Step 3: Update callers
- [x] `resolveSwitchInAbility` → generic registry lookup
- [x] `MoveExecutionPhase.abilityBlockingMove` → registry lookup
- [x] `isWeatherImmune` → registry lookup
- [x] TextRenderer → registry lookup for ability-triggered text

### Step 4: Validate
- [x] All 159 existing tests pass unchanged (behavior-preserving refactor)

## Success criteria — all met

- Every `when (ability)` or `ability in <set>` check in engine code is replaced with a
  registry lookup ✅
- Adding a new ability is one file + one registry entry ✅
- Text for ability triggers colocated with behavior, not scattered in TextRenderer ✅
- All existing tests pass ✅ (159 → 163 with 4 new pinch-boost tests)

## Decisions along the way

- **Kotlin `object X : AbilityEffect by Helper(...)`** for weather-immune abilities and
  pinch-type boosts. Single shared class, each singleton picks its (ability, weather-or-type)
  pair. Cleaner than 5 near-identical implementations.
- **Activated `BLAZE`/`OVERGROW`/`TORRENT`** instead of leaving dormant. They're one
  shared `PinchTypeBoost` class via delegation; implementing while we're here is trivial
  and removes the "dormant abilities" caveat. Net +4 tests.
- **Wired ability damage modifiers into `GenVDamageCalculator`** (previously the calc only
  consulted item modifiers). Added to the shared modifier product; hit detekt cyclomatic
  threshold, resolved with `@Suppress` + extracting `modifier` local.
- **`model/Ability.kt` now pure identity.** Dropped `isWeatherImmune` helper + the
  weather-immune sets; enum just lists valid ability values. Behavior all lives in the
  `engine/ability/` package where it belongs.

## What this unlocks

- **Diary 023 (competitive abilities)** becomes "add 3 files + 3 registry entries" for
  Sturdy, Emergency Exit, Red Card
- **Ability/item cross-registry interactions** become possible (Klutz nullifies items,
  Magic Room disables all items)
- **Gen-specific ability registries** are now trivial — the pattern is in place

## Related diaries

- **Diary 026** — Item registry refactor, the pattern we're applying here
- **Diary 028** — How to handle data-shape divergence (what registries *can't* fix)
- **Diary 029** — Move-behavior registry analysis (a future extraction)

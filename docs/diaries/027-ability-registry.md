# Diary 027: Ability Registry

**Date:** 2026-04-13
**Status:** Not started

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

- `BLAZE`, `OVERGROW`, `TORRENT` — pinch-type-boost (dormant; add `attackerDamageModifier`
  when HP ≤ 1/3 → 1.5x matching-type damage)
- `SAND_VEIL`, `SAND_RUSH`, `SAND_FORCE`, `SNOW_CLOAK`, `ICE_BODY` — `blocksWeatherDamage`
- `INTIMIDATE` — `onSwitchIn` (drop opponent Atk by 1 stage)
- `DRIZZLE` — `onSwitchIn` (set rain for 5 turns)
- `LEVITATE` — `blocksMove` (Ground-type damaging moves)

## Plan

### Step 1: Create `engine/ability/` package
- [ ] `AbilityEffect.kt` — interface
- [ ] `AbilityRegistry.kt` — singleton map

### Step 2: Extract each existing ability
- [ ] `IntimidateEffect.kt`, `DrizzleEffect.kt`, `LevitateEffect.kt`
- [ ] `WeatherImmunityEffects.kt` — 5 weather-immune abilities
- [ ] `PinchTypeBoostEffects.kt` — Blaze/Overgrow/Torrent (activate if dormant)

### Step 3: Update callers
- [ ] `resolveSwitchInAbility` → generic registry lookup
- [ ] `MoveExecutionPhase.abilityBlockingMove` → registry lookup
- [ ] `isWeatherImmune` → registry lookup
- [ ] TextRenderer → registry lookup for ability-triggered text

### Step 4: Validate
- [ ] All 159 existing tests pass unchanged (behavior-preserving refactor)

## Success criteria

- Every `when (ability)` or `ability in <set>` check in engine code is replaced with a
  registry lookup
- Adding a new ability is one file + one registry entry
- Text for ability triggers colocated with behavior, not scattered in TextRenderer
- All existing tests pass

## What this unlocks

- **Diary 023 (competitive abilities)** becomes "add 3 files + 3 registry entries"
  instead of editing 5 files and sprouting unreachable branches
- **Ability/item cross-registry interactions** become possible (Klutz nullifies items,
  Magic Room disables all items)

## Related diaries

- **Diary 026** — Item registry refactor, the pattern we're applying here
- **Diary 028** — How to handle data-shape divergence (what registries *can't* fix)
- **Diary 029** — Move-behavior registry analysis (a future extraction)

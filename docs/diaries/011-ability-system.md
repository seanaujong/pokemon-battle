# Diary 011: Ability System

**Date:** 2026-04-13
**Status:** Not started

## Goal

Implement abilities that trigger during battle phases. Three abilities to test different integration points: Intimidate (switch-in trigger), Levitate (per-target immunity), Drizzle (switch-in field effect).

## Trigger patterns

| Ability | When | What | Integration point |
|---------|------|------|-------------------|
| Intimidate | Switch-in | Lower all opponents' Attack by 1 | After `SwitchIn` events in `SwitchPhase` |
| Levitate | Before damage | Immune to Ground moves | Target resolution in `MoveExecutionPhase` |
| Drizzle | Switch-in | Set weather to Rain | After `SwitchIn` events in `SwitchPhase` |

## Design

### Approach: direct dispatch in phases (option A)

Phases check `pokemon.ability` directly with `when` branches. Same pattern as item effects in `EndOfTurnPhase`. Simpler than a generic ability effect system.

If the ability list grows large, refactor to an effect system (option B). For now, three abilities don't justify the abstraction.

### Switch-in abilities (Intimidate, Drizzle)

After `SwitchPhase` emits `SwitchIn`, a new post-switch step checks the incoming Pokemon's ability and emits additional events. Options:

A. **Extend `SwitchPhase`** to emit ability events after each `SwitchIn`.
B. **New `AbilityPhase`** that runs after `SwitchPhase`, checks for recent switch-ins.

Option A is simpler — `SwitchPhase` already processes switches sequentially with intermediate state. After each `SwitchIn`, check the incoming Pokemon's ability and emit events.

But this couples switch-in ability logic to `SwitchPhase`. Option B keeps abilities separate.

**Decision:** Option A for now. `SwitchPhase` emits ability events as a post-switch-in step. If ability triggers grow beyond switch-in (start-of-battle, end-of-turn abilities), we extract an `AbilityPhase`.

### Levitate (per-target immunity)

This happens during target resolution in `MoveExecutionPhase`. When resolving damage targets, check if the defender has an ability that grants immunity to the move's type.

This is the first per-target check beyond type effectiveness. It goes in the damage loop, before `calculateDamage`:

```
for targetSlot in targets:
    check ability immunity → skip if immune
    calculate damage
    check faint
```

Need a new informational event: `AbilityBlocked(slot, ability, move)` — "Gengar's Levitate made it immune!" for the log/renderer.

### New events

- `AbilityTriggered(slot, ability)` — informational, "Intimidate activates!"
- `AbilityBlocked(slot, ability)` — informational, "Levitate blocked the attack!"
- `WeatherSet(weather, turnsRemaining)` — for Drizzle setting rain (also useful for weather-setting moves later)

`StatChanged` already exists for Intimidate's attack drop.

## Plan

### Step 1: New events
- [ ] `AbilityTriggered(slot, ability)` — informational
- [ ] `AbilityBlocked(slot, ability)` — informational
- [ ] `WeatherSet(weather, turnsRemaining)` with `apply()` that sets field weather
- [ ] Compile check

### Step 2: Levitate in MoveExecutionPhase
- [ ] Before damage calc per target: check for ability immunity
- [ ] For now: Levitate blocks Ground-type moves
- [ ] Emit `AbilityBlocked` and skip damage for that target
- [ ] Test: Ground move vs Levitate Pokemon deals no damage
- [ ] Test: Non-Ground move vs Levitate Pokemon deals normal damage
- [ ] Test: Spread move with one Levitate target — other targets still take damage

### Step 3: Intimidate in SwitchPhase
- [ ] After `SwitchIn` is applied: check incoming Pokemon's ability
- [ ] If Intimidate: emit `AbilityTriggered` + `StatChanged(ATTACK, -1)` for each opponent slot
- [ ] Test: Pokemon with Intimidate switches in, opponents' attack drops
- [ ] Test: In doubles, Intimidate lowers both opponents' attack
- [ ] Test: Voluntary switch brings in Intimidate Pokemon, affects opponent before moves

### Step 4: Drizzle in SwitchPhase
- [ ] After `SwitchIn` is applied: check incoming Pokemon's ability
- [ ] If Drizzle: emit `AbilityTriggered` + `WeatherSet(RAIN, 5)`
- [ ] Test: Drizzle Pokemon switches in, weather becomes Rain
- [ ] Test: Drizzle overwrites existing weather

### Step 5: Existing tests still pass
- [ ] All 58 tests pass unchanged (no Pokemon has abilities set by default)

## Validation

| Step | Validation |
|------|-----------|
| 1 | `./gradlew compileKotlin` |
| 2 | `./gradlew test` — Levitate tests pass |
| 3 | `./gradlew test` — Intimidate tests pass |
| 4 | `./gradlew test` — Drizzle tests pass |
| 5 | `./gradlew test` — all 58 existing tests pass |

## Future accommodation

| Ability | How it fits |
|---------|-------------|
| Swift Swim (2x speed in rain) | `effectiveSpeed()` checks weather — same gen-specific leak pattern as paralysis |
| Sand Stream (like Drizzle but sandstorm) | Same pattern as Drizzle, different weather |
| Flash Fire (immune to Fire, boosts own Fire moves) | `AbilityBlocked` + a volatile flag that the damage calc reads |
| Mold Breaker (ignore target's ability) | A flag on the attacker checked during ability immunity resolution |
| Trace (copy opponent's ability) | `AbilityTriggered` + modify own ability on switch-in |

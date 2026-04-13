# Diary 005: Stat-Changing Moves

**Date:** 2026-04-12
**Status:** Not started

## Goal

Implement stat-changing moves (Swords Dance, Nasty Plot, Growl, etc.) and the `StatChanged` event. This validates that stat stages flow correctly through the damage calc and speed ordering.

## Questions to resolve

1. **Event design** — one event per stat, or one event that names the stat?
   - `StatChanged(target, stat: StatType, stages: Int)` where `StatType` is an enum (ATTACK, DEFENSE, etc.)
   - Preference: single event with a stat identifier. Avoids bloating the sealed hierarchy.

2. **Where do stat changes happen?** — could be:
   - A move's secondary effect (Flamethrower has 10% burn, some moves lower defense)
   - The move's primary effect (Swords Dance *is* a stat change)
   - An ability trigger (Intimidate)
   - For now: just primary-effect stat moves. Secondary effects and abilities come later.

3. **Move categorization** — Swords Dance has no target (self-boost) and no damage. Our `Move` type has `power: Int` and `category: MoveCategory`. A stat move has no power and no physical/special category. Options:
   - Add `MoveCategory.STATUS`
   - Make `power` nullable
   - Preference: add `STATUS` category and make power 0 for status moves

4. **Stage clamping** — `StatStages` already enforces -6..6 via `require()`. `StatChanged.apply()` needs to clamp before constructing the new `StatStages`, not let the `require` throw.

## Plan

### Step 1: StatType enum and StatChanged event
- [ ] `StatType` enum: ATTACK, DEFENSE, SPECIAL_ATTACK, SPECIAL_DEFENSE, SPEED
- [ ] `StatChanged(target, stat, stages)` event with `apply()` that updates the relevant stat stage, clamping to -6..6
- [ ] Test: applying StatChanged correctly modifies BattleState

### Step 2: Status move category
- [ ] Add `MoveCategory.STATUS`
- [ ] `MoveExecutionPhase` handles status moves: emit `StatChanged` instead of damage
- [ ] Need to define how: move effect system? Or special-case for now?
- [ ] Simplest: a `MoveEffect` sealed interface on `Move` that describes what the move does

### Step 3: Swords Dance test
- [ ] Define Swords Dance: STATUS category, power 0, effect = raise user's attack by 2 stages
- [ ] Test: use Swords Dance, then attack. Assert the stat stage is +2, damage is boosted.
- [ ] Verify stat stage multiplier: +2 = 2.0x attack

### Step 4: Stat-lowering test
- [ ] Define Growl: STATUS category, lowers target's attack by 1 stage
- [ ] Test: use Growl, then opponent attacks. Assert attack is -1, damage is reduced.
- [ ] Test stage clamping: applying -1 six times maxes out at -6, seventh is a no-op

## Validation

| Step | Validation |
|------|-----------|
| 1 | `./gradlew compileKotlin` — event compiles |
| 2 | `./gradlew compileKotlin` — status moves work in phase |
| 3 | `./gradlew test` — Swords Dance boosts damage correctly |
| 4 | `./gradlew test` — Growl reduces damage, clamping works |

## Open design questions

- **Move effects system:** Right now moves are just data (name, type, power, priority). Stat moves need to express *what they do* beyond damage. A `MoveEffect` type is the minimal addition. But this is the beginning of a larger system (secondary effects, multi-hit, recoil, etc.). How much to build now vs later?
- **Self-targeting vs opponent-targeting:** Swords Dance targets self, Growl targets opponent. The move needs to express this. For singles it's simple (self or opponent). For doubles this becomes target selection — another reason to defer the doubles refactor until after we have richer move semantics.

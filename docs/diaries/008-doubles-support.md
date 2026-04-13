# Diary 008: Doubles Support

**Date:** 2026-04-13
**Status:** Complete

## Goal

Make the engine handle doubles battles — 4 slots, 2 per side, with target selection, spread moves, and multi-target damage. This is the real test of the slot-based architecture from diary 007.

## What doubles changes

| Mechanic | Singles | Doubles |
|----------|---------|---------|
| Slots | 2 (one per side) | 4 (two per side) |
| Target selection | Implicit (the opponent) | Player picks for single-target moves |
| Spread moves | N/A | Hit multiple targets at 0.75x each |
| Move ordering | 2 actions | 4 actions sorted by priority/speed |
| End-of-turn | Iterate 2 slots | Iterate 4 slots |
| MoveTarget | SELF, OPPONENT | SELF, ONE_OPPONENT, ALL_OPPONENTS, ALL_OTHER |

## Decisions

1. **Target selection in TurnChoice.** For single-target moves in doubles, the player must specify which opponent to hit. `TurnChoice.UseMove` gets an optional `targetSlot: Slot?`. Null means "use the move's default targeting" (self-target moves, spread moves). Non-null means "hit this specific slot."

2. **MoveTarget expansion.** New targeting patterns:
   - `ONE_OPPONENT` — player selects one opposing slot (Flamethrower in doubles)
   - `ALL_OPPONENTS` — hits all opposing slots (Hyper Voice)
   - `ALL_OTHER` — hits all other slots including allies (Earthquake)
   - `SELF` stays as-is
   - Rename current `OPPONENT` to `ONE_OPPONENT` for clarity

3. **Spread modifier.** When a move hits 2+ targets, each hit gets a 0.75x damage modifier. Applied in `calculateDamage` as a new parameter, or in the phase before calling the calc. Preference: pass it to `calculateDamage` as a `spreadModifier: Double = 1.0` parameter — keeps the formula transparent.

4. **Intermediate state in executeMove.** When a spread move hits two opponents, the first hit's faint should be applied before calculating the second hit (a fainted Pokemon shouldn't take damage). Refactor `executeMove` to track `currentState` across targets.

5. **Factory method.** `BattleState.doubles(p1Left, p1Right, p2Left, p2Right)` for test setup.

## Plan

### Step 1: MoveTarget expansion
- [ ] Rename `OPPONENT` → `ONE_OPPONENT`
- [ ] Add `ALL_OPPONENTS`, `ALL_OTHER`
- [ ] Update existing moves to use `ONE_OPPONENT` (all current moves are single-target)
- [ ] Compile check — update all `when` branches on `MoveTarget`

### Step 2: Target selection in TurnChoice
- [ ] Add `targetSlot: Slot? = null` to `TurnChoice.UseMove`
- [ ] `resolveTargets` uses `targetSlot` for `ONE_OPPONENT` in multi-slot formats, falls back to first opponent slot if null (backwards-compatible for singles)
- [ ] Compile check

### Step 3: Spread modifier in damage calc
- [ ] Add `spreadModifier: Double = 1.0` to `calculateDamage`
- [ ] `MoveExecutionPhase` passes 0.75 when a move hits 2+ targets
- [ ] Compile check

### Step 4: Intermediate state tracking in executeMove
- [ ] Refactor `executeMove` to maintain `currentState` across per-target damage
- [ ] Fainted targets are skipped for subsequent hits
- [ ] State after damage is used for faint checks and effect resolution

### Step 5: resolveTargets for new MoveTarget values
- [ ] `ALL_OPPONENTS` → all opposing slots
- [ ] `ALL_OTHER` → all slots except attacker (including allies)
- [ ] `ONE_OPPONENT` → `targetSlot` from choice, or first opponent if unspecified
- [ ] `SELF` → empty (no damage targets), effects target self

### Step 6: Factory methods and helpers
- [ ] `BattleState.doubles(p1Left, p1Right, p2Left, p2Right)`
- [ ] `TurnChoices` from a map (already works — just build the map with 4 entries)

### Step 7: Singles regression
- [ ] All 34 existing tests pass unchanged
- [ ] `ONE_OPPONENT` in singles still works with implicit targeting

### Step 8: Doubles tests
- [ ] **Basic doubles turn:** 4 Pokemon, 4 moves, correct ordering by speed
- [ ] **Spread move (Earthquake ALL_OTHER):** Hits both opponents + ally at 0.75x, ally can be immune (Levitate)
- [ ] **Single-target in doubles:** Player targets specific opponent slot
- [ ] **ALL_OPPONENTS move:** Hits both opponents but not ally
- [ ] **Faint during spread:** First target faints, second target still takes damage, fainted target skipped
- [ ] **End-of-turn with 4 slots:** Weather/status/items tick for all 4 Pokemon
- [ ] **Priority in doubles:** Mach Punch goes before all non-priority moves regardless of speed

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1-6 | `./gradlew compileKotlin` after each step | PASS |
| 7 | `./gradlew test` — all 34 existing tests pass | PASS |
| 8 | `./gradlew test` — 9 new doubles tests pass | PASS |
| All | 43 tests total, 0 failures | PASS |

## Worked example: Earthquake in doubles

```
SIDE_1: Swampert (pos 0), Orthworm (pos 1)
SIDE_2: Infernape (pos 0), Gengar (pos 1, Levitate)

Swampert uses Earthquake (ALL_OTHER):
  → Target: Orthworm (ally, pos 1) — Ground type, immune? No, but Earth Eater... 
     (ability effects deferred, just type effectiveness for now)
  → Target: Infernape (opponent, pos 0) — Fire/Fighting, Ground is super-effective (2x)
     → DamageDealt at 0.75x spread modifier
  → Target: Gengar (opponent, pos 1) — Ghost/Poison, Levitate (ability, deferred)
     → For now: type effectiveness only. Ground vs Ghost/Poison = neutral on Ghost, 
       neutral on Poison = 1x (Levitate would block but we don't have ability 
       blocking yet)
```

This example shows what works now (multi-target damage, spread modifier, type effectiveness) and what's deferred (ability-based immunity like Levitate/Earth Eater). The architecture supports adding ability checks in `resolveTargets` or as a pre-damage step later.

# Diary 007: Slot-Based Architecture — Format-Agnostic Battle State

**Date:** 2026-04-13
**Status:** Complete

## Goal

Replace the singles-specific `Player`-based addressing with a `Slot`-based model that supports singles, doubles, triples, co-op (Eterna Forest style), and any other format without code changes.

## Three concepts

The current `Player` enum conflates three things:

1. **Side** — which team (us vs them). A battle always has exactly 2 sides.
2. **Slot** — a position on the field. Singles has 2, doubles has 4, triples has 6.
3. **Controller** — who makes decisions (human, AI, remote player). Irrelevant to the engine.

The engine only needs Side + Slot. Controller is a layer above — the game loop that collects `TurnChoice`s. Cheryl's AI and your input both produce choices keyed by `Slot`. The pipeline doesn't know the difference.

## Design

```kotlin
enum class Side { SIDE_1, SIDE_2 }

data class Slot(val side: Side, val position: Int = 0)
```

- **Singles:** `Slot(SIDE_1, 0)` vs `Slot(SIDE_2, 0)`
- **Doubles:** `Slot(SIDE_1, 0)`, `Slot(SIDE_1, 1)` vs `Slot(SIDE_2, 0)`, `Slot(SIDE_2, 1)`
- **Co-op (Eterna Forest):** Same as doubles — 4 slots, 2 per side. Controller assignment is external to the engine.

### BattleState

```kotlin
data class BattleState(
    val slots: Map<Slot, PokemonState>,
    val field: FieldState = FieldState(),
    val turn: Int = 1
)
```

Helpers: `pokemonFor(slot)`, `withPokemon(slot, state)`, `slotsForSide(side)`.

### TurnChoices

```kotlin
data class TurnChoices(val choices: Map<Slot, TurnChoice>)
```

Helper: `choiceFor(slot)`.

### Events

All events change `Player` → `Slot`:
- `DamageDealt(target: Slot, ...)`
- `MoveAttempted(attacker: Slot, ...)`
- `StatChanged(target: Slot, ...)`
- etc.

### Move order

`resolveMoveOrder` returns `List<Slot>` sorted by priority then speed. Works for any number of slots — singles returns 2, doubles returns 4.

### Phases

Phases iterate `state.slots` or the ordered slot list instead of hardcoding P1/P2. No format-specific logic needed.

### DamageCalc

Stays unchanged — it takes one attacker and one defender `PokemonState`. The calling code resolves which slots are involved.

## Plan

### Step 1: Side and Slot types
- [ ] `Side` enum: `SIDE_1`, `SIDE_2`
- [ ] `Slot` data class: `side: Side`, `position: Int`
- [ ] Companion convenience: `Slot.p1()`, `Slot.p2()` for singles shorthand
- [ ] Compile check

### Step 2: BattleState migration
- [ ] Replace `pokemon1`/`pokemon2` with `slots: Map<Slot, PokemonState>`
- [ ] Add `pokemonFor(slot)`, `withPokemon(slot, state)`, `slotsForSide(side)`
- [ ] Factory: `BattleState.singles(p1, p2, field, turn)` for easy construction
- [ ] Compile check (everything breaks here — fix cascading)

### Step 3: TurnChoices migration
- [ ] Replace `p1`/`p2` with `choices: Map<Slot, TurnChoice>`
- [ ] Add `choiceFor(slot)`
- [ ] Factory: `TurnChoices.singles(p1Choice, p2Choice)`
- [ ] Compile check

### Step 4: Events migration
- [ ] Replace all `Player` references with `Slot` in events
- [ ] Update all `apply()` methods to use `Slot`
- [ ] Remove `Player` type (replaced by `Side`)
- [ ] Compile check

### Step 5: Move order migration
- [ ] `resolveMoveOrder` returns `List<Slot>` sorted by priority/speed
- [ ] Works for any number of slots
- [ ] `MoveOrderDecided` reports the full ordering
- [ ] Compile check

### Step 6: Phase migration
- [ ] `MoveOrderPhase` uses new order format
- [ ] `MoveExecutionPhase` iterates slot order, status checks use `Slot`
- [ ] `EndOfTurnPhase` iterates `state.slots` instead of `Player.entries`
- [ ] Compile check

### Step 7: Test migration
- [ ] Update all tests to use `Slot`/`Side` and factory methods
- [ ] Verify all 34 tests pass
- [ ] Add a doubles smoke test: 4-slot state, 4 choices, correct ordering

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | `./gradlew compileKotlin` | PASS |
| 2-6 | `./gradlew compileKotlin` after cascading fixes | PASS |
| 7 | `./gradlew test` — all 34 tests pass | PASS |
| - | `grep -rn Player src/` returns empty | PASS |

## Target resolution design (not implemented yet, but must not foreclose)

In doubles, one move can produce different outcomes per target slot. Example:

> Swampert uses Earthquake (ALL_OTHER). Orthworm (ally, Earth Eater) heals.
> Side 2 Slot 0 takes damage (0.75x spread). Side 2 Slot 1 (Levitate) is immune.

This means:

1. **`MoveTarget` becomes a targeting pattern**, not a single target:
   ```
   SELF, ONE_OPPONENT, ALL_OPPONENTS, ALL_OTHER, ONE_ADJACENT, ALL_ALLIES
   ```

2. **The phase expands the pattern into concrete slots** based on the field layout. "ALL_OTHER" = every occupied slot except the user. "ONE_OPPONENT" = player selects in doubles, implicit in singles.

3. **Each target is resolved independently**: ability checks (Earth Eater, Levitate), type immunity, then damage calc per target.

4. **Spread modifier (0.75x)** applies when a move hits multiple targets. The phase knows this from the targeting pattern + how many targets were actually hit.

5. **DamageCalc stays single-target.** The phase calls it once per target, passing the spread modifier as an additional parameter.

### Per-target resolution is a mini pipeline

Each target goes through checks before damage:

1. **Protect** — `Volatile.Protect` on the target blocks the move for that slot only. Already defined in our Volatile sealed interface.
2. **Ability immunity** — Levitate blocks Ground moves, Earth Eater absorbs them. Per-target, per-ability.
3. **Type immunity** — Normal vs Ghost = 0x. Already handled by type chart.
4. **Damage calc** — with spread modifier if multiple targets.
5. **Secondary effects** — per-target (chance to burn, etc.)

### Substitute — damage redirection via Volatile

Substitute creates a decoy with 25% of the user's max HP. Attacks hit the Substitute
instead of the Pokemon. Modeled as `Volatile.Substitute(hp: Int)`.

Per-target resolution adds a step between immunity checks and damage:

1. If target has `Volatile.Substitute`: emit `SubstituteDamage`, decrement/remove volatile
2. If no substitute: emit `DamageDealt` to Pokemon HP

Substitute doesn't bleed through — if the hit exceeds the Substitute's HP, the
Substitute breaks and excess damage is discarded.

`SubstituteDamage` is a separate event from `DamageDealt` so the log and renderer can
distinguish "the substitute took the hit" from "the Pokemon took damage." The Substitute
HP is tracked via `VolatileChanged` (decrement) or volatile removal (break).

### Move effects have ordering

Some effects happen before damage resolution, some after:

- **Pre-damage:** `SelfDestruct` (Explosion — user faints before targets take damage)
- **Damage:** standard calc per target
- **Post-damage:** `Recoil` (user takes fraction of damage dealt), `Drain` (user heals)
- **Secondary:** `StatusInflict` (10% burn from Flamethrower), per-target

This suggests `MoveEffect` may eventually need a phase/timing tag. For now, effects run after damage. The architecture supports reordering later without structural changes.

### What we build now vs later

**Now:** `MoveTarget` keeps its current values (`SELF`, `OPPONENT`). In singles, `OPPONENT` unambiguously resolves to the one opposing slot. The phase resolves targets explicitly via `BattleState` helpers rather than `Player.opponent()`.

**Later (doubles):** Expand `MoveTarget` with multi-target patterns. Add target resolution logic to `MoveExecutionPhase` that expands patterns into slot lists. Add spread modifier to damage calc. Add per-target ability checks.

**The slot-based architecture enables this** without further refactoring — the phase already iterates slots and events target slots. The only new code is the pattern expansion and per-target resolution.

## Risks

- **Large mechanical change.** Every event, phase, and test file changes. But the changes are uniform: `Player` → `Slot`, named fields → map lookups.
- **`opponent()` disappears.** `Player.opponent()` assumed exactly 2 players. With slots, "the opponent" depends on context — in singles it's the other slot, in doubles it could be multiple slots. Phases need to resolve targets explicitly. For now, `BattleState.opponentSlots(slot)` returns all opposing slots, and singles tests use `Slot.p1()`/`Slot.p2()` convenience accessors.

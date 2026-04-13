# Diary 015: AI Opponent

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement `ChoiceProvider` implementations that select moves intelligently enough to produce interesting battles. This validates the `ChoiceProvider` interface and makes the demo/CLI usable without scripted choices.

## Design

### Three tiers of AI

1. **RandomAI** — picks a random move for each slot. Baseline for testing. Always has a valid choice.

2. **TypeAI** — picks the move that deals the most effective damage based on type matchups. Doesn't simulate actual damage — just checks `typeEffectiveness` and picks the highest multiplier. Ties broken by power. This is how a player who knows the type chart but doesn't do math would play.

3. **DamageAI** (stretch) — actually calculates damage for each move against each target and picks the highest. Uses `DamageCalculator` to simulate. This is a proper competitive AI.

For this diary: RandomAI and TypeAI. DamageAI is a natural follow-up but needs more design thought (target selection in doubles, switching vs attacking decisions).

### What the AI needs to know

The `ChoiceProvider` receives `BattleState`. From that it can:
- See all active Pokemon, their types, stats, HP, status
- See the bench (for switching decisions)
- But NOT see the opponent's moves — only their Pokemon on the field

The AI also needs to know what moves each Pokemon has. Currently `PokemonState` doesn't carry a move pool — moves are specified at choice time. In the real games, each Pokemon knows 4 moves.

**Design question:** Where does the move pool live?

The engine never asks "what moves does this Pokemon know?" — it only sees the
chosen move via `TurnChoice.UseMove`. The move pool is an input constraint for
the *choice layer*, not battle state.

Putting `moves` on `Pokemon` would make it a god object — species + level +
IVs/EVs/nature AND available moves AND eventually held item choices, ability
options, etc. Pokemon should stay as battle identity: who this is, not what
decisions are available.

**Decision:** The AI owns the move pool mapping. Each AI takes
`movePools: Map<Slot, List<Move>>` at construction. The engine doesn't carry
data it never reads.

### FaintReplacementProvider

The AI also needs to provide faint replacements. Simple strategy: send in the Pokemon with the best type matchup against the opponent's active Pokemon.

### Where it lives

New package `com.pokemon.battle.ai`. It depends on `engine` (for state, choices, damage calc) and `model` (for types, moves).

## Plan

### Step 1: RandomAI
- [ ] `ChoiceProvider` that picks a random move from the Pokemon's move pool
- [ ] If no moves, skip (shouldn't happen in a real battle)
- [ ] Also implements `FaintReplacementProvider` — picks first available
- [ ] Test: RandomAI produces valid choices for all slots

### Step 2: TypeAI
- [ ] For each slot, evaluate each move against each opponent
- [ ] Score = type effectiveness * STAB bonus * power
- [ ] Pick the move with the highest score
- [ ] For doubles: pick the best (move, target) combination
- [ ] Test: TypeAI picks super-effective moves over neutral ones

### Step 3: Integration — AI vs AI battle
- [ ] Set up a battle with Pokedex Pokemon and move pools
- [ ] Run TypeAI vs RandomAI
- [ ] Render the output
- [ ] Verify it produces a readable, interesting battle

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | `./gradlew test` — RandomAI tests (2) | PASS |
| 2 | `./gradlew test` — TypeAI tests (4) | PASS |
| 3 | `./gradlew test` — AI vs AI integration (1) | PASS |
| 3 | `./gradlew run` — TypeAI vs TypeAI 3v3 renders | PASS |
| All | 88 tests total, 0 failures | PASS |

## Key design decision

**Move pools are owned by the AI, not by Pokemon.** The engine never asks "what moves
does this Pokemon know" — it only sees the chosen move. Keeping move pools on the AI
prevents Pokemon from becoming a god object and keeps the model focused on identity
(species + level + IVs/EVs/nature).

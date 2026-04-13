# Diary 003: Cleanup — Package Organization and Format Readiness

**Date:** 2026-04-12
**Status:** Complete

## Goal

Reorganize the flat `com.pokemon.battle` package into subpackages, and reduce coupling to singles-specific assumptions so the architecture stays open to doubles/triples/inverse battles.

## Package reorganization (done)

22 files split into three subpackages:

| Package | Files | Rationale |
|---------|-------|-----------|
| `com.pokemon.battle.model` (12) | `Ability`, `FieldState`, `Item`, `Move`, `Player`, `Pokemon`, `PokemonState`, `Species`, `Stats`, `StatusCondition`, `Type`, `Volatile` | Pure data — no logic beyond derived properties |
| `com.pokemon.battle.engine` (7) | `BattleEvent`, `BattleState`, `DamageCalc`, `MoveOrder`, `Phase`, `TurnChoices`, `TurnPipeline` | Pipeline machinery and battle logic |
| `com.pokemon.battle.phase` (3) | `EndOfTurnPhase`, `MoveExecutionPhase`, `MoveOrderPhase` | Concrete phase implementations |

Tests stay in `com.pokemon.battle` with wildcard imports from all three subpackages.

## Format readiness audit (done)

Grepped for hardcoded `Player.P1`/`Player.P2` in source:
- All references are in **dispatch/lookup** code (`when(player)` switches, `opponent()`) — not iteration
- `EndOfTurnPhase` uses `Player.entries` for iteration — correct
- No hardcoded `listOf(Player.P1, Player.P2)` patterns found

The codebase is clean on this front. The migration path to `Slot`-based addressing for doubles remains as documented in the format audit.

## Validation

| Check | Result |
|-------|--------|
| `./gradlew compileKotlin` | PASS |
| `./gradlew test` — all 8 tests | PASS |
| No leftover files in root package | PASS |
| No hardcoded P1/P2 iteration | PASS |

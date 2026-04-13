# Diary 003: Cleanup — Package Organization and Format Readiness

**Date:** 2026-04-12
**Status:** Planned (after diary 002)

## Goal

Reorganize the flat `com.pokemon.battle` package into subpackages, and reduce coupling to singles-specific assumptions so the architecture stays open to doubles/triples/inverse battles.

## Package reorganization

Current: all 17+ files in `com.pokemon.battle`.

Proposed split:

| Package | Contents | Rationale |
|---------|----------|-----------|
| `com.pokemon.battle.model` | `Species`, `Pokemon`, `PokemonState`, `BattleState`, `StatStages`, `Type`, `Move`, `FieldState`, `StatusCondition`, `Volatile`, `Ability`, `Item`, `Player` | Pure data — no logic beyond derived properties |
| `com.pokemon.battle.engine` | `Phase`, `TurnPipeline`, `BattleEvent` + subclasses, `TurnChoices`, `MoveOrder`, `DamageCalc` | Pipeline machinery and battle logic |
| `com.pokemon.battle.phase` | `MoveOrderPhase`, `MoveExecutionPhase`, `EndOfTurnPhase` | Concrete phase implementations — this is the package that grows as mechanics are added |

Wait until after diary 002 to confirm these boundaries. EndOfTurnPhase may reveal sub-concepts that shift the split.

## Format readiness

Based on the audit (`docs/diaries/temp/002-format-audit.md`), the pipeline and event-sourcing architecture are format-agnostic. The singles-specific parts are:

### Things to address

1. **Avoid hardcoding `P1`/`P2` iteration** — new code should use `Player.entries` or similar instead of `listOf(Player.P1, Player.P2)`. Review EndOfTurnPhase after diary 002.

2. **Document the `Player` → `Slot` migration path** — if doubles becomes a goal, the change is: introduce a `Slot` type (e.g., `P1_LEFT`, `P1_RIGHT`), change `BattleState` to `Map<Slot, PokemonState>`, update events to target `Slot` instead of `Player`. The pipeline and Phase interface don't change.

3. **Keep `DamageCalc` single-target** — this is correct for all formats. The calling code handles multi-target (spread moves call it per-target with a 0.75x modifier). No change needed.

### Things NOT to do now

- Don't introduce `Slot` until we need doubles. Premature abstraction.
- Don't make `BattleState` generic over format. The map-based version is a different implementation, not a parameterization.
- Don't add target selection to moves. Singles has implicit targeting (the one opponent). Doubles would need it, but that's a doubles feature.

## Validation

- `./gradlew test` passes after reorganization (imports change, nothing else)
- Grep for hardcoded `P1`/`P2` iteration patterns and flag any

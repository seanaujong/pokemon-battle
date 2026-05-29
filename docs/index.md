# Documentation

## Where to start

- **New to this codebase?** Read [architecture.md](architecture.md) for the
  type definitions, phase pipeline, and extensibility model. Then skim
  [../CONTRIBUTING.md](../CONTRIBUTING.md) for the how-tos (adding an item,
  ability, move, event).

- **Want a worked example?** Read the tests, not prose:
  - `engine/src/test/kotlin/com/pokemon/battle/CharizardVsVenusaurTest.kt` —
    simple turn, single KO.
  - `engine/src/test/kotlin/com/pokemon/battle/InfernapeVsSwampertTest.kt` —
    priority, burn, sandstorm, Leftovers in one turn.

  These are the old `example-simple.md` / `example-extended.md` in
  executable form — they can't drift because they compile.

## Reference

| Doc | What it covers |
|-----|---------------|
| [architecture.md](architecture.md) | Technical spec: types, events, phases, pipeline |
| [data-ingestion.md](data-ingestion.md) | Ingestion architecture: layers, invariants, the committed-artifact seam |
| [corpus-format.md](corpus-format.md) | Persisted-battle JSON shape |
| [corpus-queries.md](corpus-queries.md) | DuckDB query cookbook for analysts |
| [skills/](skills/) | Recipes: how to add a move, event, item, ability |

## Development log

Diary entries in [`diaries/`](diaries/) track what was built, why decisions
were made, and what was learned along the way. Each entry follows the
iteration loop described in [../CLAUDE.md](../CLAUDE.md).

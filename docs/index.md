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

## Documentation conventions

- **Docs are self-contained and cohesive.** Each doc must make sense on its
  own. Inter-doc links live only in this index — don't cross-link doc bodies,
  don't add "See also" sections, and don't reference diary entries from a doc
  body. That web of links is what rots when files move, get renamed, or
  decisions are superseded.
- **Canonical docs and diaries are two tiers.** The docs in the reference
  table are canonical — the system as it is; trust them and the code for
  current behavior. Diaries are the event log: accurate as *rationale* (why a
  decision was made, at the time), not as current reference. Distill durable
  conclusions up into a canonical doc; leave the narrative in the diary.
- **Each doc opens with an "At a glance" abstract** — skimmable in 10 seconds.
- **Headings are unnumbered and named descriptively** — the abstract is the
  TOC; section names are the stable interface.
- **Within-doc cross-references are by name, never by position** — "the
  move-order phase", not "step 3".

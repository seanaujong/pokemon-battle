# Documentation

## Where to start

- **New to this codebase?** Read the [guide](guide.md) — it walks through how a battle turn works, from the player's perspective down to the code, using Pokemon examples you already know.

- **Want the technical spec?** The [architecture](architecture.md) doc has complete type definitions, the phase pipeline design, and the extensibility model.

## Reference

| Doc | What it covers |
|-----|---------------|
| [guide.md](guide.md) | How a turn works — explains the architecture through gameplay |
| [architecture.md](architecture.md) | Technical spec: types, events, phases, pipeline |
| [example-simple.md](example-simple.md) | Worked example: Charizard KOs Venusaur with Flamethrower |
| [example-extended.md](example-extended.md) | Worked example: priority, burn, sandstorm, Leftovers |

## Development log

Diary entries in [`diaries/`](diaries/) track what was built, why decisions were made, and what was learned along the way. Each entry follows the iteration loop described in [CLAUDE.md](../CLAUDE.md).

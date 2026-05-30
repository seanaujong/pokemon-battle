# pokemon-battle

A Pokemon singles battle engine in Kotlin, built as an **event-sourced turn
pipeline**: every turn is resolved by folding immutable state through a sequence
of pure phases, and *everything that happens* — who moved first, each hit, each
faint — is recorded as an event. The event log is the battle.

<!--
  Demo GIF. The rendered file is NOT committed (it's gitignored) — a binary blob
  is permanent in git history. To host it on GitHub's free asset CDN: drag
  assets/demo.gif into any GitHub issue/PR/comment box, copy the
  https://github.com/user-attachments/assets/... URL it returns, discard the
  draft, and paste that URL into the src below. Regenerate the GIF itself with
  `vhs assets/demo.tape`.
-->
<p align="center">
  <img src="https://github.com/user-attachments/assets/c02425b3-98ef-4494-9cd7-36f3baa89163" alt="An AI-vs-AI demo battle resolving turn by turn in the terminal" width="700">
</p>

## What it does

Give it two teams and a way to pick moves, and it plays the battle out turn by
turn — speed order, damage with type effectiveness and STAB, status, weather,
items, abilities, switching, and fainting — then hands back the winner, the
final state, and the complete history of events.

The history isn't a side effect bolted on for logging. It *is* how the engine
works: phases never edit state in place, they emit events, and each event knows
how to produce the next state. That one decision is what makes the same battle
renderable as text, replayable for debugging, and queryable for analytics —
all from the same stream.

## The core idea

A turn is a fold. Immutable `BattleState` flows left to right through a list of
**phases**. Each phase reads the state, emits zero or more **events**, and those
events are applied to produce the next state. Orchestration is a ~5-line nested
fold; all the real logic lives in the phases and events, each testable on its
own.

```
TurnChoices + BattleState
        |
        v
  +-----------+     +-----------+     +-----------+     +-----------+
  | MoveOrder | --> |  Switch   | --> | MoveExec  | --> | EndOfTurn | --> ...
  +-----------+     +-----------+     +-----------+     +-----------+
   emit events       emit events       emit events       emit events
        |                 |                 |                 |
        v                 v                 v                 v
  apply to state    apply to state    apply to state    apply to state
        |                 |                 |                 |
        +--------+--------+--------+--------+---------+-------+
                 |
                 v
       Final BattleState + List<BattleEvent>  (complete history)
```

Three guarantees fall out of this shape, and they're the engine's load-bearing
invariants:

- **State is never mutated.** Every field is `val`; events are the *only* way
  state changes. That's why N battles can run concurrently in one JVM with no
  shared-state contamination.
- **Phases are pure functions** of `(state, choices) -> List<BattleEvent>`, so
  each mechanic is verifiable in isolation.
- **New mechanics are new events + phases, not edits to old ones.** Adding a
  move, item, or ability touches new files and a registry entry; existing code
  stays untouched.

The engine also **doesn't enforce legality** — no learnset checks, no move-count
limits. It resolves whatever it's handed, which is what lets custom formats
(Hackmons, Almost Any Ability) work without engine changes. Legality is a
team-building concern, a layer above.

## A turn, rendered

The demo above is AI vs AI. Here's the start of that battle as the text renderer
prints it — each line corresponds to one event in the log:

```
--- Turn 1 ---
Charizard used Flamethrower!
It's super effective!
Venusaur took 133 damage! (155 → 22 HP)
Venusaur used Sludge Bomb!
Charizard took 70 damage! (153 → 83 HP)
--- Turn 2 ---
Charizard used Flamethrower!
It's super effective!
Venusaur took 133 damage! (22 → 0 HP)
Venusaur fainted!
Go! Blastoise!
```

Want to see how a single turn is constructed in code, end to end? The tests are
the worked examples — they compile, so they can't drift:

- `engine/src/test/kotlin/com/pokemon/battle/CharizardVsVenusaurTest.kt` — a
  simple turn: outspeed, super-effective hit, KO.
- `engine/src/test/kotlin/com/pokemon/battle/InfernapeVsSwampertTest.kt` —
  priority, burn, sandstorm, and Leftovers all in one turn.

## Run it

```sh
./gradlew :cli:demo      # watch an AI-vs-AI battle play out (the GIF above)
./gradlew :cli:run       # play it yourself, choosing moves at the prompt
./gradlew test           # run the suite (also writes a JaCoCo coverage report)
```

To regenerate the demo GIF after an engine change: warm the build with
`./gradlew :cli:assemble`, then run `vhs assets/demo.tape`.

## How it's built

The repo is a Gradle multi-module build. The engine sits at the bottom with
**zero I/O and zero serialization dependencies**; everything else depends on it,
never the other way around. A consumer is just another module that reads the
event stream.

```
:cli             interactive / demo entry points
:server          JSONL over stdin/stdout for out-of-JVM clients
:analytics       event-stream consumers (win rates, replay export)
:data-ingestion  PokeAPI / Smogon fetchers
:ai              choice strategies (random, type-aware)
:render          events → text
:data            catalogs (Pokedex, moves, items, abilities)
:engine          pipeline, events, model, phases, loop  ← no project deps
```

## Where to go next

- **[docs/architecture.md](docs/architecture.md)** — the *why* and shape of the
  engine: the event-sourcing model, layering, the registry pattern for
  gen-varying catalogs, and the multi-gen / custom-format extension points.
- **[CONTRIBUTING.md](CONTRIBUTING.md)** — the *how*: adding a move, item,
  ability, or event; testing conventions; build, lint, and commit discipline.
- **[docs/index.md](docs/index.md)** — the full documentation map, including the
  battle-corpus format, the DuckDB query cookbook, and the development diaries
  that record how each piece came to be.

## Stack

Kotlin 2.2.10 · JVM 17 · Gradle 8.14 · kotlin-test + JUnit Platform · ktlint ·
detekt · JaCoCo.

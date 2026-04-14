# Diary 069: External-language client as the isolation litmus test

**Date:** 2026-04-14
**Status:** Planning — scope, shape, prerequisites.

## Why this diary exists

Diary 067 flagged "frontend and server as separate repositories" as the
strongest test of whether our module boundaries are real. The user:
*"A client of a separate language being able to interact with the
engine/server is a great litmus test."* That's the goal.

We don't need a full networked server — we need a process that a
non-JVM client (Python, TypeScript) can subprocess and talk to over
stdin/stdout with line-delimited JSON, plus a text team format the
client can build and send. This is exactly Showdown's `battle-stream.ts`
shape, deferred in diary 054 pending a forcing function. This is the
forcing function.

## Goal

A Python or TypeScript script can:

1. Subprocess the JVM `:server` module.
2. Send two teams in Smogon format, get back an acknowledgment.
3. Send turn choices as JSON lines, receive battle events as JSON lines
   until the battle ends.
4. Exercise the whole path with a `RandomAI`-level client logic,
   proving an out-of-JVM consumer can drive the engine end to end.

That's the litmus test. If any piece requires the client to reach into
JVM internals, the isolation is leaky.

## Prerequisite: delete the generated-catalog path

Before the server work, a cleanup the server makes obvious:

- `PokedexCatalog.XXX` symbol-keyed access has one production call site
  (`PlayMain.kt`, migrated in diary 064). Its supporting infrastructure
  is `PokedexCodegenMain` + `PokedexCodegenTest` + `codegenSpecies`
  Gradle task + pre-commit hook line + ktlint exclude in `:data`.
- The JSON classpath loader (`Pokedex.loadJsonFromClasspath`) is the
  workhorse: ~30 call sites across `:engine`, `:analytics`, `:render`,
  `:ai`, `:cli` tests plus `DemoMain`.
- The server resolves team input at runtime via **name lookup** (client
  sends `"Charizard"`, server resolves to `Species`). That's the JSON
  loader's natural shape; the generated-catalog's compile-time-symbol
  benefit cannot help any non-JVM client.

Diary 064's case study (can the engine consume catalogs via either
shape?) earned its keep as a diary. The infrastructure didn't. Delete
it.

**Deletions:**
- `data/src/main/kotlin/com/pokemon/battle/data/PokedexCatalog.kt`
- `data-ingestion/src/main/kotlin/com/pokemon/battle/ingest/codegen/` (whole dir)
- `data-ingestion/src/test/kotlin/com/pokemon/battle/ingest/codegen/PokedexCodegenTest.kt`
- `codegenSpecies` task in `data-ingestion/build.gradle.kts`
- `ktlint { filter { exclude PokedexCatalog.kt } }` in `data/build.gradle.kts`
- `PokedexCodegenTest` invocation in `.githooks/pre-commit`
- `data/src/test/kotlin/com/pokemon/battle/data/PokedexCatalogTest.kt`

**Revert:** `PlayMain.kt`'s `PokedexCatalog.CHARIZARD` to
`pokedex.getValue("Charizard")`, matching every other call site.

## Team import: Smogon set format (text)

Rather than inventing a JSON team schema, the client sends the textual
Smogon format already used across the competitive community:

```
Charizard @ Life Orb
Ability: Blaze
EVs: 252 SpA / 4 SpD / 252 Spe
Timid Nature
- Flamethrower
- Air Slash
- Focus Blast
- Roost

Venusaur @ Black Sludge
Ability: Overgrow
EVs: 252 HP / 252 Def / 4 SpD
Bold Nature
- Giga Drain
- Sludge Bomb
- Leech Seed
- Synthesis
```

**Why this over JSON:**

- Copy-paste friendly: users grab sets from Smogon / Reddit / Discord
  without translating to our schema.
- Name resolution is already solved by `SmogonToTargetsMain`'s alias
  map and our ingest pipeline — we know how `"mewtwo-mega-x"` maps to
  our catalog.
- Aligns with diary 067's row on team import as a Showdown seam we
  lacked; this makes it concrete.

**Resolver lives in `:server`** (or a new `:team-import` module if it
grows). Takes Smogon text, returns `List<Pokemon>` via `:data`'s
`Pokedex` loader + `MoveDex` + engine enums for `Item` / `Ability` /
`Nature`.

**Engine still does not enforce legality.** A Charizard with Earthquake
(not in its learnset) passes through; the client has taken
responsibility for team validity. Validation is diary 067's
`team-validator.ts` row — a separate module, later.

## DTO layers still needed

- **`TurnChoiceJson`** — mirror of diary 060's `BattleEventJson`.
  Today `TurnChoice` is sealed (MoveChoice, SwitchChoice, future
  GimmickChoice) but not serializable.
- **`InputResponseJson`** — mid-turn input responses (e.g.,
  `SwitchTargetResponse`) also need a wire format. Input *requests*
  come over as events already via the event DTO.
- **`TeamImportResult`** (maybe) — server's ack after parsing a team,
  or an error payload if the team text is malformed.

Add `protocolVersion: 1` to every outgoing server message. No migration
code until v2 exists.

## The `:server` module

New Gradle module `:server`:

```
:server → :engine (implementation), :data, :render, :ai
```

- Reads stdin line by line. Each line is a JSON message (team import,
  choice, input response, or control).
- Writes stdout line by line. Each line is a JSON message (event,
  input request, result, error).
- No HTTP, no WebSocket, no threads beyond what `BattleLoop` already
  does. Simplest shape that proves the boundary.

Main class is a `ServerMain` that:
1. Reads team imports for both sides.
2. Constructs the `BattleLoop` with a `ChoiceProvider` backed by
   stdin-JSON and emits events to stdout-JSON.
3. Runs until `BattleResult` is produced, emits the result, exits 0.

Showdown's `sim|>player|action` line format is a reference, not a
requirement. We adopt a simpler JSONL shape: every line is a complete
JSON object with a `type` discriminator.

## The smoke test

A Python script in `scripts/smoke-test-external-client.py` (or a
TypeScript equivalent):

1. Starts the JVM server via `./gradlew :server:run` or a produced jar.
2. Sends two hard-coded Smogon team blobs.
3. Plays turns via local `random.choice` over legal moves reported in
   the event stream.
4. Reads events until a `BattleEnded` message.
5. Asserts `exit_code == 0` and `winner` is one of the two sides.

If this passes, the module boundaries and wire protocol have survived
an out-of-JVM consumer. That's the proof.

## Non-goals (for this diary)

- **WebSocket / HTTP.** stdin/stdout is enough for the litmus test.
- **Concurrent battles.** One server process = one battle.
- **Replay loading.** Just live play.
- **Team validator.** Engine accepts what it's given; diary 067's
  `team-validator.ts` row is a later separate module.
- **Protocol versioning logic.** Field present from day one, migration
  code deferred until v2 exists.

## Plan (rough order)

- [ ] Delete generated-catalog path (prerequisite above).
- [ ] `TurnChoiceJson` DTOs + round-trip tests in `:engine`.
- [ ] `InputResponseJson` DTOs + round-trip tests.
- [ ] Smogon set text parser (module TBD — `:server` for v1).
- [ ] `:server` module scaffold — `ServerMain`, JSONL read/write loop.
- [ ] Stdin-backed `ChoiceProvider` + `InputResponder`.
- [ ] Event → stdout JSONL emitter.
- [ ] Python smoke test script.
- [ ] Wire protocol documentation in `docs/wire-protocol.md` (or as a
  section in `:server`'s README).

## Validation signal

- All existing tests still green after the prerequisite deletion.
- `:server:test` passes (unit tests on the parser + DTOs).
- The Python smoke-test script runs end-to-end with exit 0 and prints
  a sensible battle log. That's the real signal; the rest is
  plumbing.

## Open questions

- **One `:server` module or split into `:server` + `:team-import`?**
  Propose one module for v1; split if the Smogon parser grows past a
  single file or gets its own nontrivial test corpus.
- **Which JVM runtime format for shipping the server?** `./gradlew
  :server:run` works for the smoke test; a `shadowJar` lets external
  clients `java -jar` without Gradle. Decide when we ship.
- **Python or TypeScript for the smoke test?** Python has fewer deps
  (stdlib subprocess) and keeps the signal tight. TypeScript proves
  the "eventual web client" path more directly. Start with Python;
  the protocol is language-agnostic anyway.
- **Does `:server` belong as a peer of `:cli`, or is `:cli` eventually
  replaced by `:server` + a thin stdin shim?** Probably peers for now
  — `:cli` is interactive-TTY-shaped, `:server` is machine-to-machine
  shaped.

## Related

- **Diary 054** — deferred event streaming until a UI consumer arrived.
  This is that consumer.
- **Diary 060** — `BattleEventJson` DTO layer; one of the prerequisites.
- **Diary 064** — the case study that built both catalog paths. This
  diary retires the generated one.
- **Diary 067** — cataloged seams Showdown has that we don't. This
  diary implements three of them (battle-stream, team import, server).
- **Diary 068** — `internal` visibility audit. The server respects
  those boundaries by design.

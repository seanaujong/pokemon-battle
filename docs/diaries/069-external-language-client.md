# Diary 069: External-language client as the isolation litmus test

**Date:** 2026-04-14
**Status:** Complete — all five steps shipped. Python subprocess drove
a full battle through the JVM server via JSONL. Litmus test passed.

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
- [x] Smogon set text parser (landed in `:server` for v1).
- [x] `:server` module scaffold — `ServerMain`, `ServerSession`, JSONL loop.
- [x] Per-turn prompts merged into `ChoiceRequest` + `FaintReplacementRequest` +
  `InputRequestMessage` rather than stdin-backed providers — the session owns
  the turn loop so protocol messages and engine callbacks interleave cleanly.
- [x] Event → stdout JSONL emitter (`TurnEvents` message).
- [x] Python smoke test script (`scripts/smoke-test-external-client.py`).
- [ ] Formal wire protocol doc — deferred; the Messages.kt sealed hierarchies
  with `@SerialName` annotations are the source of truth for v1.

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

## What shipped

Five commits, all green under `./gradlew test ktlintCheck detekt`:

1. `4e2ab66` — remove generated-catalog path (prerequisite).
2. `f0b100b` — `TurnChoice` + `TurnChoices` DTO layer.
3. `b66a644` — `:server` module with Smogon parser.
4. `40acd9a` — `ServerSession` JSONL loop + protocol messages.
5. `9bc828c` — Python smoke test, `@SerialName` annotations for
   human-readable wire format, full `Move` objects in `Ready.slots` so
   clients can echo choices without a catalog.

The smoke test runs 5/5 green with random move selection — side 1
(Charizard with Flamethrower + Earthquake + Thunderbolt) beats side 2
(Venusaur + Blastoise) in 4–10 turns depending on move rolls.

## The pattern: daemon + thin client speaking a wire protocol

Once the smoke test worked, the shape became recognizable —
`:server` is the same structural split as Airflow / Docker / K8s:

| Ours | Docker | K8s | Airflow |
|---|---|---|---|
| `:server` (JVM) | `dockerd` | `kube-apiserver` | scheduler |
| Python smoke test | `docker` CLI | `kubectl` | `airflow` CLI |
| JSONL over stdin/stdout | JSON over Unix socket | REST / gRPC | REST + CLI |
| `BattleEventJson` stream | event stream | `watch` events | task logs |
| `team_set` / `choice` | `docker run` / `exec` | `apply` manifest | DAG submit |

**Where we deliberately differ (for now):**

- **One battle per process** vs their long-running daemons. Adding a
  session multiplexer (outer `session_id` field on every message) is
  straightforward when pressure arrives — we don't pay for it today.
- **No auth, no authz.** They need it; we don't, because the transport
  is a subprocess pipe. When the first network-exposed consumer
  appears (web UI), that's the forcing function.
- **Stdin/stdout, not a socket.** Cheapest transport. The JSONL shape
  itself is transport-agnostic; moving to TCP/WebSocket doesn't change
  `Messages.kt` at all.

**Why the analogy is load-bearing, not cosmetic:** the reason Docker/K8s/
Airflow can have Go, Python, JS, Rust clients all speaking the same
server is that the wire protocol is the *only* contract. Anything not on
the wire is server-internal and free to change. Our diary 068 `internal`-
visibility audit is the same principle applied inside the JVM: the
contract is what crosses the boundary, everything else is free to
refactor. The Python smoke test proves the boundary is real — it has
zero Kotlin, zero JVM, and drives a complete battle.

## Should `protocolVersion` actually stay?

We included `protocolVersion: 1` on every message "from day one, with
migration logic deferred." Now that it's built, the honest question:
*is this field doing anything?*

Three options:

1. **Remove it until forced.** Consistent with CLAUDE.md's "don't design
   for hypothetical future requirements." We control both sides of the
   wire today — if we change the protocol, we change both. A field with
   no consumer is ceremony.
2. **Enforce it.** Server rejects messages with mismatched version;
   client checks server's version on `Ready` and aborts on mismatch.
   Cheap (a few lines of validation). A field with enforcement at least
   *means* something.
3. **Leave inert.** Keep the field, don't validate. This is the worst
   of the three — a public claim ("this is v1") that isn't backed by
   behavior. When we add v2, clients that sent `protocolVersion: 1`
   will have been silently accepted by a v2 server that assumed they
   meant v2, or rejected with a confusing error, or anything in
   between.

**Recommendation: option 2, enforce it.** The forcing function for v2
is a real second consumer (web UI, MCP tool) — when that arrives, the
field becomes load-bearing. Enforcing it now costs ~10 lines of code
and means:
- A mismatched client gets a clear `error` message immediately, not a
  confusing parse failure three messages in.
- Protocol migration is a real operation with real semantics when v2
  lands, not a cosmetic field bump.
- The `@SerialName("error")` message type already exists; this is the
  natural use case.

Option 1 is defensible: delete the field, add it back in one commit
when v2 is forced. The risk is that by then there's a Python client in
someone's hands that doesn't know about protocolVersion, and we have
to coordinate a flag day. That risk is low because we have exactly one
consumer today (`smoke-test-external-client.py`, in this repo).

Leaning toward option 2 — enforce server-side, document the behavior
("server rejects non-matching protocolVersion with an `error` message
and closes the stream"). Follow-up to this diary if adopted.

### Update: option 2 shipped (commit `314876c`)

Enforcement landed: `ServerSession.readClient` validates
`protocolVersion` on every incoming message and emits an `error`
before closing if it mismatches. Test:
`ServerSessionTest.mismatched protocolVersion produces an error
message`.

**What this is NOT:** a backwards-compatibility commitment. The user's
reality check on this is correct — this is a solo project where the
server and every checked-in client (smoke test, `:cli`) live in the
same repo and change together. If the protocol bumps to v2, both
sides move in one commit; there are no v1 clients in the wild to
break.

**What it IS:** a mismatch-detector. The specific bug it catches is
"I changed the server and forgot to update the Python script" — the
two get out of sync, the field catches it with a clear error message
instead of a confusing parse failure three messages in. Cheap
insurance for the exact risk a solo project has.

The comment at the top of `Messages.kt` spells this out so a reader
doesn't mistake the field for a BC promise. If/when deployed clients
we can't coordinate with appear, that's the point to revisit — add
migration logic, deprecation windows, the usual BC machinery. Not
before.

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

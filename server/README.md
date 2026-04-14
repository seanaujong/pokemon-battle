# `:server`

JSONL over stdin/stdout wrapper around `:engine`'s battle loop. Lets an
out-of-JVM client (Python, TypeScript, anything that can subprocess and
read lines) drive a Pokemon battle end-to-end without needing a JVM
dependency.

See **diary 069** for the design rationale (`docs/diaries/069-external-
language-client.md`). See **diary 071** for the registry-DI refactor
that `:server` depends on.

## Quick start

```bash
# Build a runnable binary:
./gradlew :server:installDist

# Run it — reads newline-delimited JSON messages from stdin, writes
# newline-delimited JSON from stdout, one message per line:
server/build/install/server/bin/server

# Or run via the Gradle application plugin:
./gradlew :server:run

# For a working example, drive a full battle from Python:
python3 scripts/smoke-test-external-client.py
```

## Protocol (v1)

One JSON object per line. Every message carries `protocolVersion: 1`;
mismatched versions produce an `error` message and close the stream.
The version is a mismatch detector, *not* a backwards-compatibility
promise (see diary 069).

The source of truth for the full message shape is
`server/src/main/kotlin/com/pokemon/battle/server/protocol/Messages.kt`.
Start there. Summary below.

### Client → server

| `type` | Fields | When |
|---|---|---|
| `team_set` | `side`, `team` (Smogon format text) | Session setup — send twice, one per side. |
| `choice` | `choices: {entries: [{slot, choice}]}` | Every turn, in response to `choice_request`. |
| `input_response` | `response` | In response to `input_request` (mid-turn prompt like U-turn target). |
| `faint_replacement` | `slot`, `benchIndex` | In response to `faint_replacement_request`. |

### Server → client

| `type` | Fields | When |
|---|---|---|
| `ready` | `slots`, `benches` | After both `team_set`s; includes move pools so client can build choices. |
| `choice_request` | `turn`, `activeSlots` | Before every turn. |
| `turn_events` | `turn`, `events`, `replacementEvents` | After each turn resolves. |
| `input_request` | `request` | Pipeline paused mid-turn (e.g. U-turn needs a switch target). |
| `faint_replacement_request` | `slot`, `eligibleBenchIndices` | After a turn's events, for each fainted active slot with a bench. |
| `result` | `winner`, `turns` | Battle ended (win / draw / turn limit). |
| `error` | `message` | Parse failure, protocol mismatch, or other session abort. |

### Choice shape

A turn choice is either `use_move` or `switch`:

```json
{"type": "use_move", "move": {"name": "Flamethrower", "type": "FIRE", "category": "SPECIAL", "power": 90}}
{"type": "switch", "benchIndex": 0}
```

Clients build `use_move` by echoing a `Move` object the server sent
them in `ready.slots[i].moves`. No name-resolution table needed on the
client side.

### Team format

`team_set.team` is **Smogon set text** — the same format Smogon,
Reddit, and Discord use. Example:

```
Charizard @ Life Orb
Ability: Blaze
Level: 100
EVs: 252 SpA / 4 SpD / 252 Spe
Timid Nature
- Flamethrower
- Thunderbolt
- Earthquake
- U-turn
```

Multiple sets are separated by blank lines. Nickname / gender / shiny /
Tera Type lines are accepted but ignored in v1.

The engine does *not* enforce team legality. Clients are free to send a
Charizard with Earthquake that it can't learn. A future team-validator
module (diary 067's `team-validator.ts` row) is where legality would
live.

## Lifecycle

One session per process. The server reads two `team_set` messages,
builds the battle state, then runs turns until a win / draw / turn
limit / error. Then it emits a `result` and exits 0.

Multiplexing multiple battles in one process is not supported — see
diary 070's audit for what it would cost. The supervisor pattern
(one process per battle, managed externally) is the current shape.

## Limitations (v1)

- No auth / authz. Subprocess pipe is the trust boundary.
- No network transport. Add one (WebSocket, HTTP streaming) without
  changing the protocol shape — the JSONL message format is
  transport-agnostic.
- No protocol migration logic. Bumping to v2 is an atomic change
  across server + every client in this repo.
- Singles only. The DTO types for doubles slots exist but the
  team-parser and session flow assume one active slot per side.
- Nickname, gender, shiny, Tera Type in the Smogon parser are
  dropped on the floor.

## Related

- `docs/diaries/069-external-language-client.md` — design, litmus test results, protocolVersion discussion.
- `docs/diaries/067-seams-we-lack.md` — catalog of Showdown seams we don't have; the server implemented several.
- `docs/diaries/070-multiplexing-and-concurrency.md` — audit for running many battles in one JVM.
- `docs/diaries/071-registry-di-refactor.md` — registry injection that phases rely on.
- `scripts/smoke-test-external-client.py` — working Python client demonstrating every message type.

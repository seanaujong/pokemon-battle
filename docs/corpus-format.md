# Battle corpus format (v1)

This document describes the on-disk shape of a persisted battle. The
format is owned by the `:persistence` module; this doc is the contract
that every reader (`BattleLoader`, analytics CLIs, DuckDB queries,
future replay viewers) depends on.

If this doc and `PersistedBattle.kt` disagree, the source code wins and
the doc is stale — but please update the doc when you land the change.

## Shape

One file per battle. File name: `<battleId>.json` where `battleId` is a
UUIDv4. Files live in a caller-configured directory (typically
`./battles/` for local runs; configurable via env var for the server).

Each file is a single JSON object, pretty-printed, UTF-8:

```json
{
  "metadata": {
    "battleId": "04b2848e-3c84-4d48-a0d3-e7a749a02aa0",
    "startedAtEpochMs": 1776400000000,
    "endedAtEpochMs":   1776400000500,
    "formatTag": "matrixEval",
    "playerTags": { "SIDE_1": "TypeAI", "SIDE_2": "RandomAI" },
    "protocolVersion": 1,
    "clientInfo": "seed=42"
  },
  "winner": "SIDE_1",
  "turns": [
    {
      "turnNumber": 1,
      "events": [ /* BattleEventJson entries */ ],
      "replacementEvents": [ /* faint-replacement BattleEventJson entries */ ]
    }
  ]
}
```

## Fields

### Metadata

| Field | Type | Required? | Notes |
|---|---|---|---|
| `battleId` | string | yes | UUID; file name equals `$battleId.json`. |
| `startedAtEpochMs` | long | yes | Millis since epoch at battle construction. |
| `endedAtEpochMs` | long | yes | Millis since epoch at battle end, stamped via `withEnded()`. |
| `formatTag` | string | yes | Free-form format label: `"matrixEval"`, `"server-live"`, `"batchDemo-…"`. |
| `playerTags` | map<string, string> | optional (default `{}`) | Keyed by `Side.name` (`"SIDE_1"`, `"SIDE_2"`). Values are caller-chosen (AI strategy name, handle, etc.). |
| `protocolVersion` | int? | optional | Server-driven battles set this to `PROTOCOL_VERSION`; local batch runs leave it null. |
| `clientInfo` | string? | optional | Caller-chosen free-form tag. Matrix eval encodes `"seed=N"` so every battle is reproducible. |

### Body

| Field | Type | Notes |
|---|---|---|
| `winner` | `"SIDE_1"` / `"SIDE_2"` / null | null = draw or turn limit. |
| `turns` | list | Complete per-turn history. |
| `turns[i].turnNumber` | int | 1-indexed. |
| `turns[i].events` | list | `BattleEventJson` entries for the turn's main pipeline. |
| `turns[i].replacementEvents` | list | Events from faint-replacement, if any, for this turn. |

`finalState` is **deliberately not** serialized. Events are the source
of truth; the final state can be reconstructed by replaying them if a
consumer needs it. Omitting avoids baking a snapshot of a complex
immutable graph into every file.

## Event entries

Each entry in `events` / `replacementEvents` is a JSON object
following `BattleEventJson`'s sealed hierarchy. The discriminator is
`type` and its value is the fully-qualified Kotlin class name of the
DTO (e.g. `com.pokemon.battle.engine.serialization.DamageDealtJson`).
For wire-protocol messages the server uses short `@SerialName` values
(`"use_move"` etc.); the on-disk event format kept the long names
because analytics and replay consumers don't benefit from shortness and
the long names are self-describing for ad-hoc DuckDB queries.

Full list of variants: `engine/src/main/kotlin/com/pokemon/battle/engine/serialization/BattleEventJson.kt`.

## Versioning

The format is **v1**. No explicit version field on the file — `metadata`
structure and event DTOs are the de facto version. Changes:

- **Additive field on metadata:** safe. Older readers skip unknown
  fields (`ignoreUnknownKeys = true` in the loader).
- **Additive event variant:** safe for the same reason for readers
  that filter by `type`; readers that `when`-match exhaustively will
  fail loud and need a new branch.
- **Removing a field or renaming:** breaking. Handle via the DTO
  migration pattern (diary 060) — add a new DTO, keep the old, make
  the `toDomain` converter tolerate both.

Diary 076 is the worked precedent — `DamageDealt.critical` was removed
from the DTO after every in-repo consumer migrated to reading
`CriticalHit` events.

## Reading the corpus

**Kotlin consumers:**

```kotlin
val battles: Sequence<PersistedBattle> = BattleLoader.loadAll(dir)
```

Returns a lazy sequence; stream the corpus without loading all of it
into memory.

**Ad-hoc queries via DuckDB** (no build steps, no Kotlin):

```sql
-- Battles per winner
SELECT winner, COUNT(*) FROM read_json('battles/*.json', format='unstructured')
GROUP BY winner;

-- Per-seed reproducibility audit
SELECT metadata->>'clientInfo' AS seed, winner
FROM read_json('battles/*.json', format='unstructured')
ORDER BY seed;
```

Before adding a new `BattleCorpus` Kotlin function, check whether a
two-line DuckDB query covers it. Diary 081 argues for this as the
cheap-answer-first discipline. When the same query gets run often
*and* belongs in the test suite, promote it to `BattleCorpus`.

**External-language consumers** (Python, TS, etc.) can parse the JSON
with their native libraries and reimplement `PersistedBattle` shape
locally. Keep field names in sync by regenerating from this doc when
it changes.

## Storage layout — current

Flat directory. No partitioning, no indexes. Tens of thousands of
files per directory are fine; beyond that, add a `yyyy/mm/dd/`
partition scheme (trivial to retrofit).

## Storage layout — deferred

The following are explicitly not here yet (see diary 081 for
thresholds):

- Columnar conversion (Parquet). Forced at ~100K+ battles with whole-
  corpus queries.
- Per-turn streaming. Each file is written at battle end only.
- Compression. Files are pretty-printed JSON; a typical 10-turn battle
  is ~8-12 KB. Gzip would cut that 5-10× but adds a pipeline step —
  defer until disk usage is a real concern.

## Related

- `:persistence/src/main/kotlin/com/pokemon/battle/persistence/PersistedBattle.kt` — authoritative shape.
- `:persistence/src/main/kotlin/com/pokemon/battle/persistence/FileBattleRecorder.kt` — write path.
- `:persistence/src/main/kotlin/com/pokemon/battle/persistence/BattleLoader.kt` — read path.
- Diary 078 — pipeline v1 design.
- Diary 080 — `:server` recording.
- Diary 081 — industry comparison that motivated DuckDB-as-escape-hatch.
- Diary 076 — schema-evolution worked example.

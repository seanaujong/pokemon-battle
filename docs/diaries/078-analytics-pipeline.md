# Diary 078: Analytics pipeline — from one battle to an aggregate view

**Date:** 2026-04-14
**Status:** Planning — no code changes. Documents the shape, layering
constraints, and forcing-function thresholds. Implementation waits on
a real consumer (web UI stats, ML corpus, leaderboard).

## Why this diary exists

Diary 042 established that the battle event log is a first-class data
asset — every turn is a complete, serializable audit trail. `:analytics`
ships `BattleAnalyzer.analyze(result) → BattleSummary` for single-battle
rollups.

What's missing is the path from *"a battle just ran"* to *"here are the
aggregate stats over N battles"*. Three concrete gaps: no persistent
sink, no collection path from live games, and no cross-battle metadata
(battle id, timestamp, format tag, player identity).

The user's framing when this came up: *"all suggestions are fine as long
as we're not leaking into layers like we did earlier."* That's the
design constraint this diary is written against. Earlier leaks we fixed:
diary 065 (species catalog in `:engine` — moved to `:data`), diary 066
(rendering in `:engine` — moved to `:render`), diary 071 (items /
abilities as engine-internal registries — moved to `:data` behind a DI
contract). This diary is the plan for the next set of concerns, done
right from the start rather than caught later.

## The three gaps, named

### Gap 1: no persistent sink

`BattleResult` lives and dies in a JVM process. `ReplayExporter.toJson`
produces a string; nobody stores it.

### Gap 2: no collection from live games

`ServerSession` streams events to stdout, but nothing also tees them to
a file. `PlayMain` / `DemoMain` don't persist anything after a battle
ends. The "session-has-a-recorder" seam doesn't exist.

### Gap 3: no cross-battle metadata

To aggregate, you need to distinguish battles, attribute them to a
format / player, and time-order them. `BattleResult` has none of this
— by design, because it's pure mechanics.

## Layering discipline — what does NOT leak

Before naming where new things go, name where they **don't** go. These
are the layer-leak failure modes we're avoiding:

- **`:engine` gains `BattleId` / `timestamp` / `format tag` / player
  identity.** These are consumer-level metadata; `:engine` stays pure
  mechanics. `BattleResult`'s fields (winner, finalState, turnHistory)
  are the complete engine output. No additions.
- **`:analytics` gains file I/O or a database driver.** `:analytics`
  is a pure transformation over events, same shape as `:render`.
  Reading from disk is a different concern.
- **`:render` or `:ai` know anything about persistence or metadata.**
  Neither is a consumer of the storage layer.
- **Metadata fields sneak into `BattleEvent` subclasses.** Events are
  mechanical; adding "playerId" to `MoveAttempted` or similar would be
  the same class of bug as putting game rules in event `apply()`
  methods (see `docs/architecture.md`'s "Events are mechanical, rules
  live in phases" lesson).
- **`:server` gains a hard dep on `:persistence`.** The server doesn't
  record by default; a recorder is an optional injected dependency.
  `:server` declares the *interface*; `:persistence` implements it.

## Proposed modules

### New: `:persistence`

Owns the storage layer. Knows about files, JSONL, directory layout.
Does not know about rendering or AI. Depends on `:engine` only (for
`BattleResult` and event DTOs).

What lives here:

```
:persistence
├── BattleMetadata                 // id, startTime, endTime, formatTag, protocolVersion, clientInfo
├── PersistedBattle                // BattleMetadata + BattleResult wrapper
├── BattleRecorder                 // interface: record(result, metadata)
├── FileBattleRecorder             // implementation: writes JSONL to a configured dir
└── BattleLoader                   // reads the dir back for :analytics batch mode
```

**Why a new module rather than putting these in `:analytics`:** file
I/O is different from stream analysis. `:analytics` stays pure — it
accepts `List<PersistedBattle>` (or `Sequence<PersistedBattle>` for
streaming) and produces rollups. Where those come from is
`:persistence`'s business. Same shape as the `:data` / `:engine` split:
`:engine` resolves whatever it's given, `:data` supplies catalogs.

### Extended: `:analytics`

Gains batch aggregation functions:

```kotlin
object BattleCorpus {
    fun aggregateMoveUsage(battles: Sequence<PersistedBattle>): Map<Move, Int>
    fun aggregateItemWinRate(battles: Sequence<PersistedBattle>): Map<Item, Double>
    fun aggregateKosPerTurn(battles: Sequence<PersistedBattle>): Double
    // ... etc
}
```

`:analytics` gains a `testImplementation(:persistence)` so batch tests
can exercise the full read-aggregate path, but `:analytics` main has
**no** production dep on `:persistence`. Keeps `:analytics`'s own tests
fast and its surface clean.

Actually — reconsider: `:analytics` main would need `PersistedBattle`
at minimum to accept it as input. That's a small type, could live in
`:persistence` and be `api`-exposed. Or live in `:engine` (but
metadata belongs above engine). Decide at implementation: either
`:analytics` depends on `:persistence` via `api` (fine, `:analytics`
is already a consumer layer) or `PersistedBattle` lives in
`:analytics` and `:persistence` implements-by-returning it.

**Leaning: `PersistedBattle` in `:persistence` since that's who owns
the on-disk format; `:analytics` takes an `api` dep.**

### New optional: `:analytics-cli`

A thin entry point that reads a directory, runs aggregations, prints.
Not strictly necessary — the tests and an ad-hoc script cover the same
ground. Only creates this if `jar -jar analytics-cli.jar .battles/`
becomes a real workflow.

### Unchanged: everything else

`:engine`, `:render`, `:ai`, `:data`, `:data-ingestion`, `:cli`,
`:server` stay as-is except for:

- `:server` adds a `recorder: BattleRecorder? = null` constructor
  parameter. `BattleRecorder` interface is declared **in `:server`**
  (small interface — two methods), not `:persistence`. `:persistence`
  provides the implementation. `:server` has no dep on `:persistence`.
- `:cli`'s `PlayMain` / `DemoMain` gain the same optional parameter.

This follows the same shape as registries: interface in the consumer
module, implementation in the data / peripheral module, the consumer
module declares what it needs without depending on the implementer.

### Alternative considered: put `BattleRecorder` in `:engine` as a
seam

**Rejected.** The logic would be "events already flow through the
engine; let the engine broadcast them to a Recorder." But that makes
the engine know about recording as a concept, which is the same class
of leak as the rendering-in-engine smell diary 066 called out.
Consumers record; the engine doesn't broadcast. Keeping the recorder
at the session / loop level matches how `TextRenderer` consumes
events without the engine knowing about it.

## BattleMetadata — where each field comes from

```kotlin
data class BattleMetadata(
    val battleId: String,         // UUID generated at loop construction
    val startedAt: Instant,       // Clock.systemUTC() at loop start
    val endedAt: Instant,         // Clock.systemUTC() at result
    val formatTag: String,        // caller-supplied: "gen5ou", "custom-singles", etc.
    val protocolVersion: Int,     // from :server if server-driven; null for :cli
    val clientInfo: String?,      // caller-supplied tag
    // Deferred: player identity, rating, team hashes — diary 067 team-validator
    // seam will shape these.
)
```

**Where this class lives:** `:persistence`. All fields are
consumer-level, not engine-level. The entry point that constructs a
`BattleLoop` also constructs the metadata: `PlayMain` hardcodes a
default format tag; `ServerSession` derives it from the `team_set`
message (which today doesn't carry a format — future protocol
extension, maybe v2); `DemoMain` hardcodes "demo".

**Clock as an injection point, not a global.** `:persistence`'s
recorder takes a `Clock` parameter (default `Clock.systemUTC()`) so
tests can use a fixed clock and get deterministic timestamps.

## Collection path — when and how

Three collection shapes, pick when the forcing function arrives:

### Shape A: post-battle write (simplest)

`BattleLoop.run()` returns; the caller constructs `BattleMetadata`,
invokes `recorder.record(result, metadata)`. One file per battle.
Works for local play and demo.

**Cost:** ~30 lines in `:persistence`. Optional constructor param in
`:cli` / `:server`. This is probably what v1 looks like.

### Shape B: per-turn streaming

`ServerSession` tees every `turn_events` emission to an append-only
file. Writes incrementally during the battle. Useful if sessions are
long-running and you want partial observability (or if the JVM
crashes mid-battle and you want to not lose the log).

**Cost:** more plumbing — the recorder sees individual events rather
than a completed result. Roughly what Showdown's `battle-log.*` does.
Defer until Shape A isn't enough.

### Shape C: external sink (network / DB)

Swap `FileBattleRecorder` for `PostgresBattleRecorder` or
`HttpBattleRecorder`. The `BattleRecorder` interface doesn't change;
the implementation is swapped via DI at entry point construction.

**Cost:** a new recorder impl. `:persistence` stays the owner
regardless; the filesystem isn't the only backing store it might host.

## Forcing functions (explicitly not here yet)

None of these is present today. Naming them so we recognize them:

- **A web UI that displays usage stats.** "Rocky Helmet's win rate"
  needs aggregation over past games, which needs persistence + batch
  analysis.
- **An ML training corpus.** Save every AI-vs-AI battle; replay
  offline for training. Needs persistence (storage) + DTO round-trip
  (already have it) + enough metadata to partition training sets
  (format, version).
- **A tournament client.** Leaderboard needs per-player aggregates,
  which needs player identity. The team-validator row in diary 067 is
  the upstream prerequisite.

Any one of these forces Shape A at minimum. Without one, building
the pipeline is the speculation trap diaries 060 / 064 / 067 / 072 all
warn against.

## What v1 looks like, when forced

Minimal viable pipeline:

1. New `:persistence` module with `BattleMetadata`, `PersistedBattle`,
   `BattleRecorder` interface (or accept the interface-in-`:server`
   decision), `FileBattleRecorder`, `BattleLoader`.
2. `:cli`'s `DemoMain` wires a file recorder to a `./battles/` directory.
   `PlayMain` optionally the same.
3. `:analytics` gains one batch function over `Sequence<PersistedBattle>` —
   e.g. `aggregateMoveUsage` — plus its test.
4. Tests validate the round-trip: run 3 AI-vs-AI demo battles, read them
   back, aggregate move usage, assert the aggregated count matches the
   per-battle summaries added up.
5. Commit. Done.

Everything else defers. Shape B / Shape C / the CLI entry point /
player identity / format v2 all wait for their own forcing functions.

## What v1 deliberately does not ship

- **No `BattleId` / timestamp on `BattleResult`.** Stays in
  `BattleMetadata` in `:persistence`.
- **No broadcast-events pattern on `:engine`.** Recorder sits at
  session / loop level.
- **No database.** Filesystem is sufficient for tens of thousands of
  battles. Databases arrive when querying by complex predicates
  actually needs SQL.
- **No format registry beyond a string tag.** The format tag is a
  free-form label; structured format definitions wait for the
  team-validator seam (diary 067).
- **No player identity / rating.** Same — waits for the validator
  and whatever tournament integration forces it.
- **No incremental aggregations.** Batch recompute is cheaper than
  worth-the-complexity incremental maintenance at the target scale.

## Validation signal (when shipped)

- `./gradlew :persistence:test` exercises record → read → equal.
- `./gradlew :analytics:test` exercises `aggregateMoveUsage` over a
  multi-battle corpus.
- `:engine` and `:render` and `:ai` have **no** new dependencies.
  Verifiable via `git diff`.
- `BattleResult` has no new fields. Verifiable via `git diff`.
- The `EngineImmutabilityInvariantTest` stays green (no new `var` at
  class or top level in `:engine`).

## Related

- **Diary 042** — "the event log is a first-class data asset." This
  diary is the operationalization at batch scale.
- **Diary 060** — DTO split. Without that layer, `PersistedBattle`
  wouldn't round-trip. Prerequisite already met.
- **Diary 065 / 066 / 068 / 071** — the refactor wave that established
  the layering discipline this diary commits to upholding.
- **Diary 067** — the forcing-function catalog. Team validator,
  replay versioning, and "frontend and server as separate
  repositories" are all adjacent concerns; none are blockers for
  Shape A.
- **Diary 073** — KMP / non-JVM audit. `:persistence`'s file I/O
  would need an `expect class` abstraction if we ever go
  multiplatform; flagged here as a future consideration, not a blocker.

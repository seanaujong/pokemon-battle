# Diary 081: Industry comparison — offline batching, event storage, and analytics

**Date:** 2026-04-14
**Status:** Reference / orientation. No code changes. Maps our pipeline
against industry-standard shapes so we can borrow vocabulary and
recognize forcing-function thresholds earlier.

## Why this diary exists

Pokemon Showdown has been our mechanics reference since diary 066. For
batch processing, event storage, and analytics, we've had no such
compass — which means we've been making local decisions (JSONL files,
`:persistence` module, `BattleCorpus` folds) without naming the
industry shapes they correspond to.

The user's framing: *"Not that we have to do all that setup but surely
the comparisons will guide us."* That's the move. Map it.

## What we have today (lens for comparison)

- **Storage:** one pretty-printed JSON file per battle, flat directory,
  filesystem.
- **Schema:** `PersistedBattle` (metadata + list of `PersistedTurn`),
  each turn a list of `BattleEventJson`. Versioned implicitly via
  `protocolVersion` (diary 069).
- **Collection:** `BattleRecorder` interface; `FileBattleRecorder`
  writes at battle end. Optional per session.
- **Query:** `BattleCorpus` Kotlin functions — `Sequence`-based folds
  over `BattleLoader.loadAll(dir)`.
- **Orchestration:** `:cli:batchDemo` / `:cli:matrixEval` Gradle tasks
  drive N battles synchronously in one JVM; `:server` records live
  battles when env var is set.
- **Scale:** hundreds of battles today; thousands easily; tens of
  thousands without strain.

## Industry shapes, mapped

### 1. File-per-record + folder-as-table — *what we have*

**Industry analog:** line-delimited JSON (JSONL / NDJSON) in
`s3://bucket/topic/date=2026-04-14/*.json`. Deliberate simplicity
chosen by data-engineering shops at all scales before specific scale
problems bite. Readable by grep, ingestable by every tool.

**We're using it.** No change needed.

### 2. Columnar file formats (Parquet, ORC, Arrow)

**What it solves:** analytical queries ("avg turns per month") on
row-oriented data are I/O-bound on JSON — you parse every byte of
every event to count wins. Columnar formats store `winner`, `turns`,
`formatTag` contiguously so aggregations skip irrelevant fields.

**When we'd need it:** ~100K+ battles AND queries that only touch a
few fields of each. We're 2-3 orders of magnitude below that. The
conversion step is a ~50-line Kotlin job when forced.

**What would force it:** a cron that nightly aggregates all battles in
`./battles/` and the aggregation takes minutes instead of seconds.

### 3. Columnar OLAP engines (DuckDB, ClickHouse, Polars)

**What it solves:** SQL (or SQL-like) over the columnar files above
without standing up a warehouse. DuckDB in particular can
`SELECT COUNT(*) FROM read_json('./battles/*.json', format='array')`
directly against our JSON today, right now, no conversion.

**Interesting observation:** this is a free capability that we could
"shell into" from the analytics CLI later. A contributor could drop
`./battles/` into DuckDB and run ad-hoc queries we haven't anticipated
— the JSON format is the contract. Worth remembering when someone
asks "can I query X" and we haven't built `BattleCorpus.X`.

**When we'd build it into the pipeline:** not until `BattleCorpus`
grows to 15+ aggregation functions and the cost of adding one more
outweighs pointing users at DuckDB.

### 4. Event streaming (Kafka, Kinesis, Pulsar)

**What it solves:** multiple producers writing events and multiple
consumers reading them concurrently, with decoupled retention and
replay. Our `:server` is a single producer writing to disk; a single
consumer (`BattleCorpus`) reads at leisure.

**Where the comparison helps:** our wire protocol's `turn_events`
messages *are* an event stream in Kafka-terminology. If we ever
needed two live consumers (live UI + analytics hot path, say), we'd
think in Kafka's shape — partition by battle id, consumer groups,
offsets. Today we're simpler than that, correctly.

**Nearest Showdown parallel:** their `battle-stream.ts` is a per-battle
async iterator that multiple consumers can subscribe to. That's one
battle's events, not a cross-battle stream — single-producer,
multi-consumer *within one battle*. We could build the same shape
(diary 070's multiplexing direction) but don't need to.

### 5. Table formats with schema evolution (Delta Lake, Iceberg, Hudi)

**What it solves:** your schema changes (new fields, renamed columns)
without breaking readers. Time travel: "give me the data as of last
Tuesday." Atomic multi-file writes.

**Where we intersect:** diary 076 removed `DamageDealt.critical`; we
handled schema evolution by atomic commits across all readers. Delta
Lake's automated handling of this is overkill when you control every
reader. **But** if we ever ship a Python client that third parties
run against saved replays, forward-compat becomes real. That's the
point the diary 069 `protocolVersion` field stops being ceremonial.

**Our closest stand-in today:** the DTO layer (diary 060). Domain
types evolve without the on-disk format moving; the DTO is the
stability contract. Conceptually it's a thin Iceberg — one schema, we
control migrations.

### 6. Orchestration (Airflow, Dagster, Prefect, Argo)

**What it solves:** scheduled jobs, dependencies between jobs,
retries, observability. A pipeline that runs nightly, aggregates
yesterday's battles, pushes a report.

**We don't have any of it.** We have `:cli:batchDemo` invoked by a
human. If we ever want "run every night, update the dashboard,"
that's the forcing function.

**Zero-infrastructure start:** cron + shell script calling `./gradlew
:cli:matrixEval`. Works up to the point where observability/retries
actually matter.

### 7. Experiment tracking (Weights & Biases, MLflow, ClearML)

**What it solves:** "did this change to TypeAI produce a better
version?" tracks runs, compares metrics, surfaces diffs. Our matrix
eval is pointing at this need already — `BattleSummary.criticalHits`
compared across two versions of the engine would benefit.

**Where it clicks:** when we have 3+ AI strategies or 3+ engine
versions and want to eyeball a leaderboard. Today we have 2
strategies and one engine version, so a 2x2 table is enough.

**Nearest analog we already have:** `scripts/smoke-test-external-client.py`
+ matrix runner produce structured output. One script away from
"append this run to a CSV" for a baseline tracker.

### 8. ML training environments (OpenAI Gym, PettingZoo)

**What they provide:** a standardized `observation, action, reward,
done` interface so RL algorithms don't know about the underlying
game. PettingZoo extends to multi-agent (which is what Pokemon is).

**What our engine already has:** `BattleState` (observation), `TurnChoice`
(action), `BattleEvent` stream (reward signal can be derived:
damage dealt, KOs, win), `isDefeated` (done signal). The wire
protocol (diary 069) exposes all of it to Python already.

**Gap:** no `gym.Env`-shaped Python wrapper. A ~200-line Python class
that wraps `smoke-test-external-client.py` as a Gym env is real ML
infrastructure, not just scaffolding. High-value forcing function if
ML training is ever a direction.

### 9. Game replay systems (Showdown replays, Lichess PGN,
chess.com)

**What they provide:** URL-addressable compressed match logs, often
with replay UI. Chess's PGN is a standard notation; Showdown stores
compressed protocol text.

**Where we are:** `FileBattleRecorder` produces URL-addressable blobs
(one file per battle, named by UUID). A web UI that renders a
`PersistedBattle` as a playable animation is the next step — and it's
the Kotlin/Multiplatform question from diary 073.

## Architectural insights from the comparison

Five observations worth keeping:

1. **JSON-per-battle + folder-as-corpus puts us in good company.** Before
   data shops build Parquet pipelines, they often live in this shape for
   years. Optimizing prematurely would mean we can't grep our own data.

2. **Our DTO layer (diary 060) is the thin version of Iceberg's schema
   evolution layer.** When a reader encounters a field it doesn't know
   about (say an older client reading a newer file), the DTO decoders
   can be extended to handle it. We don't formalize this yet because
   we control every reader — but if external readers appear, Iceberg's
   "optional fields are fine; renames are not" discipline is the one
   to copy.

3. **Our event stream is already Gym-shaped.** `BattleState` → observation,
   `TurnChoice` → action, `BattleEventJson` → reward signal, `Result`
   → done. The wire protocol exposes all four to a non-JVM process.
   Any RL framework can wrap the JSONL client with zero engine
   changes. The constructed forcing function that would demonstrate
   this is real: one Python Gym wrapper class, one minimal trainer.
   Diary 073 flagged this obliquely; the comparison makes the
   architectural claim concrete.

4. **We have no orchestration layer, and that's fine.** Airflow /
   Dagster are solutions for problems we don't have (multi-step
   dependency graphs, retries, scheduling). A shell loop over gradle
   tasks will carry us far. When we need it, Dagster's "software-defined
   assets" model (the output of one job is the input of another) maps
   best to our Kotlin-first story — not Airflow's DAG-of-operators.

5. **DuckDB is a free query capability we're not advertising.** Any
   contributor can point DuckDB at `./battles/*.json` today and ask
   questions we haven't built `BattleCorpus` functions for.
   `add-analytics-query.md` skill doc could mention this as the
   "have you tried DuckDB first?" escape hatch before writing a new
   aggregation function.

## Forcing-function thresholds (calibrated by the comparison)

- **Convert to Parquet:** cross 50K battles *and* have recurring
  queries that scan whole corpus.
- **Schema evolution as a real contract:** first external (non-repo)
  reader appears.
- **Event streaming infrastructure:** need concurrent live consumers
  beyond "one logger writing to a file."
- **Orchestration:** first scheduled job we'd miss if the laptop was off.
- **Experiment tracking:** third AI strategy AND we care about trending
  aggregate metrics across engine versions.
- **Gym wrapper:** first RL training loop (arguably already close to
  worth building as a small showcase, per finding #3 above).

Below any threshold, we're fine. The comparison's real value is
telling us what's **ahead of us** without pressuring us to build it
early.

## Concrete small moves informed by this

These are cheap and borrow from the comparison without committing to
infrastructure:

1. **Name the JSON "corpus format" in a tiny spec doc.** Analogous to
   Parquet having a spec file, not just a library. Prevents drift if
   a second reader appears. `docs/corpus-format.md`. ~15 minutes.

2. **Document DuckDB as a query escape hatch** in a `:analytics`
   README or a new `add-analytics-query.md` skill doc. "Before adding
   a new `BattleCorpus` function, try this DuckDB one-liner." Same
   principle as the `@Suppress` with rationale — point users at the
   cheap answer before committing to code.

3. **Seeded matrix eval script.** Diary 079's matrix uses `Random(seed)`
   but the seeds aren't printed. Printing them as part of metadata
   would make every matrix run exactly reproducible — the
   experiment-tracking shape from finding #3. ~10 lines.

4. **Sketch a `gym.Env` wrapper** as an experimental script (not
   shipped) to see how close the architecture really is. Gap analysis,
   not infrastructure. ~1 hour.

None of these is load-bearing. Ship if motivated, skip if not.

## Related

- **Diary 042** — original analytics framing.
- **Diary 060** — DTO split (our thin Iceberg).
- **Diary 069** — wire protocol (our Gym-adjacent observation/action
  surface).
- **Diary 070** — multiplexing (our Kafka-adjacent question if it
  ever becomes real).
- **Diary 073** — KMP / non-JVM target (where the Gym wrapper and
  replay UI conversations would land).
- **Diary 078** — analytics pipeline v1 (what this comparison is
  pointing at).
- **Diary 079** — constructed forcing functions (the doctrine that
  lets us pick from the thresholds above).
- **Diary 080** — `:server` recording (the "multi-source corpus" piece
  this comparison implicitly evaluates).

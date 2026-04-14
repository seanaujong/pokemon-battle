# Diary 083: Matrix-eval tidies + corpus format spec

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Two of diary 082's retroactive findings were flagged as "not urgent":

- Matrix eval hardcodes `"TypeAI"` / `"RandomAI"` as strings in two
  places — a typo in one would produce "Unknown strategy" at runtime.
- The random seeds driving each battle aren't recorded anywhere, so
  "run this battle again and get the same result" requires re-reading
  the runner source to reconstruct the seed formula.

Plus one of diary 081's "concrete small moves informed by industry
comparison":

- The corpus format has no spec doc. Parquet has one; we should too.

All three are cheap. Shipping as one diary.

## What changed

### `Strategy` enum in `:cli`

Replaces the hardcoded strings in `MatrixEvalMain` with a sealed enum.
Adding a new strategy is a single enum value + a single `when` branch;
the compiler catches typos and exhaustiveness.

```kotlin
enum class Strategy { TypeAI, RandomAI }
```

The `playerTags` on persisted metadata continues to use `.name` as the
string key — the *wire* / on-disk format is stable strings, the
*runtime* is typed. Same pattern as `BattleEventJson` (typed
internally, string discriminator externally).

### Seed recording

`matrixEval` runs now write `clientInfo = "seed=$seed"` on every
persisted battle. The `seed` is the exact value passed to `Random()`
for that battle's `RandomAI`. Dropping a `battleId`'s file into a
replay harness with its recorded seed reproduces the battle
bit-for-bit.

TypeAI is deterministic — the seed doesn't influence it — but the
seed is still recorded so mixed-matchup battles and any future
seed-dependent strategy are handled uniformly.

### `docs/corpus-format.md`

Spec for the on-disk format. Covers field meanings, optional vs
required, versioning discipline, DuckDB escape-hatch queries,
deferred-capability thresholds. Diary 081's "name the JSON corpus
format in a tiny spec doc" move.

Explicit non-goal: the doc does not prescribe indexes, partitioning,
or compression. It describes what we have today, with pointers to
when each would be forced.

## Code review

### Diagnostics

- *Testable:* the `Strategy` enum is structurally trivial; `printMatchupMatrix`
  is exercised by the matrix runner end-to-end. Not a formal unit test —
  the matrix runner is the integration test. Acceptable for a CLI
  entry point.
- *Readable:* replacing `when (strategy: String)` with `when (strategy: Strategy)`
  removed the `else -> error(...)` arm (exhaustive over enum). Clearer.
- *Layer:* `Strategy` lives in `:cli` because strategies are a deploy-time
  concept (what AI implementations are exposed as entry points).
  Considered `:ai` but rejected — `:ai` is the library; `:cli` is the
  catalog of what gets shipped in which entry point. Same pattern as
  `GenVRegistries` in `:data` — the library doesn't dictate the
  deploy-time bundle.
- *Auditable:* every battle file now carries `clientInfo = "seed=N"`.
  `grep clientInfo battles/*.json` is the audit.
- *Happy path:* enum → typed call → exhaustive `when`.
- *Failure modes:* a new strategy without a `when` branch is a compile
  error. Good. A battle file missing `clientInfo` still parses (the
  field is optional) — that's intentional for non-seeded runs.
- *Illegal state:* `Strategy` is closed; can't construct an unknown
  value. The previous string API allowed `"Typo"` to fall through to
  `error("Unknown strategy")` at runtime.
- *Invariants:* `playerTags` keyed by `Side.name` is now asserted by
  convention across matrix runner, analytics aggregations, and the
  format spec doc. Not yet tested mechanically (diary 082 flagged
  this); adding a test would be one line. Not done in this diary
  because the convention is single-source in the format doc and the
  enum is the one place that constructs these keys.
- *Duplicated logic:* none introduced.
- *Mutation:* none; the existing local accumulators are unchanged.
- *Names:* `Strategy` matches the domain. `clientInfo` is overloaded
  (server info + seed) but the free-form intent is documented in
  `BattleMetadata`.
- *Layer-blurring:* none.
- *Removal:* deleting `Strategy` reverts to a string enum. Low cost to
  reverse.
- *Other:* docs/corpus-format.md deliberately notes where it drifts
  from the code ("if this disagrees with `PersistedBattle.kt`, the
  source wins"). Makes drift visible to future readers.

### Industry comparison

- **Seed recording** is the standard shape for reproducible ML
  experiments (W&B, MLflow, DVC, Hydra all record them as run
  metadata). We're hand-rolling because we have one runner, not
  because the industry standard is wrong.
- **Typed strategy enum** is the same move every ML framework makes
  between "a string argument" and "a registered class" (think
  `stable-baselines3`'s `PPO` / `A2C` / `DQN` classes vs argument
  strings). The enum is our tiny version.
- **Format spec doc** puts us in the same posture as Parquet, Avro,
  Arrow, and every file-based data format. The spec is the contract
  consumers depend on; the implementation is the private detail.
  Deferring schema-evolution machinery (Iceberg, Delta) is the
  correct choice at our scale (diary 081 rationale).

No architectural departures from industry norms. Deliberate scale
downshift on machinery (no experiment tracker, no schema evolution
framework) is documented in diary 081.

### Findings to fix

None urgent. The `Side.name`-as-key test is still worth adding when
we next touch the `playerTags` code — still filed from diary 082.
Keeping it filed rather than fixing now because the format doc now
names the convention, which is the cheaper enforcement than a test.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `rm -rf battles && ./gradlew :cli:matrixEval --args="10" -q` produces
  40 battle files, each with `"clientInfo": "seed=N"` on the metadata.
- `DiaryConventionTest` passes (this diary has the `## Code review`
  section).

## Related

- **Diary 082** — findings that drove two of the three ships here.
- **Diary 081** — industry comparison that drove the spec doc.
- **Diary 079** — original matrix eval; Strategy enum is the "would
  rewrite if doing it again" point that diary 079's review noted.
- **Diary 076** — schema evolution precedent referenced in the corpus
  spec.
- **`docs/corpus-format.md`** — the new spec.

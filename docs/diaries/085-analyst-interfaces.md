# Diary 085: Analyst interfaces — SQL cookbook + Kotlin-script isolation test

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

After diary 084 shipped the 3×3 matrix, the user asked: *"would a data
analyst like using something like SQL queries or Kotlin-based Jupyter
notebooks for this kind of work?"*

The answer: yes, both — and the architecture already supports both
because of the layering discipline from diaries 078 / 081 / 083. But
"supports" is a claim, not a proof. This diary constructs the forcing
function that tests the claim.

## What was tested

### Claim 1: DuckDB can query our JSON corpus with zero translation

**Test:** `pip install duckdb` (in a repo-local venv), write a SQL
query that reproduces the matchup matrix, run it, compare output to
`:cli:matrixEval`'s print.

**Result:** identical. The 3×3 matrix SQL produced exactly the same
numbers as the Kotlin matrix runner — TypeAI 100% side 1, RandomAI vs
HeuristicAI 4/15, etc. Zero Kotlin code involved; DuckDB read
`battles/*.json` directly.

### Claim 2: `:persistence` is independently consumable from Maven Local

**Test:** configure `maven-publish` on `:engine` and `:persistence`,
run `./gradlew publishToMavenLocal`, write a `.main.kts` Kotlin script
that `@file:DependsOn(...)`s the artifacts, and execute it against
the matrix corpus. Success iff the script runs without the multi-
module build being involved.

**Result:** `scripts/analyst-query.main.kts` loaded 135 battles and
printed per-strategy win rates:

```
Loaded 135 battles from …/battles

Win rate when playing as side 1:
  RandomAI       :  17 /  45 (37.8%)
  HeuristicAI    :  43 /  45 (95.6%)
  TypeAI         :  45 /  45 (100.0%)

Average turns per battle:
  matrixEval          : 7.2
```

The numbers (17 / 43 / 45 summed from the 3×3 matrix's side-1 column
totals) match byte-for-byte. **Isolation is empirically real** —
someone with a Kotlin CLI and access to Maven Local artifacts
consumes the pipeline without cloning the project.

### What the architecture actually enforced

Three properties, each carried from a prior diary, combined to make
this painless:

- **`:analytics` is I/O-free** (diary 078) — it eats a `Sequence<PersistedBattle>`
  and couldn't care where the sequence came from. DuckDB-SQL, script-
  loaded files, hand-built test fixtures all work interchangeably.
- **On-disk format is specced JSON** (diary 083) — DuckDB's
  `read_json('battles/*.json')` works with zero translation because
  the fields are named and typed consistently.
- **`:persistence` `api`-exposes `:engine`** (diary 071 pattern) —
  consumers of `:persistence` see `Side`, `BattleResult`, etc.
  transitively without needing to know the module graph.

Each one of those properties was forced into place by a prior
session's forcing function. The cumulative payoff is that adding an
analyst interface took ~30 minutes, not days.

## What shipped

1. **`scripts/analyst-env/`** — venv for DuckDB. Gitignored via
   `scripts/.gitignore`.
2. **`docs/corpus-queries.md`** — SQL cookbook: 6 worked queries
   covering wins-by-side, matchup matrix, battle length, turn-limit
   stalls, battle reproducibility, and event-level move usage.
3. **`maven-publish` on `:engine` and `:persistence`** — one block per
   `build.gradle.kts`. Runs via `./gradlew :engine:publishToMavenLocal
   :persistence:publishToMavenLocal`.
4. **`scripts/analyst-query.main.kts`** — reference Kotlin script
   using the published artifacts. Can be repurposed for Kotlin
   Jupyter by dropping the `@file:DependsOn` directives in favor of
   the notebook kernel's `%use` machinery.

## Code review

### Diagnostics

- *Testable:* the queries and the script are both "run it and
  compare the output." Not unit tests, but both were exercised
  against the real corpus during this diary's work — matches the
  pattern of `BatchDemoMain`'s integration testing.
- *Readable:* SQL cookbook uses DuckDB's native JSON path operators
  (`metadata->>'playerTags'->>'SIDE_1'`). Slightly verbose but
  self-explanatory.
- *Layer:* no new code in `:engine`, `:render`, `:ai`. `:persistence`
  and `:engine` gained `maven-publish` — a publishing concern, not a
  runtime one.
- *Auditable:* the `corpus-queries.md` doc reproduces the matrix
  from the matrix runner, which is the end-to-end audit.
- *Happy path:* analyst needs (a) the repo checkout OR Maven Local,
  (b) DuckDB XOR Kotlin CLI, (c) a corpus in `battles/`. All three
  pieces documented.
- *Failure modes:* the `.main.kts` script requires `kotlin` CLI on
  `PATH` — noted in the script header. If Maven Local is empty, the
  `@file:DependsOn` resolution fails loudly. If the corpus dir is
  empty, `BattleLoader.loadAll` returns an empty sequence (tested
  in diary 078). All visible failures, no silent ones.
- *Duplicated logic:* the SQL cookbook's matchup-matrix query
  duplicates the logic of `BattleCorpus.matchupWinRates`. Intentional
  — they're in different languages for different audiences. If the
  Kotlin implementation changes, the doc will drift; when that
  happens, re-run both, compare, update the doc with a test line.
- *Illegal state:* none introduced.
- *Invariants:* the `playerTags` map key convention (`Side.name`
  string) is baked into both the SQL cookbook and the script. Still
  not mechanically enforced (flagged in diary 082); shipping this
  diary made the convention more load-bearing, so a test earns its
  keep now.
- *Removal:* deleting `docs/corpus-queries.md` and the script
  reverts to "analytics only via `BattleCorpus` Kotlin calls" — no
  damage to the live pipeline. `maven-publish` blocks are also
  independently removable.
- *Other:* the `analyst-env/` venv is a new persistent-state item at
  the repo root. Gitignored. If a contributor deletes it, the
  cookbook's `setup` section tells them how to recreate.

### Industry comparison

- **DuckDB-over-files** is exactly how modern data engineering
  teaches "analytics on a laptop" — duckdb.org's docs and the "Big
  Data Is Dead" meme both argue that for tens-of-thousands-of-rows
  workloads, columnar-over-files eats the classical warehouse's
  lunch. We're below even their threshold; the one-liner approach is
  defensible.
- **Maven Local for notebook dependencies** is how the JetBrains
  Kotlin Jupyter stack works out-of-the-box (`%use @file:DependsOn`
  / `%maven` directives). Our `.main.kts` file is nearly translatable
  to a notebook cell by deleting the directives and pasting into a
  Jupyter-Kotlin kernel.
- **SQL + Kotlin-script hybrid** is what the JVM-data-engineering
  community converged on over the last three years: Spark SQL +
  Kotlin/Scala for the heavy lifts, DuckDB for exploration. We
  deliberately bypass Spark because we're at the scale where Spark
  setup cost outweighs benefit — single-laptop DuckDB is faster to
  iterate on.
- **What we're NOT shipping:** an OLAP star schema (overkill), a
  semantic layer (dbt / LookML — overkill), a BI tool integration
  (Superset / Metabase — good fit eventually, out of scope for v1).
  Diary 081 noted the thresholds for each.

### Findings to fix

One real, minor:

- The SQL cookbook query #6 relies on the full DTO class name
  (`…MoveAttemptedJson`) in the event `type` discriminator. That's
  stable today because we haven't added `@SerialName` to event
  variants, but it's a latent dependency. If we ever add short names
  to event DTOs (for a wire protocol optimization, say), the SQL
  breaks silently. **Mitigation:** the corpus-format spec now names
  this as a "current property of the on-disk format"; if it changes,
  the spec changes with it. Acceptable; flagged.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `./gradlew :engine:publishToMavenLocal :persistence:publishToMavenLocal`
  produces jars + POMs + Gradle module metadata in
  `~/.m2/repository/com/pokemon/battle/`.
- `python3 -c "import duckdb; ..."` (via `scripts/analyst-env/`)
  reproduces the 3×3 matrix from raw JSON.
- `kotlin scripts/analyst-query.main.kts` loads 135 battles and prints
  matching win totals.
- `DiaryConventionTest` passes (this diary has the `## Code review`
  section).

## Related

- **Diary 078** — pipeline v1; `:analytics` I/O-freedom came from here.
- **Diary 081** — industry comparison; DuckDB-as-escape-hatch was
  proposal there, concrete now.
- **Diary 083** — corpus-format spec; both analyst paths depend on it.
- **Diary 084** — 3×3 matrix that produced the corpus these queries
  run against.
- **`docs/corpus-queries.md`** — the SQL cookbook.
- **`scripts/analyst-query.main.kts`** — the isolation-test script.

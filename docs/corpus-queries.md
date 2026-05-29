# Corpus queries cookbook

Before writing a new `BattleCorpus` aggregation function, check whether
a two-line DuckDB query covers your question — the DuckDB-as-escape-hatch
discipline; this doc is the catalog of worked examples.

All queries below run against `battles/*.json` — the persisted-battle
JSON format. Written and tested against DuckDB 1.5.2 (the version in
`scripts/analyst-env/`).

## Setup

```bash
# One-time, from repo root:
python3 -m venv scripts/analyst-env
source scripts/analyst-env/bin/activate
pip install duckdb
```

Then for each query session:

```bash
source scripts/analyst-env/bin/activate
python3  # or `python3 -c "import duckdb; ..."` for one-liners
```

DuckDB's CLI (`duckdb`) works identically if you prefer a REPL — the
Python `duckdb` library and the CLI share the same engine.

## Queries

### 1. Win count by side

```python
import duckdb
duckdb.execute("""
    SELECT winner, COUNT(*) AS n
    FROM read_json('battles/*.json')
    GROUP BY winner
    ORDER BY n DESC
""").fetchall()
```

Answers "who's winning in aggregate." `winner` is `NULL` for draws / turn-
limit.

### 2. Matchup matrix (reproduces `:cli:matrixEval` output)

```python
duckdb.execute("""
    SELECT
      metadata->>'playerTags'->>'SIDE_1' AS side1,
      metadata->>'playerTags'->>'SIDE_2' AS side2,
      COUNT(*) FILTER (WHERE winner = 'SIDE_1') AS s1_wins,
      COUNT(*) AS n,
      ROUND(100.0 * COUNT(*) FILTER (WHERE winner = 'SIDE_1') / COUNT(*), 1) AS s1_pct
    FROM read_json('battles/*.json')
    GROUP BY 1, 2
    ORDER BY 1, 2
""").fetchall()
```

Per-matchup side-1 win rate. Exact reproduction of what
`BattleCorpus.matchupWinRates` computes, without any Kotlin code.

### 3. Average battle length by format

```python
duckdb.execute("""
    SELECT
      metadata->>'formatTag' AS format,
      AVG(array_length(turns)) AS avg_turns,
      COUNT(*) AS n
    FROM read_json('battles/*.json')
    GROUP BY 1
""").fetchall()
```

Useful for format comparisons when the corpus mixes
`matrixEval` / `server-live` / `batchDemo-…` runs.

### 4. Battles that hit the turn limit

```python
duckdb.execute("""
    SELECT metadata->>'battleId' AS id, array_length(turns) AS turns
    FROM read_json('battles/*.json')
    WHERE winner IS NULL
    ORDER BY turns DESC
""").fetchall()
```

Returns draws / turn-limit battles. If `turns` equals the runner's
`maxTurns`, it's a stall; investigate the battle file for pathological
behavior.

### 5. Reproduce a specific battle

```python
duckdb.execute("""
    SELECT metadata->>'battleId' AS id, metadata->>'clientInfo' AS info
    FROM read_json('battles/*.json')
    WHERE winner = 'SIDE_2' AND metadata->>'playerTags'->>'SIDE_1' = 'TypeAI'
    LIMIT 5
""").fetchall()
```

Seeds are recorded in `clientInfo`; feed the seed back into a matrix
runner with matching side tags and you reproduce the exact battle
bit-for-bit.

### 6. Move usage in winning battles only

```python
duckdb.execute("""
    SELECT
      event.value->>'move'->>'name' AS move_name,
      COUNT(*) AS uses
    FROM read_json('battles/*.json') AS battle,
         UNNEST(battle.turns) AS turn,
         UNNEST(turn.events) AS event
    WHERE battle.winner = 'SIDE_1'
      AND event.value->>'type' LIKE '%MoveAttemptedJson'
    GROUP BY 1
    ORDER BY uses DESC
    LIMIT 10
""").fetchall()
```

Event-level query showing which moves the winning side actually used.
Nested `UNNEST`s expand the turn → events arrays. The full DTO type
name (`…MoveAttemptedJson`) in `type` is a current property of the
on-disk format.

## When to lift to `BattleCorpus`

A DuckDB query graduates to a Kotlin function when:

- It's run often enough to belong in the test suite.
- It's part of a report you ship (not a one-off exploration).
- The logic becomes complex enough that SQL obscures the intent.

Until then, DuckDB is cheaper. The persisted-battle JSON format is the
contract both paths depend on.

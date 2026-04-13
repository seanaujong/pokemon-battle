# Diary 013: Data Layer — Species and Move Database

**Date:** 2026-04-13
**Status:** Complete

## Goal

Load species and move data from CSV files into lookup maps. Tests and the future CLI/AI use `pokedex["Charizard"]` instead of hardcoding base stats.

## Design

### File format

CSV — human-readable, easy to edit, no dependencies. Two files:

**`data/species.csv`:**
```
name,type1,type2,hp,attack,defense,sp_attack,sp_defense,speed
Charizard,FIRE,FLYING,78,84,78,109,85,100
Venusaur,GRASS,POISON,80,82,83,100,100,80
```

`type2` is empty for mono-type Pokemon.

**`data/moves.csv`:**
```
name,type,category,power,priority,target
Flamethrower,FIRE,SPECIAL,90,0,ONE_OPPONENT
Swords Dance,NORMAL,STATUS,0,0,SELF
Earthquake,GROUND,PHYSICAL,100,0,ALL_OTHER
```

### Species: CSV (pure data)

Species have no behavioral component — just stats and types. CSV is the right format:
human-readable, editable, parseable by analytics tools.

### Moves: Kotlin definitions (data + behavior)

Moves have effects (recoil, stat boosts, status infliction) that are typed code — sealed
classes with parameters. Splitting base data to CSV and effects to code scatters one
concept across two files. Instead, moves are defined as Kotlin objects in `MoveDex`,
keeping each move's definition colocated and type-safe.

A `MoveDex.all()` function returns all registered moves. A formatter can export them
to a readable text file if non-programmers need a reference.

### Loader

```kotlin
object Pokedex {
    fun load(speciesCsv: InputStream): Map<String, Species>
}

object MoveDex {
    val all: Map<String, Move>  // Kotlin-defined, no loading needed
}
```

Species load from classpath resources. Fail fast on parse errors. Moves are
compile-time constants.

### Name on Species

`Species.name` stays as an identifier for debugging. The CSV key matches it. The engine never branches on it. The renderer reads it for display.

## Plan

### Step 1: Species CSV
- [ ] `src/main/resources/data/species.csv` with ~10 species
- [ ] Include all species used in existing tests (Charizard, Venusaur, Infernape, Swampert)

### Step 2: Pokedex loader
- [ ] Parse CSV into `Map<String, Species>`
- [ ] Handle mono-type (empty type2)
- [ ] Fail on unknown types or malformed rows
- [ ] Test: load CSV, verify species match expected values

### Step 3: MoveDex
- [ ] Kotlin object with move definitions
- [ ] `all: Map<String, Move>` for lookup
- [ ] Include all moves used in existing tests
- [ ] Test: verify move definitions are correct

### Step 4: Integration test
- [ ] Build a full battle from Pokedex/MoveDex lookups
- [ ] Existing 66 tests remain unchanged (inline definitions are fine for unit tests)

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | Species CSV with 20 species | PASS |
| 2 | Pokedex loader (3 tests) | PASS |
| 3 | MoveDex with 14 moves (4 tests) | PASS |
| 4 | Integration: battle from database (1 test) | PASS |
| All | 74 tests (66 existing + 8 new), 0 failures | PASS |

## Decisions resolved

- **Pokedex lives in `com.pokemon.battle.data`** — loading/parsing concern, not model or engine.
- **Existing tests keep inline species** — synthetic species ("Fast", "Balanced") are clearer for unit tests. Database is for integration tests and the application layer.

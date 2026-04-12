# Diary 001: Project Setup and Core Types

**Date:** 2026-04-12
**Status:** Complete

## Goal

Bootstrap the Gradle project and implement the foundational types from the architecture doc. By the end of this iteration, we should be able to compile the core data model and run a test.

## Questions (answered)

1. **Build system?** Gradle with Kotlin DSL — set up by modeling from a fresh IntelliJ-generated Gradle project.
2. **Kotlin/JVM versions?** Kotlin 2.2.10, JVM 17 (toolchain), Gradle 8.14.
3. **Package name?** `com.pokemon.battle`
4. **Test framework?** kotlin-test with JUnit Platform (comes from `testImplementation(kotlin("test"))`).

## Plan

### Step 1: Gradle project setup (done)
- [x] `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- [x] Gradle wrapper (8.14)
- [x] `.gitignore` updated for Gradle
- [x] `.idea/` config updated for Gradle integration
- [x] Standard `src/main/kotlin`, `src/test/kotlin` directory structure
- [x] `./gradlew build` passes

### Step 2: Core data types (done)
Implemented in `src/main/kotlin/com/pokemon/battle/`:
- [x] `Type.kt` — full 18-type enum with sparse effectiveness chart
- [x] `Species.kt` — base stats definition
- [x] `Pokemon.kt` — species + level
- [x] `Stats.kt` — stat calculation (Gen V+ formula, no IVs/EVs), `StatStages`, stage multiplier
- [x] `StatusCondition.kt` — enum (Burn, Poison, Paralysis, Sleep, Freeze)
- [x] `Volatile.kt` — sealed interface (Flinch, Confusion, Protect)
- [x] `PokemonState.kt` — in-battle state with `isFainted` and `maxHp` helpers
- [x] `FieldState.kt` — weather enum + field state
- [x] `BattleState.kt` — immutable snapshot with `pokemonFor`/`withPokemon` helpers
- [x] `Move.kt` — move definition (name, type, power, category, priority)
- [x] `Player.kt` — P1/P2 enum with `opponent()` extension

### Step 3: Event and phase framework (done)
- [x] `BattleEvent.kt` — sealed interface with `apply(state): BattleState`
- [x] Event subclasses: `MoveOrderDecided`, `MoveAttempted`, `MoveFailed`, `DamageDealt`, `PokemonFainted`, `StatusApplied`, `StatusDamage`, `WeatherDamage`
- [x] `Phase.kt` — fun interface
- [x] `TurnPipeline.kt` — nested fold orchestration
- [x] `TurnChoices.kt` — sealed `TurnChoice` with `UseMove`

### Step 4: First test — Charizard vs Venusaur (done)
- [x] `DamageCalc.kt` — Gen V+ formula with injectable roll, STAB, burn penalty
- [x] `MoveOrderPhase` — priority brackets, then speed comparison
- [x] `MoveExecutionPhase` — damage calc, type effectiveness, faint check, skip fainted
- [x] `EndOfTurnPhase` — no-op stub
- [x] `CharizardVsVenusaurTest` — 4 tests, all passing

## Validation

| Step | Validation | Result |
|------|-----------|--------|
| 1 | `./gradlew build` succeeds | PASS |
| 2 | `./gradlew compileKotlin` succeeds | PASS |
| 3 | `./gradlew compileKotlin` succeeds | PASS |
| 4 | `./gradlew test` — 4 tests pass | PASS |

## Design decisions made

- **Player identity in events:** Used a `Player` enum (P1/P2) instead of `Pokemon` object references. `BattleState.pokemonFor(player)` and `withPokemon(player, state)` provide clean lookup/update. This works well with immutable state.
- **Damage formula randomness:** The `roll` parameter is `(IntRange) -> Int`, defaulting to `range.random()`. Tests pass a fixed lambda for deterministic results.
- **Type chart:** Implemented all 18 types upfront as a sparse map (only non-1.0 entries stored). No need to revisit.
- **IVs/EVs gap:** Our simplified stat formula (no IVs/EVs/Nature) produces lower stats than the worked examples in `docs/`. The Charizard vs Venusaur test compensates by starting Venusaur at 130 HP instead of full. Once IVs/EVs are added, the examples will match exactly. Noted in the example doc.

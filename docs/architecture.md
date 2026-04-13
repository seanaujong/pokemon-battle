# Architecture: Event-Sourced Turn Resolution Pipeline

## Overview

A Pokemon singles battle turn — after both players have submitted their choices —
is resolved by running the battle state through a sequence of **phases**. Each phase
inspects the current (immutable) state, emits zero or more **events**, and those
events are applied to produce the next state. The full list of events *is* the
history log.

```
TurnChoices + BattleState
        |
        v
  +-----------+     +-----------+     +-----------+
  |  Phase 1  | --> |  Phase 2  | --> |  Phase 3  | --> ...
  +-----------+     +-----------+     +-----------+
   emit events       emit events       emit events
        |                 |                 |
        v                 v                 v
  apply to state    apply to state    apply to state
        |                 |                 |
        +--------+--------+--------+--------+
                 |
                 v
       Final BattleState + List<BattleEvent>  (complete history)
```

## Core Types

### BattleState

An immutable snapshot of the entire battle at a point in time.

```kotlin
data class BattleState(
    val pokemon1: PokemonState,   // player 1's active Pokemon
    val pokemon2: PokemonState,   // player 2's active Pokemon
    val field: FieldState,        // weather, terrain, trick room, etc.
    val turn: Int
)

data class Species(
    val name: String,
    val types: List<Type>,
    val baseHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val baseSpecialAttack: Int,
    val baseSpecialDefense: Int,
    val baseSpeed: Int
)

data class Pokemon(
    val species: Species,
    val level: Int
    // future: nature, IVs, EVs
)

data class PokemonState(
    val pokemon: Pokemon,
    val currentHp: Int,
    val statStages: StatStages,   // -6 to +6 modifiers
    val status: StatusCondition?, // burn, poison, paralysis, sleep, freeze — null = healthy
    val volatiles: Set<Volatile>, // temporary conditions cleared on switch (see below)
    val ability: String?,
    val item: String?
)
```

Three layers, each with a clear responsibility:

- **`Species`** — what a Charizard *is*. Shared across all Charizards.
- **`Pokemon`** — a specific Charizard. Species + level (and later nature/IVs/EVs).
- **`PokemonState`** — that Charizard *in battle*. Only holds what changes during
  a turn: current HP, stat stages, status, volatiles.

Stats like max HP, attack, defense, etc. are **derived, not stored**. They are
computed from `pokemon.species.baseX` and `pokemon.level` at the point of use
(the standard Pokemon stat formula). Stat stages are applied on top at calc time.
This avoids redundant state that could drift out of sync.

All fields are `val`. State changes happen exclusively through events.

### Volatile

Volatiles are temporary battle conditions that are cleared when a Pokemon switches
out. Contrast with status conditions (burn, poison, etc.) which persist across
switches. A Pokemon can have multiple volatiles at once but never duplicates,
so they're stored as a `Set`.

Some volatiles are simple flags, others carry state (turn counters, targets), so
`Volatile` is a sealed interface rather than an enum:

```kotlin
sealed interface Volatile {
    data object Flinch : Volatile
    data class Confusion(val turnsRemaining: Int) : Volatile
    data class LeechSeed(val drainTarget: Pokemon) : Volatile
    data object Protect : Volatile
}
```

### Player

Events reference Pokemon by player slot, not by object reference. This works cleanly
with immutable state — `BattleState.pokemonFor(player)` provides lookup.

```kotlin
enum class Player { P1, P2 }
```

### BattleEvent

A sealed hierarchy — every possible thing that can happen during a turn.

```kotlin
sealed interface BattleEvent {
    fun apply(state: BattleState): BattleState
}

data class MoveOrderDecided(
    val firstAttacker: Player,      // Player enum (P1/P2) — not object refs
    val reason: String              // "priority", "speed", "speed_tie"
) : BattleEvent

data class MoveAttempted(
    val attacker: Player,
    val move: Move
) : BattleEvent

data class MoveFailed(
    val attacker: Player,
    val reason: String              // "fully_paralyzed", "asleep", "frozen", "flinched"
) : BattleEvent

data class DamageDealt(
    val target: Player,
    val amount: Int,
    val effectiveness: Effectiveness,
    val critical: Boolean
) : BattleEvent

data class StatusApplied(
    val target: Player,
    val status: StatusCondition
) : BattleEvent

data class StatusDamage(
    val target: Player,
    val amount: Int,
    val source: StatusCondition     // burn, poison
) : BattleEvent

data class WeatherDamage(
    val target: Player,
    val amount: Int,
    val weather: Weather
) : BattleEvent

data class PokemonFainted(
    val player: Player
) : BattleEvent

// ... more as needed
```

Each event subclass implements `apply()` to return a new `BattleState`. Events that
are purely informational (like `MoveAttempted`) return the state unchanged — they
still appear in the log.

### Phase

A function that reads the current state and produces events.

```kotlin
fun interface Phase {
    fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent>
}
```

Phases do NOT mutate state. They receive state, decide what should happen, and
express that as events. This keeps each phase a pure function — easy to test
in isolation.

### TurnPipeline

Orchestrates the phases and accumulates the event log.

```kotlin
class TurnPipeline(private val phases: List<Phase>) {

    data class Result(
        val finalState: BattleState,
        val events: List<BattleEvent>
    )

    fun resolve(initialState: BattleState, choices: TurnChoices): Result {
        return phases.fold(Result(initialState, emptyList())) { (state, events), phase ->
            val newEvents = phase.resolve(state, choices)
            val newState = newEvents.fold(state) { s, event -> event.apply(s) }
            Result(newState, events + newEvents)
        }
    }
}
```

The pipeline is a nested fold — outer over phases, inner over events — with no
mutable state. The complexity lives in the phases and events, not the orchestration.

## Phases (initial set)

| Order | Phase                | Responsibility                                         |
|-------|----------------------|--------------------------------------------------------|
| 1     | `MoveOrderPhase`     | Compare priority brackets, then speed. Emit `MoveOrderDecided`. |
| 2     | `MoveExecutionPhase` | For each attacker in order: check if they can act, calculate and apply damage, apply secondary effects. Emit `MoveAttempted`, `MoveFailed`, `DamageDealt`, `StatusApplied`, `PokemonFainted`, etc. |
| 3     | `EndOfTurnPhase`     | Weather tick, status damage (burn/poison), item effects (Leftovers), volatile expiry. Emit `StatusDamage`, `WeatherDamage`, `PokemonFainted`, etc. |

This is deliberately minimal. New phases slot in without touching existing code:

- **`WeatherSetupPhase`** — could run after move execution to handle weather-setting moves
- **`AbilityPhase`** — trigger abilities like Intimidate, Drizzle at the right time
- **`SwitchPhase`** — handle forced switches (Roar, Dragon Tail) or faint switches

## Extensibility Model

Adding new mechanics follows a consistent pattern:

1. **Define new `BattleEvent` subclass(es)** with their `apply()` logic
2. **Create a new `Phase`** or extend an existing one to emit those events
3. **Register the phase** in the pipeline at the right position

Existing phases and events don't need to change. The pipeline is open for extension,
closed for modification.

## Why Immutability

- **Debuggability**: Replay any point in the turn by applying events 0..N
- **Testability**: Assert on the event list, not just the final state. "Did burn
  damage happen before Leftovers recovery?" is answerable by checking event order.
- **Safety**: Phases can't accidentally corrupt state that a later phase depends on.
  Each phase sees a consistent snapshot.
- **History is free**: The event list is a complete, ordered record of the turn.
  No separate logging system needed.

## Multi-Gen Support

Different Pokemon generations (and games like Pokemon Champions) change the rules:
paralysis skip rates, burn damage fractions, speed modifiers, status durations, even
which mechanics exist. The architecture supports this without parameterization.

### Where gen-specific rules live

All gen-specific behavior is in **phases**, not in the model or engine:

| What varies | Where it lives | Example |
|-------------|---------------|---------|
| Paralysis skip rate (25% vs other) | `MoveExecutionPhase` | Status check before move attempt |
| Burn damage fraction (1/16 vs 1/8) | `EndOfTurnPhase` | Status damage sub-concern |
| Speed modifier for paralysis (0.5x vs 0.25x) | `PokemonState.effectiveSpeed()` | Called by `resolveMoveOrder` |
| Damage formula modifiers | `DamageCalc` | Burn penalty, crit multiplier |
| End-of-turn effect order | `EndOfTurnPhase` | Sub-concern ordering |

### How to support a different gen

Build different phase implementations, not config flags:

```kotlin
// Gen V+ rules
val genVPipeline = TurnPipeline(listOf(
    MoveOrderPhase(),
    GenVMoveExecutionPhase(),
    GenVEndOfTurnPhase()
))

// Pokemon Champions rules
val championsPipeline = TurnPipeline(listOf(
    MoveOrderPhase(),
    ChampionsMoveExecutionPhase(),
    ChampionsEndOfTurnPhase()
))
```

The pipeline, events, and model layer are gen-agnostic. Different gens produce the
same event types (`MoveFailed`, `DamageDealt`, `StatusDamage`) — they just disagree
on the numbers and conditions. A `DamageDealt` event doesn't know or care which gen
calculated it.

### What NOT to do

- **Don't add gen parameters to phases.** `MoveExecutionPhase(gen = Gen.V)` leads to
  phases full of `if (gen == CHAMPIONS)` branches. Separate implementations are
  smaller, testable, and don't accumulate dead branches.
- **Don't put gen-specific constants in the model.** `StatusCondition.BURN` is the
  same across gens. The *effect* of burn (how much damage, which stats it modifies)
  is phase logic, not data.
- **Don't put gen-specific logic in events.** `DamageDealt.apply()` just subtracts
  HP. The number it subtracts was decided by a phase — that's where gen rules belong.

### Known gen-specific leak

`PokemonState.effectiveSpeed()` applies the paralysis speed modifier (0.5x). This is
a gen-specific rule living in the model layer. It's acceptable while we target a single
gen, but for multi-gen support this logic would move into the phase or engine layer
(e.g., a gen-specific speed resolver that `resolveMoveOrder` delegates to).

### Keeping ourselves honest

When adding new mechanics, ask: "is this a game rule or a data definition?" If it's
a rule (a number, a condition, a formula), it belongs in a phase. If it's a definition
(a type, a stat, a structure), it belongs in the model. Gen-specific logic leaking
into the model or engine is a sign something is in the wrong layer.

## Known Limitations

### MoveExecutionPhase doesn't track intermediate state during effects

`executeMove` returns a flat list of events without applying them between steps.
Currently fine because `StatBoost` is self-contained. But effects that depend on
post-damage state (drain: heal based on damage dealt, recoil: self-damage based on
damage dealt) will need the phase to apply events incrementally — similar to how
`resolve()` applies events between players, but within a single move's execution.

When adding drain/recoil/multi-hit: refactor `executeMove` to track `currentState`
across damage and effects, same pattern as the outer loop.

## What This Design Does NOT Cover (yet)

- Team selection / team preview
- Switch-in triggers (entry hazards, abilities)
- Multi-turn moves (Fly, Dig, Solar Beam)
- Choice locks, Encore, Disable
- Mega Evolution, Z-Moves, Dynamax, Terastallization

These can all be modeled as new phases + events when we're ready for them.

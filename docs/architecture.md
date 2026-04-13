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

### Effects don't track intermediate state

`executeMove` tracks `currentState` across per-target damage (a fainted target is
skipped for subsequent hits). However, the effects phase at the end of `executeMove`
doesn't apply effect events to `currentState` between effects. Currently fine because
`StatBoost` is self-contained. But effects that depend on post-damage state (drain:
heal based on damage dealt, recoil: self-damage based on damage dealt) will need
the effects loop to track state incrementally too.

### MoveOrderResult.reason only describes top-two ordering

The `reason` field compares the first and second slots. In a 4-slot doubles battle,
each pair might have different ordering reasons (priority vs speed). The field is
informational only and doesn't affect behavior, but can be misleading. Consider
per-slot reasons or removing it if precision matters.

### No validation on targetSlot in TurnChoice

`TurnChoice.UseMove(move, targetSlot)` doesn't verify that `targetSlot` is a valid
opponent slot for `ONE_OPPONENT` moves. A caller could pass an ally slot or the
attacker's own slot. This is an input validation concern for the game loop layer —
the engine trusts its inputs, same as with IV/EV validation.

### Gen-specific rules in engine and model layers

Documented above under "Known gen-specific leak" and "Multi-Gen Support." The burn
modifier and STAB live in `DamageCalc` (engine); paralysis speed halving lives in
`PokemonState.effectiveSpeed()` (model). Both are acceptable for single-gen but would
need to move to phases for multi-gen support.

## Future Scenarios

Scenarios that the architecture supports with varying degrees of new work.

### Switching mid-turn (Pursuit, U-turn)

**U-turn** works: it's a move effect that triggers a switch. The slot's `PokemonState`
changes via `BattleState.withPokemon(slot, newPokemon)`.

**Pursuit** is harder: it needs to know about pending switches *before* moves execute,
hitting the switching Pokemon at double power. This requires either a pre-execution
switch resolution phase, or the ability to interrupt the normal execution order. The
pipeline supports adding phases, but Pursuit's timing is a new concept — it reacts to
an opponent's *choice*, not the current state.

### Mega Evolution / Dynamax / Terastallization

These transform a Pokemon at the start of a turn, changing types, stats, or abilities.
The transformation needs to happen before `MoveOrderPhase` (Mega can change speed,
affecting ordering). A pre-order transformation phase handles this — the pipeline
supports inserting phases at any position.

The subtlety: the player's *choice* to Mega evolve is submitted alongside their move
choice but resolved before moves. This means `TurnChoice` needs to express "use this
move AND Mega evolve" — a compound choice.

### Ally targeting in doubles (Heal Pulse, Helping Hand)

The slot model supports this: `slotsForSide(slot.side).filter { it != slot }` gives
allied slots. `MoveTarget` needs a `ONE_ALLY` value, and `resolveTargets` needs a
new case. Straightforward extension.

### Triples — positional adjacency

In triples, position 0 can only target positions 0 and 1 on the opposing side. Position
1 (center) can target all three. `Slot.position` is already there — adjacency logic is
a `resolveTargets` extension. Needs `MoveTarget.ONE_ADJACENT` and a helper like
`BattleState.adjacentSlots(slot)`.

### Out of scope: Rotation battles

Rotation battles need a concept of active vs bench *within* one side's slots. This
doesn't fit the current model where every slot in `BattleState.slots` is active. A
rotation battle engine would be a separate implementation sharing the model layer but
with a different `BattleState` shape.

## What This Design Does NOT Cover (yet)

- Team selection / team preview
- Switch-in triggers (entry hazards, abilities)
- Multi-turn moves (Fly, Dig, Solar Beam)
- Choice locks, Encore, Disable
- Mega Evolution, Z-Moves, Dynamax, Terastallization

These can all be modeled as new phases + events when we're ready for them.

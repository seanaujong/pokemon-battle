# Architecture: Event-Sourced Turn Resolution Pipeline

## Overview

A Pokemon battle turn — after all players have submitted their choices —
is resolved by running the battle state through a sequence of **phases**. Each phase
inspects the current (immutable) state, emits zero or more **events**, and those
events are applied to produce the next state. The full list of events *is* the
history log.

```
TurnChoices + BattleState
        |
        v
  +-----------+     +-----------+     +-----------+     +-----------+
  | MoveOrder | --> |  Switch   | --> | MoveExec  | --> | EndOfTurn | --> ...
  +-----------+     +-----------+     +-----------+     +-----------+
   emit events       emit events       emit events       emit events
        |                 |                 |                 |
        v                 v                 v                 v
  apply to state    apply to state    apply to state    apply to state
        |                 |                 |                 |
        +--------+--------+--------+--------+---------+-------+
                 |
                 v
       Final BattleState + List<BattleEvent>  (complete history)
```

The pipeline is wrapped by a **game loop** that collects choices, runs turns,
handles faint replacements, and checks win conditions.

## Layers

```
loop/       — game orchestration (BattleLoop, providers, results)
phase/      — turn resolution logic (gen-specific rules)
engine/     — pipeline plumbing (events, state, damage calc)
model/      — pure data (species, pokemon, moves, types)
```

Each layer depends only on the ones below it.

## Core Types

### BattleState

An immutable snapshot of the entire battle at a point in time. Uses a slot-based
model that supports singles, doubles, triples, and co-op formats.

```kotlin
data class BattleState(
    val slots: Map<Slot, PokemonState>,     // active Pokemon on the field
    val bench: Map<Side, List<PokemonState>>, // per-side reserves
    val field: FieldState,                  // weather, terrain
    val turn: Int
)

enum class Side { SIDE_1, SIDE_2 }
data class Slot(val side: Side, val position: Int = 0)
```

Slots identify field positions. Singles has 2 slots, doubles has 4. The bench holds
reserves for switching and faint replacement.

Helpers: `pokemonFor(slot)`, `withPokemon(slot, state)`, `slotsForSide(side)`,
`opponentSlots(slot)`, `allSlots()`, `benchFor(side)`, `isDefeated(side)`.
Factories: `BattleState.singles(...)`, `BattleState.doubles(...)`.

### Data layering

```
Species  →  Pokemon  →  PokemonState
(what)      (who)       (in battle)
```

- **`Species`** — what a Charizard *is*. Base stats, types. Shared across all Charizards.
  Has `baseStat(StatType)` accessor.
- **`Pokemon`** — a specific Charizard. Species + level + IVs + EVs + Nature.
  Has `maxHp` and `calcStat(StatType)` using the full Gen V+ formula.
- **`PokemonState`** — that Charizard *in battle*. Current HP, stat stages, status,
  volatiles, ability, item. Has `isFainted`, `maxHp`, `effectiveSpeed()`.

All fields are `val`. State changes happen exclusively through events.

### Volatile

Temporary battle conditions cleared on switch-out. Contrast with status conditions
(burn, poison) which persist. Stored as a `Set` on `PokemonState`.

```kotlin
sealed interface Volatile {
    data object Flinch : Volatile
    data class Confusion(val turnsRemaining: Int) : Volatile
    data class Sleep(val turnsRemaining: Int) : Volatile
    data object Protect : Volatile
}
```

### Move

```kotlin
data class Move(
    val name: String,
    val type: Type,
    val category: MoveCategory,     // PHYSICAL, SPECIAL, STATUS
    val power: Int,
    val priority: Int = 0,
    val target: MoveTarget = MoveTarget.ONE_OPPONENT,
    val effects: List<MoveEffect> = emptyList()
)

enum class MoveTarget { SELF, ONE_OPPONENT, ALL_OPPONENTS, ALL_OTHER }

sealed interface MoveEffect {
    data class StatBoost(val stat: StatType, val stages: Int) : MoveEffect
}
```

`MoveTarget` determines which slots the move affects. `MoveEffect` describes
secondary/primary effects beyond damage. Both are extensible via new enum values
and sealed subclasses.

### BattleEvent

A sealed hierarchy — every possible thing that can happen during a turn. Each
event has `apply(state): BattleState`.

```
MoveOrderDecided    — informational: who goes in what order, and why
MoveAttempted       — informational: a Pokemon attempts a move
MoveFailed          — informational: a Pokemon can't act (sleep, freeze, paralysis)
DamageDealt         — subtracts HP from target
PokemonFainted      — informational: HP reached 0
StatusApplied       — sets a status condition
StatusCleared       — clears a status + related volatiles
StatusDamage        — end-of-turn burn/poison damage
WeatherDamage       — end-of-turn weather damage
ItemHealing         — end-of-turn item healing (Leftovers)
WeatherTick         — decrements weather counter
StatChanged         — modifies stat stages (clamped to -6..+6)
VolatileChanged     — updates a volatile (e.g., sleep counter decrement)
SwitchOut           — moves Pokemon to bench, clears volatiles/stat stages
SwitchIn            — moves bench Pokemon to slot
```

Events that are purely informational return the state unchanged. They still
appear in the log for rendering and debugging.

### TurnChoices

```kotlin
data class TurnChoices(val choices: Map<Slot, TurnChoice>)

sealed interface TurnChoice {
    data class UseMove(val move: Move, val targetSlot: Slot? = null) : TurnChoice
    data class Switch(val benchIndex: Int) : TurnChoice
}
```

`targetSlot` is used for `ONE_OPPONENT` moves in doubles where the player
selects which opposing slot to target.

### Phase and TurnPipeline

```kotlin
fun interface Phase {
    fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent>
}

class TurnPipeline(private val phases: List<Phase>) {
    fun resolve(initialState: BattleState, choices: TurnChoices): Result
}
```

The pipeline is a nested fold — outer over phases, inner over events — with no
mutable state. The complexity lives in the phases and events, not the orchestration.

## Phases

| Order | Phase                | Responsibility |
|-------|----------------------|----------------|
| 1     | `MoveOrderPhase`     | Sort slots by priority then speed. Emit `MoveOrderDecided`. |
| 2     | `SwitchPhase`        | Process voluntary switches in speed order. Emit `SwitchOut` + `SwitchIn`. |
| 3     | `MoveExecutionPhase` | For each slot in order: status checks, damage calc, effects. |
| 4     | `EndOfTurnPhase`     | Weather damage, status damage, item effects, weather tick. |

New phases slot in without touching existing code.

## Game Loop

```kotlin
class BattleLoop(
    pipeline: TurnPipeline,
    choiceProvider: ChoiceProvider,
    faintReplacementProvider: FaintReplacementProvider,
    maxTurns: Int = 100
)
```

The game loop collects choices, runs the pipeline, handles faint replacements,
and checks win conditions. `ChoiceProvider` and `FaintReplacementProvider` are
callback interfaces — tests pass lambdas, a real game implements with UI.

`TurnResult` separates pipeline events from replacement events. `BattleResult`
reports the winner, final state, and full turn history.

## Extensibility Model

Adding new mechanics follows a consistent pattern:

1. **Define new `BattleEvent` subclass(es)** with their `apply()` logic
2. **Create a new `Phase`** or extend an existing one to emit those events
3. **Register the phase** in the pipeline at the right position

Existing phases and events don't need to change.

## Multi-Gen Support

Different Pokemon generations change the rules. The architecture supports this
by building different phase implementations, not config flags.

### Where gen-specific rules live

All gen-specific behavior is in **phases**, not in the model or engine:

| What varies | Where it lives |
|-------------|---------------|
| Paralysis skip rate | `MoveExecutionPhase` |
| Burn damage fraction | `EndOfTurnPhase` |
| Damage formula modifiers | `DamageCalc` |
| End-of-turn effect order | `EndOfTurnPhase` |
| Spread damage modifier | `MoveExecutionPhase` |

### What NOT to do

- Don't add gen parameters to phases — separate implementations instead
- Don't put gen-specific constants in the model
- Don't put gen-specific logic in events — `apply()` is mechanical

### Known gen-specific leaks

- `PokemonState.effectiveSpeed()` applies paralysis speed modifier (model layer)
- `DamageCalc` contains burn penalty and STAB (engine layer)

These are acceptable for single-gen. For multi-gen, they'd move to phases.

### Keeping ourselves honest

When adding new mechanics, ask: "is this a game rule or a data definition?"
Rules belong in phases. Definitions belong in the model.

## Known Limitations

### Effects don't track intermediate state

The effects loop at the end of `executeMove` doesn't apply events between effects.
Fine for `StatBoost`. Drain/recoil will need incremental state tracking.

### MoveOrderResult.leadReason only describes top-two ordering

Informational only, doesn't affect behavior. Can be misleading in multi-slot formats.

### No validation on targetSlot in TurnChoice

Engine trusts its inputs. Validation belongs in the game loop layer.
`ONE_OPPONENT` does validate that the target is on the opposing side.

## Future Scenarios

### Switching mid-turn (Pursuit, U-turn)

U-turn works as a `MoveEffect` triggering switch after damage. Pursuit needs
pre-execution awareness of pending switches — a new timing concept.

### Mega Evolution / Dynamax / Terastallization

Pre-order transformation phase. `TurnChoice` needs compound choices
("use this move AND Mega evolve").

### Ally targeting (Heal Pulse, Helping Hand)

Add `MoveTarget.ONE_ALLY`. `resolveTargetSlots` gets a new case.

### Triples adjacency

`Slot.position` supports it. Add `MoveTarget.ONE_ADJACENT` and adjacency helpers.

### Out of scope: Rotation battles

Needs active-vs-bench within a side — different `BattleState` shape.

## What This Design Does NOT Cover (yet)

- Team selection / team preview
- Switch-in triggers (entry hazards, abilities)
- Multi-turn moves (Fly, Dig, Solar Beam)
- Choice locks, Encore, Disable
- Mega Evolution, Z-Moves, Dynamax, Terastallization
- Ability system (Intimidate, Levitate, Drizzle)

These can all be modeled as new phases + events when we're ready for them.

# How a Turn Works

You know how a Pokemon battle turn works. Both players pick a move. The faster Pokemon goes first. Damage is calculated, types matter, and sometimes someone faints. Then end-of-turn effects tick — weather, burn, Leftovers.

This codebase models exactly that. The twist is *how* — every single thing that happens during a turn is recorded as an **event**, and the battle state is never modified in place. This guide walks through the code using examples you already know.

## The setup

Before a turn starts, we need to know what's on the field. That's `BattleState`:

```kotlin
data class BattleState(
    val pokemon1: PokemonState,   // player 1's active Pokemon
    val pokemon2: PokemonState,   // player 2's active Pokemon
    val field: FieldState,        // weather, terrain
    val turn: Int
)
```

Each Pokemon on the field is a `PokemonState` — their current HP, stat stages, status condition, held item. This is separate from `Species` (what a Charizard *is*) and `Pokemon` (a specific Charizard at level 50). `PokemonState` is what changes during battle.

The key rule: **everything is `val`**. You can't write `pokemon.currentHp -= 50`. Instead, you create a new `PokemonState` with the updated HP. This sounds tedious, but it's what makes the whole system work.

## Both players pick a move

```kotlin
val choices = TurnChoices(
    p1 = TurnChoice.UseMove(flamethrower),
    p2 = TurnChoice.UseMove(sludgeBomb)
)
```

That's it. In the future this could include switching, using items, etc. — `TurnChoice` is a sealed interface so new options can be added.

## The turn runs through a pipeline

Here's the core idea. A turn is processed by running the state through a sequence of **phases**:

```
MoveOrderPhase → MoveExecutionPhase → EndOfTurnPhase
```

Each phase looks at the current state, decides what happens, and expresses it as a list of **events**. The pipeline applies those events to get the next state, then passes it to the next phase.

```kotlin
val pipeline = TurnPipeline(
    listOf(MoveOrderPhase(), MoveExecutionPhase(), EndOfTurnPhase())
)
val result = pipeline.resolve(initialState, choices)
// result.events  = everything that happened, in order
// result.finalState = the state after the turn
```

That's the whole orchestration. Let's see what each phase does.

## Phase 1: Who goes first?

`MoveOrderPhase` compares priority brackets, then speed. Charizard (base 100 speed) vs Venusaur (base 80 speed), both using priority-0 moves:

```
Event: MoveOrderDecided(firstAttacker=P1, reason="speed")
```

This event is **informational** — it doesn't change the state. It exists so the log records *why* Charizard went first. A test can assert on the reason, not just the outcome.

If Infernape uses Mach Punch (+1 priority) against Swampert's Earthquake (priority 0), the event says:

```
Event: MoveOrderDecided(firstAttacker=P1, reason="priority")
```

Same phase, different reason. The log makes it clear.

## Phase 2: Moves happen

`MoveExecutionPhase` is where the action is. For each Pokemon in order:

1. Check if they can act (not fainted)
2. Attempt the move
3. Calculate damage (type effectiveness, STAB, burn penalty, etc.)
4. Apply the damage
5. Check if the target fainted

Each of those steps is an event:

```
MoveAttempted(attacker=P1, move=Flamethrower)
DamageDealt(target=P2, amount=162, effectiveness=SUPER_EFFECTIVE, critical=false)
PokemonFainted(player=P2)
```

The `DamageDealt` event's `apply()` method creates a new state with Venusaur at 0 HP. The `PokemonFainted` event is informational — the HP was already set by `DamageDealt`.

Because Venusaur fainted, the phase skips its turn entirely. No events, no damage, nothing. The log tells the complete story.

### The damage formula

The damage calculation is a pure function:

```kotlin
val result = calculateDamage(attacker, defender, move, roll = { 100 })
```

The `roll` parameter controls the random factor (85-100 in real games). For testing, you pass a fixed value. This is how we can write deterministic tests against a formula that's normally random.

The formula handles:
- Type effectiveness (Fire vs Grass/Poison = 2x)
- STAB (Charizard using a Fire move = 1.5x)
- Burn penalty (halves physical damage)
- Stat stages (Swords Dance boosts, Intimidate drops)

## Phase 3: End of turn

`EndOfTurnPhase` handles everything that ticks at the end of a turn, in the game's fixed order:

1. **Weather damage** — Sandstorm hits everyone who isn't Rock/Ground/Steel (or has a weather-immune ability like Sand Veil)
2. **Status damage** — Burn deals 1/16 max HP, Poison deals 1/8
3. **Item effects** — Leftovers restores 1/16 max HP
4. **Weather tick** — Countdown decrements, weather clears when it hits 0

Each produces its own event type:

```
WeatherDamage(target=P1, amount=9, weather=SANDSTORM)
StatusDamage(target=P2, amount=10, source=BURN)
ItemHealing(target=P2, amount=10, item=LEFTOVERS)
WeatherTick(weather=SANDSTORM, turnsRemaining=2)
```

Notice how burn damage and Leftovers healing cancel out for Swampert (both 1/16 max HP). This isn't special-cased — it falls out naturally from the event sequence. But the log makes it *visible*.

## Why events?

You might wonder: why not just mutate the state directly? `pokemon.hp -= damage` is simpler, right?

Three reasons, all grounded in Pokemon:

### 1. You can replay any point in the turn

The event list is a complete history. Want to know what Infernape's HP was after Earthquake but before sandstorm? Apply events 1-5 and read the state. This is how things like damage logs and battle replays work.

### 2. Tests can assert on *what happened*, not just the end result

"Infernape has 33 HP" doesn't tell you much. But this does:

```
took 110 from Earthquake (super effective, burn-halved)
took 9 from sandstorm
final: 33 HP
```

If a bug causes sandstorm to tick before moves execute, the event *order* catches it immediately — even if the final HP happens to be the same.

### 3. Phases can't corrupt each other

Each phase gets an immutable snapshot. `MoveExecutionPhase` can't accidentally modify state that `EndOfTurnPhase` depends on. They communicate through events, which the pipeline applies between phases.

## How to add new mechanics

This is where the architecture pays off. Want to add a new mechanic? The pattern is always the same:

1. **Define a new event** — e.g., `StatChanged(target, stat, stages)` with an `apply()` that updates stat stages
2. **Emit it from a phase** — either an existing phase (Intimidate could be in `MoveExecutionPhase`) or a new one
3. **Register the phase** — add it to the pipeline at the right position

Existing events and phases don't change. Here are some examples:

| Mechanic | New event(s) | Where it goes |
|----------|-------------|---------------|
| Trick Room | `TrickRoomSet`, `TrickRoomEnded` | New phase or `MoveExecutionPhase`; `MoveOrder` checks field state |
| Entry hazards | `HazardDamage`, `HazardSet` | New `SwitchInPhase` |
| Intimidate | `StatChanged` | New `AbilityPhase` that runs on switch-in |
| Leftovers | `ItemHealing` (already exists) | Already in `EndOfTurnPhase` |

The pipeline is open for extension. You add new things by writing new code, not by modifying what's already working.

## The code layout

```
src/main/kotlin/com/pokemon/battle/
  model/    -- data types: Species, Pokemon, Type, Move, etc.
  engine/   -- pipeline: Phase, TurnPipeline, BattleEvent, DamageCalc
  phase/    -- MoveOrderPhase, MoveExecutionPhase, EndOfTurnPhase
```

**model** is what things *are*. **engine** is how turns *work*. **phase** is what *happens* during a turn.

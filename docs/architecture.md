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

## Design Rationale

These are the key design decisions and why they were made.

**Start from one concrete scenario, then layer.** The first working code resolved
"Charizard KOs Venusaur with Flamethrower" end-to-end. Every abstraction earned
its place against a real flow before the next mechanic was added.

**Keep orchestration trivial.** The pipeline is a nested fold — outer over phases,
inner over events — in roughly 5 lines. All interesting logic lives in phases and
events, which are independently testable. If the orchestrator is complex, logic is
in the wrong place.

**Derive, don't store.** Stats are calculated from base + IVs/EVs/Nature + stages
at the point of use, never cached on state. This eliminates staleness bugs at the
cost of recalculation, which is negligible for this domain.

**Separate data by lifecycle.** Species (eternal, shared) → Pokemon (per-battle) →
PokemonState (per-turn). Each layer changes at a different rate, so separating
them makes it obvious where each piece of data belongs.

**Sealed hierarchies as domain catalogs.** The sealed event hierarchy is a readable
catalog of everything that can happen in a turn. A new developer opens
`BattleEvent.kt` and sees the whole domain. Events are split by concern across
files but share the same sealed interface.

**Immutability makes the audit trail free.** Because state is never mutated, the
event history *is* the audit trail — no logging layer needed on top. Every state
can be explained by replaying its events.

**The engine doesn't enforce legality.** No learnset validation, no move count
limits, no species-ability restrictions. The engine resolves whatever it's given.
Legality is a team-building concern — a layer above the engine. This makes custom
formats (Hackmons, Almost Any Ability, etc.) work without engine changes.

**Move pools belong to the choice layer, not the model.** A Pokemon's identity
(species + level + IVs/EVs/nature) is separate from what moves are available to it.
The AI owns move pools. This prevents Pokemon from becoming a god object.

## Layers

```
ai/         — choice logic (RandomAI, TypeAI, SidedAI)
render/     — presentation (TextRenderer, renderBattle)
loop/       — game orchestration (BattleLoop, providers, results)
phase/      — turn resolution logic (gen-specific rules)
engine/     — pipeline plumbing (events, state, damage calc)
data/       — loading and lookup (Pokedex CSV, MoveDex definitions)
model/      — pure data (species, pokemon, moves, types)
```

Dependency graph (each layer depends only on those below it):

```
ai      →  engine, model, loop
render  →  engine, model, loop
loop    →  engine, model
phase   →  engine, model
data    →  model
engine  →  model
model   →  (nothing)
```

## Core Types

### BattleState

An immutable snapshot of the entire battle at a point in time. Uses a slot-based
model that supports singles, doubles, triples, and co-op formats.

```kotlin
data class BattleState(
    val slots: Map<Slot, PokemonState>,       // active Pokemon on the field
    val bench: Map<Side, List<PokemonState>>,  // per-side reserves
    val field: FieldState,                     // weather, terrain
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
  Has `baseStat(StatType)` accessor. Loaded from CSV via `Pokedex`.
- **`Pokemon`** — a specific Charizard. Species + level + IVs + EVs + Nature.
  Has `maxHp` and `calcStat(StatType)` using the full Gen V+ formula.
- **`PokemonState`** — that Charizard *in battle*. Current HP, stat stages, status,
  volatiles, ability, item. Has `isFainted`, `maxHp`, `baseEffectiveSpeed()`.

All fields are `val`. State changes happen exclusively through events.

### Volatile

Temporary battle conditions cleared on switch-out (by `SwitchPhase`, not by the
event — clearing is a gen-specific rule). Contrast with status conditions
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
and sealed subclasses. Move definitions live in `MoveDex` with effects colocated.

### BattleEvent

A sealed hierarchy split across 7 files by concern. Each event has
`apply(state): BattleState`.

| File | Events |
|------|--------|
| `BattleEvent.kt` | `MoveOrderDecided`, `MoveAttempted`, `MoveFailed`, `DamageDealt`, `PokemonFainted` |
| `StatusEvents.kt` | `StatusApplied`, `StatusDamage`, `StatusCleared` |
| `WeatherEvents.kt` | `WeatherDamage`, `WeatherTick`, `WeatherSet` |
| `SwitchEvents.kt` | `SwitchOut`, `SwitchIn` |
| `StatEvents.kt` | `StatChanged`, `VolatileChanged` |
| `AbilityEvents.kt` | `AbilityTriggered`, `AbilityBlocked` |
| `ItemEvents.kt` | `ItemHealing` |

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

### Phase and TurnPipeline

```kotlin
fun interface Phase {
    fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent>
}

class TurnPipeline(private val phases: List<Phase>) {
    fun resolve(initialState: BattleState, choices: TurnChoices): Result
}
```

## Phases

| Order | Phase                | Responsibility |
|-------|----------------------|----------------|
| 1     | `MoveOrderPhase`     | Sort slots by priority then speed via `SpeedResolver`. |
| 2     | `SwitchPhase`        | Clear volatiles/stat stages, switch, trigger switch-in abilities. |
| 3     | `MoveExecutionPhase` | Status checks, per-target damage (with ability immunity), effects. |
| 4     | `EndOfTurnPhase`     | Weather damage, status damage, item effects, weather tick. |

`MoveExecutionPhase` is structured as sub-functions: `checkStatusThenExecute` →
`executeMove` → `resolveDamage` (per-target) + `resolveEffects`.

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

Collects choices, runs the pipeline, handles faint replacements (with switch-in
ability triggers), and checks win conditions. `TurnResult` separates pipeline
events from replacement events. `BattleResult` reports the winner, final state,
and full turn history.

## Application Layer

### Data (implemented)

- **`Pokedex`** — loads species from `data/species.csv` (20 species).
  `Pokedex.loadFromClasspath()` returns `Map<String, Species>`.
- **`MoveDex`** — Kotlin-defined moves with colocated effects (14 moves).
  Auto-registered via `register()` pattern. `MoveDex["Flamethrower"]` for lookup.

### Renderer (implemented)

`TextRenderer` converts events to game-style text ("Charizard used Flamethrower!").
`BattleRenderer` interface takes `(event, stateBefore, stateAfter)` — swappable for
HTML, animation, etc. `renderBattle()` replays from initial state to produce complete
output.

### AI (implemented)

- **`RandomAI`** — picks random moves. Baseline. Injectable `Random` for tests.
- **`TypeAI`** — scores moves by type effectiveness × STAB × power.
- **`SidedAI`** — composes two AIs by side.

Move pools are `Map<String, List<Move>>` keyed by species name, owned by the AI.

### UI layer (not yet implemented)

The true test of layer separation: multiple consumers using the same engine
interfaces. `ChoiceProvider` and `BattleRenderer` are the integration points.

| Consumer | ChoiceProvider | Renderer | Transport |
|----------|---------------|----------|-----------|
| REPL | stdin reader | `TextRenderer` to stdout | None (in-process) |
| TUI | terminal UI library | Formatted text with HP bars | None (in-process) |
| REST API | POST endpoint, waits for response | JSON event stream | HTTP |
| React site | REST API underneath | Frontend consumes event JSON | HTTP + WebSocket |
| MCP server | Tool calls for choices | Events as tool responses | MCP protocol |

The engine doesn't change. `BattleResult` with its event log is the universal
output format. Each consumer reads it through its own renderer.

### Analytics pipeline (not yet implemented)

Aggregate event logs across battles for win rates, move usage, balance analysis.
The event log is structured data — typed events with slot references, damage
amounts, effectiveness. Natural input for batch analytics.

## Multi-Gen Support

Different generations change the rules. The architecture supports this
by building different phase implementations, not config flags.

### Where gen-specific rules live

| What varies | Where it lives |
|-------------|---------------|
| Paralysis skip rate | `MoveExecutionPhase` |
| Burn damage fraction | `EndOfTurnPhase` |
| Damage formula | `DamageCalculator` (injectable) |
| Speed modifiers | `SpeedResolver` (injectable) |
| End-of-turn effect order | `EndOfTurnPhase` |
| Spread damage modifier | `MoveExecutionPhase` |
| Volatile/stat clearing on switch | `SwitchPhase` |

### Injectable gen-specific logic

- **`DamageCalculator`** — `fun interface`. `GenVDamageCalculator` default.
- **`SpeedResolver`** — `fun interface`. `GenVSpeedResolver` default.

### What NOT to do

- Don't add gen parameters to phases — separate implementations instead
- Don't put gen-specific constants in the model
- Don't put gen-specific logic in events — `apply()` is mechanical

## Custom Format Compatibility

The engine makes few assumptions about what's "legal," so most Showdown/Smogon
custom formats work without engine changes.

### What works out of the box

| Format | Why it works |
|--------|-------------|
| Almost Any Ability | `PokemonState.ability` is independent of species |
| Balanced Hackmons | Arbitrary stats, any ability, any move |
| Pure Hackmons | No learnset enforcement |
| 6+ moveslots | Move pools are `List<Move>` with no size limit |
| Little Cup (Level 1) | Stat formula handles any level |
| 350 Cup | Construct modified `Species` at team-building |
| Mix and Mega | Modified `Move` objects passed to `TurnChoice` |

### What needs architectural changes

| Change | Enables |
|--------|---------|
| Overridable types on `PokemonState` | Terastallization, Camomons, STABmons |
| Injectable type chart | Inverse Battles |
| Move-use ability trigger in `MoveExecutionPhase` | Trademarked format |
| Side-wide ability queries | Shared Power format |
| Phase-level move modification | Terrain Pulse, Weather Ball |

## Known Limitations

### MoveOrderResult.leadReason only describes top-two ordering

Informational only, doesn't affect behavior. Can be misleading in multi-slot formats.

### SwitchIn uses benchIndex (position-based)

Bench shifts between events, so individual events aren't self-describing.
Correct during sequential replay. Fix if event log portability is needed.

### SwitchOut leaves Pokemon in slot AND bench temporarily

Resolved by paired `SwitchIn`. Only a problem if `SwitchOut` is emitted alone.

## Future Scenarios

| Scenario | Approach | Complexity |
|----------|----------|------------|
| U-turn / Volt Switch | `MoveEffect.SelfSwitch` after damage | Low |
| Pursuit | Pre-execution switch awareness | Medium |
| Mega Evolution / Dynamax | Pre-order transformation phase + compound `TurnChoice` | Medium |
| Ally targeting (Heal Pulse) | `MoveTarget.ONE_ALLY` + new `resolveTargetSlots` case | Low |
| Triples adjacency | `Slot.position` + adjacency helpers | Low |
| Rotation battles | Different `BattleState` shape (active vs bench within side) | Out of scope |

## What This Design Does NOT Cover (yet)

- Team selection / team preview / learnset validation
- Entry hazards (Stealth Rock, Spikes)
- Multi-turn moves (Fly, Dig, Solar Beam)
- Choice locks, Encore, Disable
- Critical hit calculation
- Protect / Substitute mechanics
- CLI / REPL for interactive play

These can all be modeled as new phases, events, effects, and injectable interfaces.

## Lessons Learned

Principles discovered during development, not obvious at the start.

**Resist the god object.** We almost put `moves: List<Move>` on `Pokemon` because
"that's how the games work." But the engine never reads a Pokemon's move pool — it
only sees the chosen move via `TurnChoice`. Move pools belong to the choice layer
(AI, CLI), not the model. Ask: "does the engine actually read this?" before adding
fields.

**Name the concept, not the implementation.** `Player` conflated three things: which
side (Side), which field position (Slot), and who makes decisions (Controller). It
took the Eterna Forest co-op example to expose this. When a name starts feeling
overloaded, it probably represents multiple concepts.

**Events are mechanical, rules live in phases.** `SwitchOut.apply()` originally cleared
volatiles and stat stages. That's a game rule, not a state transformation. Moving it
to `SwitchPhase` made Baton Pass possible without changing the event. If an event's
`apply()` has `if` branches based on game rules, the logic is in the wrong place.

**Start concrete, extract when the pattern repeats.** Abilities started as direct
`when` dispatch in phases. The damage calc started as a free function. Speed
calculation started on `PokemonState`. Each became injectable (`DamageCalculator`,
`SpeedResolver`, `TypeChart`) only when multi-gen support demanded it — not before.

**The engine doesn't enforce legality.** No learnset validation, no move count limits,
no species-ability restrictions. This wasn't a shortcut — it's a design choice that
makes custom formats (Hackmons, Almost Any Ability) work without engine changes.
Legality is a team-building concern, a layer above the engine.

**Don't calculate — let the code tell you.** Mental math for damage formulas is
error-prone. Run the code with fixed inputs, read the output, assert on that. The
code is deterministic; your head isn't.


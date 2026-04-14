# Architecture: Event-Sourced Turn Resolution Pipeline

> **Scope of this document.** This is the *why* and *shape* of the engine, not a
> reference for current type signatures. Code is authoritative for shapes, field
> lists, and what is currently implemented — this doc drifts the moment you look
> at it. Read `engine/src/main/kotlin/com/pokemon/battle/` for current types,
> `docs/diaries/` for the history of decisions, and the sections below for the
> rationale that ties them together.
>
> The canonical statement of the engine's **key invariants** (immutability, events
> as sole mutation, pure phases, sealed hierarchies, engine-has-zero-I/O) lives in
> `CLAUDE.md` under *Design Principles*. This document restates them only where a
> rationale section explains *why* the choice was made.

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
handles faint replacements, and checks win conditions. For the current set of
phases, event subclasses, and pipeline/loop signatures — including mid-turn input
handling added in diary 055 and the `GameEvent` / `ControlEvent` split in diary
061 — see the source under `engine/src/main/kotlin/com/pokemon/battle/`.

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
catalog of everything that can happen in a turn. A new developer opens the
`engine/` events package and sees the whole domain. Events are split by concern
across files but share the same sealed interface.

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

The repo is a Gradle multi-module build. Modules are peers, not
sub-packages — each has its own `build.gradle.kts`, dependencies, and
test suite. For the authoritative list see `settings.gradle.kts`.

```
:cli             — interactive / demo entry points (PlayMain, DemoMain)
:analytics       — event-stream consumers (BattleAnalyzer, ReplayExporter)
:data-ingestion  — PokeAPI / Smogon fetchers + codegen (auditModelGap, codegenSpecies)
:ai              — choice strategies (RandomAI, TypeAI, SidedAI)
:render          — events-to-text (TextRenderer, BattleRenderer, per-item/ability text)
:data            — catalogs (PokedexCatalog, MoveDex, Pokedex loader, generated Kotlin)
:engine          — pipeline, events, model, phases, loop
```

Dependency graph (arrow = "declared dependency"):

```
:cli             →  :engine, :data, :render, :ai
:analytics       →  :engine (tests: :data)
:data-ingestion  →  :data, :engine
:ai              →  :engine   (api — re-exposes TurnChoice)
:render          →  :engine   (api — re-exposes BattleEvent/State)
:data            →  :engine   (api — re-exposes Species)
:engine          →  (no project deps; depends only on kotlinx-serialization)
```

**Three modules re-expose `:engine`'s public types via `api(...)`:** a
consumer of `:data` / `:render` / `:ai` sees engine types transitively,
because those modules' signatures return or take engine types
(`PokedexCatalog.CHARIZARD: Species`, `TextRenderer.render(event:
BattleEvent, ...)`, AI strategies return `TurnChoice`). Other
dependencies are `implementation`.

**Inside `:engine`, `internal` marks the non-contract surface:** whole
packages (`engine/item/*`, `engine/ability/*`, `gen/simplified/*`) plus
single-file helpers (`HazardResolver`, `SwitchOutClearing`, default
`DamageCalculator` / `SpeedResolver` implementations, concrete
`Ruleset` objects, `TypeChart` charts). External modules can import
only the types that are part of the public contract — domain models,
events, pipeline/loop contracts, concrete phases, and extensibility
interfaces. See diary 068 for the full audit.

## Data layering

```
Species  →  Pokemon  →  PokemonState
(what)      (who)       (in battle)
```

- **`Species`** — what a Charizard *is*. Base stats, types. Shared across all
  Charizards. Loaded via `Pokedex`.
- **`Pokemon`** — a specific Charizard. Species + level + IVs + EVs + Nature.
  Knows `maxHp` and how to compute stats via the Gen V+ formula.
- **`PokemonState`** — that Charizard *in battle*. Current HP, stat stages,
  status, volatiles, ability, item.

Each layer changes at a different rate, and all fields are `val`. State changes
happen exclusively through events.

## The event-sourcing shape

The engine's defining choice is that **events are the sole means of state
mutation**. Phases never rewrite state directly; they emit events, and events'
`apply` methods produce the next state. Consequences:

- The event log *is* the audit trail, the undo buffer, the replay, and the
  rendering input.
- Adding a new mechanic is *typically* adding a new event subclass + the phase
  that emits it. Existing code stays untouched (open/closed).
- Events split by concern across multiple files under a shared sealed interface
  — see `engine/` for the current catalog. Events that are purely informational
  return the state unchanged; they still appear in the log.

For the current split between `GameEvent` (applies to `BattleState`) and
`ControlEvent` (applies to `PipelineState`, used for mid-turn input), see diary
061.

## Phases and the pipeline

The pipeline is a list of `Phase` functions run in order. Each phase reads the
current state plus any choices, and returns events to apply. The current phase
order and responsibilities live in `engine/src/main/kotlin/com/pokemon/battle/phase/`;
historically the set has been `MoveOrderPhase → SwitchPhase → MoveExecutionPhase
→ EndOfTurnPhase`, with additions as mechanics shipped.

New phases slot in without touching existing code. Phases are pure functions of
their inputs, which is what makes them testable in isolation.

## Game loop

`BattleLoop` wraps the pipeline: collects choices, runs turns, handles faint
replacements (with switch-in ability triggers), and checks win conditions.
`TurnRecord` separates pipeline events from replacement events. `BattleResult`
reports the winner, final state, and full turn history. For the current
constructor — including `InputResponder` for mid-turn prompts added in diary
055 — see `engine/loop/BattleLoop.kt`.

## Event-stream consumers

The `BattleEvent` stream is the universal output. Every consumer reads the same
stream and interprets it for its own purpose (rendering, analytics, persistence,
replay, metrics, UIs). Diary 042 makes this framing explicit.

### The module-placement rule

> **The engine module has zero I/O and zero serialization deps.** Pure-transform
> consumers (events in, values out; no file / network / DB / format-specific
> libs) can live in the engine module. Any consumer that touches I/O,
> serialization, or a client toolchain goes in a separate module.

Why this rule holds the line:

- `./gradlew :engine:test` stays fast (no Node, no HTTP, no DB drivers pulled in).
- Adding a React UI later doesn't slow down engine tests by one millisecond.
- Each consumer module owns its own deps — upgrading `ktor-client` in
  `data-ingestion` doesn't touch `engine`'s classpath.
- Dependency direction is enforced: engine knows nothing about any client;
  clients depend on engine's stable data types.

Consumers live in dedicated peer modules: `:render` (events-to-text),
`:analytics` (BattleAnalyzer, ReplayExporter), `:cli` (interactive entry
points), `:data-ingestion` (external catalogs), `:ai` (choice
strategies). Future web UI / MCP server / replay viewer would each be a
new peer module depending on `:engine` + whatever else they need. For
the current module map see `settings.gradle.kts`.

### Choice providers (the input side of the same layering)

`ChoiceProvider` is the input analogue. Same rule: pure providers (random AI,
heuristic AI) live in the engine; I/O-bound ones (stdin, HTTP, MCP tool handler)
live in the corresponding consumer module. Move pools are owned by the provider,
not the `Pokemon` model (see Lessons Learned).

## Multi-Gen Support

Different generations change the rules. The architecture supports this
by building different phase implementations and different registry contents,
*not* by config flags threaded through the engine.

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

Injectable seams today include `DamageCalculator` and `SpeedResolver` (both `fun
interface`, each with a Gen V default implementation). Others will be extracted
when a second gen's rules demand it.

### Registry pattern for items, abilities, and moves

Items, abilities, and moves are large enum-like catalogs where each entry has its
own behavior, and the catalog differs across gens (e.g. Life Orb didn't exist in
Gen 3; Fairy type didn't exist before Gen 6; Eviolite is Gen 5+). Hardcoding
`when (item)` branches inside calculators and phases doesn't scale — at 5 gens
and 50 items you get O(items × callers) scattered `if` branches.

**The pattern:** each entity has an `Effect` interface with default no-op hooks
into the pipeline. Each instance is a small singleton implementing only the
hooks that apply. A registry maps enum values to their effects, and the lookup
itself can be context-aware (e.g. `ItemRegistry.effectForHolder(pokemon)`
returns null if Klutz is suppressing the item). Callers consult the registry:

```kotlin
// sketch; see engine/item/ for the live interface
interface ItemEffect {
    val item: Item
    fun attackerDamageModifier(attacker: PokemonState, move: Move): Double = 1.0
    fun endOfTurn(pokemon: PokemonState, slot: Slot): List<BattleEvent> = emptyList()
    // ... additional hooks as mechanics demand
}

object LifeOrbEffect : ItemEffect { /* overrides only the hooks it needs */ }

val itemMod = ItemRegistry.effectForHolder(attacker)?.attackerDamageModifier(attacker, move) ?: 1.0
```

**Benefits:**

- Adding a new entity is one file + one registry entry — no scattered edits.
- Gen-specific registries (`GenIVItemRegistry`, `GenVItemRegistry`) become the
  natural seam for multi-gen support — the calc stays untouched, only the
  registry changes.
- Behavior is colocated with identity.
- No unreachable branches in `when (enum)` switches elsewhere.

Applies to: items, abilities, and move-level effects beyond the `MoveEffect`
data hierarchy (where the `when` in `MoveExecutionPhase.resolveEffect` is the
current dispatch site; registry extraction tracked in diary 029). See diary 026
for the first extraction, diary 033 for context-aware lookups (Klutz), and the
Lessons Learned section below.

### What NOT to do

- Don't add gen parameters to phases — separate implementations instead.
- Don't put gen-specific constants in the model.
- Don't put gen-specific logic in events — `apply()` is mechanical.
- Don't scatter `when (item)` / `when (ability)` branches inside calculators or
  phases. Extract behavior into per-entity `Effect` objects and consult the
  registry.

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

Mechanics explored conceptually but not yet scoped. For what is and isn't
implemented *right now*, the source tree and diary index are authoritative; the
table below captures the shape of the extension rather than the shipping status.

| Scenario | Approach | Complexity |
|----------|----------|------------|
| Pursuit | Pre-execution switch awareness | Medium |
| Mega Evolution / Dynamax | Pre-order transformation phase + compound `TurnChoice` | Medium |
| Ally targeting (Heal Pulse) | `MoveTarget.ONE_ALLY` + new `resolveTargetSlots` case | Low |
| Triples adjacency | `Slot.position` + adjacency helpers | Low |
| Multi-turn moves (Fly, Dig, Solar Beam) | Volatile + deferred phase | Low / Medium |
| Choice locks, Encore, Disable | Per-Pokemon move restrictions in choice layer | Medium |
| Substitute | Volatile + damage interception via existing hooks | Low |
| Team selection / team preview / learnset validation | Layer *above* the engine | Out of scope |
| Rotation battles | Different `BattleState` shape (active vs bench within side) | Out of scope |

## Lessons Learned

Principles discovered during development, not obvious at the start.

**Species pools, item catalogs, and ability catalogs belong in `:data`,
not `:engine`.** The engine resolves whatever it's given — what
specific Charizard exists, what the full item list is, what moves are
in the pool — is data, not mechanics. Our first `:engine` included all
of `PokedexCatalog` / `MoveDex` as Kotlin objects alongside pipeline
code; diary 065 extracted them to `:data`. The tell: the engine never
iterates over "all species" — it operates on whatever `Species`
instance a consumer hands it. Same shape as "move pools belong to the
choice layer." Diary 066 then extracted `:render` and `:ai` on the
same principle (Showdown puts rendering in the client, AI in the
server; neither belongs in `sim/`). The generalizable test: does the
engine actually read this catalog, or does it only consume one entry
at a time? If the latter, the catalog belongs outside.

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

**The registry pattern is the right answer for large, gen-varying catalogs.** Items,
abilities, and move-level effects are all "large enum-like catalogs where each entry has
its own behavior, and the set differs across gens." Hardcoding `when (item)` branches
inside the damage calc worked at 1 item, looked rough at 3, and would be unmaintainable
at 50 across 5 gens. Extraction to `ItemEffect` objects + `ItemRegistry` put behavior next
to identity, eliminated cross-branch noise in exhaustive `when`s, and gave us the natural
seam for gen-specific variants (`GenIVItemRegistry` vs `GenVItemRegistry`). Do this
*when* the pattern is visible, not before — three items was the sweet spot for us.
Diary 026 has the full story.

**The event log is a first-class data asset, not just a rendering input.** Every
structural decision that keeps events complete, ordered, and serializable pays off in
three directions: rendering (text/HTML/JSON), analytics (win rates, usage stats,
correctness audits), and replay (save, reload, debug). The engine's contribution is
emitting faithful events; everything else — text, metrics, replay viewers — is a
consumer module that reads the stream. Diary 042 makes this framing explicit and walks
through the three analytics categories (gameplay insights, engine self-consistency,
dev performance). The minimum-viable investment to unlock all of this: add
`@Serializable` to every event. Most of the value comes for free from the
event-sourcing shape we already adopted.

**Behavior and rendering want separate registries once the entity count is non-trivial.**
Early on, colocating render strings with effect behavior (one `ItemEffect` file owning
both "what Leftovers does" and "what Leftovers prints") is the right simplicity. Past
10+ entities it becomes a drag: every new hook widens the interface, render strings
bloat effect files, and anything renderer-specific (localization, JSON events, HTML) has
to subclass every effect or swap whole implementations. The fix — demonstrated in
diary 038 — is parallel `Text` interfaces and registries next to each `Effect` registry,
consulted by `TextRenderer` instead of the effect registry. Adding a second renderer
becomes a new `Text*` registry, zero behavior changes. Don't do this split too early; do
it when the effect interface bloats past the TooManyFunctions threshold and render
coupling starts hurting readability.

**Registries should be context-aware at the query level.** Once the engine has multiple
registries (items, abilities, eventually moves), cross-cutting "X suppresses Y"
interactions pile up: Klutz suppresses the holder's item; Embargo suppresses a target's
item via volatile; Magic Room disables all items field-wide; Neutralizing Gas suppresses
abilities. The wrong shape is to scatter `if (ability == KLUTZ)` checks across every
caller. The right shape is for the *lookup itself* to be context-aware:
`ItemRegistry.effectForHolder(pokemon)` returns null if the item is suppressed, hiding
the suppression logic inside the registry. Callers remain generic; adding a new
suppressor (Embargo volatile, Magic Room field) means one signature extension on the
lookup, not an edit in every caller. Diary 033 establishes this with Klutz as the first
example.

**Registries turn multi-gen support into a data-loading problem, not an engine problem.**
Once behavior is extracted from the engine into per-entity effect objects, a gen-specific
variant of the engine is just a different set of objects registered. The engine itself
never asks "what gen am I?" — it consults whichever registry the pipeline was built with.
Diary 032 audited every real move change across 9 gens (Knock Off's power and damage
formula evolution, Defog gaining hazard-clear, Toxic's accuracy changes, Weather Ball's
weather-type switching, Hidden Power's removal, and dozens more) and found **zero cases**
requiring `if (gen == X)` branches in engine code. Every change decomposes into a data
CSV diff or a registry re-registration. This is the strongest architectural validation
we have for the pattern: it scales not just to more entries per gen, but to more *gens*
without engine changes. The registry pattern isn't just a convenience — it's the only
way we've found to keep the engine gen-agnostic.

**The engine doesn't enforce legality.** No learnset validation, no move count limits,
no species-ability restrictions. This wasn't a shortcut — it's a design choice that
makes custom formats (Hackmons, Almost Any Ability) work without engine changes.
Legality is a team-building concern, a layer above the engine.

**Don't calculate — let the code tell you.** Mental math for damage formulas is
error-prone. Run the code with fixed inputs, read the output, assert on that. The
code is deterministic; your head isn't.

**Documentation that describes current shape will drift; documentation that
describes rationale won't.** The original version of this file listed type
signatures, field-by-field `BattleState` layouts, a file-by-file event catalog,
and an "implemented vs future" table. Every refactor (diaries 041, 044, 055,
056, 061) silently invalidated one of them without anyone back-editing. Diary
063 stripped the time-sensitive surface and kept rationale + lessons + concepts,
on the theory that rot-prone prose is worse than no prose. Point readers at
source for shapes; point readers at diaries for history; keep this doc for the
*why*.

# Diary 031: Engine Scope — When to Unify, When to Fork

**Date:** 2026-04-13
**Status:** Analysis / principles — no implementation

## Context

Diary 030 ranked 12 mechanics by engine-disruption. The top entry was **Legends: Arceus's
action-speed scheduler** — no turns, priority queue, agile/strong move styles. I flagged
it as "out of reach without a core rewrite."

The right framing is stronger than "out of reach" — **it's a different engine entirely.**
Legends Arceus, and likely its 2026 follow-up Pokemon Legends: Z-A, don't share an
execution model with mainline. Trying to unify them under one codebase doesn't buy
you anything; it costs you clarity.

This diary makes that line explicit: when is a Pokemon format within our engine's
scope, and when is it a separate engine that happens to share data types?

## The criterion: execution model, not feature count

Whether two formats can share an engine depends on whether they share an **execution
model** — the fundamental "what happens when, in what order, driven by what" of
resolving actions.

Our engine's execution model:
- **Turn-based.** All actors commit choices, then choices resolve in a determined order.
- **Synchronous turn boundary.** A turn starts, things happen within it, a turn ends.
- **Side-based teams.** Actors belong to persistent sides; sides have win conditions.
- **Phase pipeline.** Within a turn, fixed phases process in order (MoveOrder → Switch
  → MoveExecution → EndOfTurn).

Anything that doesn't share that model is a different engine.

## What's IN scope

Anything fitting the turn-based / phase-pipeline / side-based shape:

| Format | In scope? | Notes |
|--------|-----------|-------|
| Singles (all gens 2+) | ✅ Core | The default |
| Doubles (Gen 3+) | ✅ Core | Already supported via slot positions |
| Triples (Gen 5-6) | ✅ Stretch | Adjacency rules add complexity but same model |
| Rotation (Gen 5) | ✅ Stretch | Non-turn "rotate" actions fit as a pre-move phase |
| Horde (Gen 6) | ✅ Stretch | 1v5, still turn-based |
| SOS (Gen 7) | ✅ Stretch | 1v1 scaling to 1v2, slot count changes mid-battle |
| Battle Royal (Gen 7) | ✅ Stretch | 4 sides, win = most KOs when someone empties |
| Max Raids (Gen 8) | ⚠️ Edge | 4 trainers vs 1 boss with shared HP and shield phases. Turn-based but simultaneous input and round-based. Probably in if we generalize `sides` and `WinCondition`. |
| Tera Raids (Gen 9) | ⚠️ Edge | Similar to Max Raids. |
| Pokemon Champions | ✅ Core | Mainline VGC doubles, just with unified gimmick |
| Smogon formats (OU, UU, NatDex, Monotype, Inverse) | ✅ Core | Different rulesets, same engine |

## What's OUT of scope — separate engines

| Format / title | Out because | Shared data? |
|----------------|-------------|--------------|
| **Pokemon Legends: Arceus** | Action-speed scheduler, no turns, agile/strong styles | Species / Move data yes; battle state no |
| **Pokemon Legends: Z-A** (2026) | Similar LA-style action combat per previews | Same as LA |
| **Pokemon GO** | Real-time, charge-move timing, dodge mechanics, CP system | Species data only |
| **Pokemon Unite** | MOBA, real-time, respawns, mid-match leveling, cooldowns | Species names only |
| **Pokemon Masters EX** | 3-person squads, sync pairs, action points, gauge-based moves | Different character concept entirely |
| **Pokemon Stadium / Battle Revolution** | 3D presentation layer over mainline engine | Same engine would be used; but these are *clients*, not engines |
| **Pokken Tournament** | Real-time fighting game | Species data only |
| **Pokemon TCG (physical or TCGL)** | Card game, nothing in common | Only thematic |

For each of these, building "Pokemon X support" into our engine would require either
(a) introducing abstractions so general that the mainline code suffers, or (b) carrying
dead code that never fires for the formats we actually care about. Neither is worth it.

## What a fork would share, and what it wouldn't

If someone wanted to build a Legends Arceus simulator, **they'd share our data layer and
fork everything else.**

Shared:
- `Species`, `Move`, `Ability`, `Item` enums / data classes
- `Type` enum + type chart data (though LA's type interactions are mostly mainline)
- `Pokedex` CSV loader (species stats, typing)

Not shared:
- `BattleState` / `TurnPipeline` / `Phase` — whole different execution model
- `TurnChoices` / `TurnChoice` — no turns
- `MoveExecutionPhase`, `EndOfTurnPhase`, `SwitchPhase` — no phases
- Most events — LA uses its own action/damage model

The right structure: **a shared `pokemon-data` module** (species, moves, types) consumed
by both a `pokemon-battle-engine` module (mainline turn-based) and a hypothetical
`pokemon-la-engine` module (action-speed scheduler). Two engines, one data layer.

## How to decide future scope additions

When a new Pokemon format or title comes out, ask in this order:

1. **Does it share the turn/phase/side execution model?**
   - Yes → probably in scope, evaluate the specific mechanical twists
   - No → separate engine, share only data
2. **Do the mechanical twists fit our seams (Registry, Calculator, Ruleset, etc.)?**
   - Yes → implementation work, not architectural
   - No → diary-length discussion about the specific twist before committing
3. **Is the format popular enough to be worth the work?**
   - Competitive (VGC, Smogon, Champions) → high value
   - Niche single-player variant → low value, maybe just add the mechanics as optional features

## Applying this to known cases

### Legends Arceus (confirmed out)

Action-speed scheduler vs turn-based. **Different engine.** We could build an LA engine
as a sibling project if ever desired, sharing the `pokemon-data` module. Not a
refactoring of this engine.

### Max Raids / Tera Raids (edge case, tentatively in)

4 trainers + 1 boss, shared HP bar, shield phases at HP thresholds, simultaneous input.
Turn-based at its core: each "turn" all four trainers commit moves, boss commits moves,
all resolve. Shield phases are a field-level state machine that fires at HP thresholds.

**Can fit our engine** if:
- `sides: List<Side>` allows 4 "player" sides + 1 "boss" side
- `Side.sharedHp` flag or similar for raid bosses
- `WinCondition` plugs in "KO boss" vs standard "all opponents fainted"

Stretch but feasible. Worth implementing only if we care about the format.

### Pokemon Champions (confirmed in)

It IS our engine's target format — competitive VGC doubles with a unified gimmick slot
and regulation-set rulesets. Most of what we're planning (ability registry, Ruleset,
GimmickState) maps directly to Champions' architecture.

### Stadium / Battle Revolution (in, via presentation layer)

These are 3D presentation shells over the mainline engine. Our engine is the engine;
a Stadium port would be a rendering layer on top. No engine changes needed.

## Related principle: "data is shared, execution is not"

A useful generalization: **species, moves, types, abilities, items are universal Pokemon
concepts.** A Blastoise is a Blastoise whether it's in Mainline Gen 9, LA, GO, Unite, or
Champions. The species data, move data, type data are stable across all those titles
(with some gen-gating).

But **execution models are title-specific**. Turn-based mainline is one. LA's action-speed
is another. GO's real-time-tap-and-dodge is a third. Unite's MOBA is a fourth.

So: **share data, fork execution.** A future "Pokemon multi-title project" would have one
data module and N engine modules. Our engine is one of those N, not all of them.

## Scope summary for this project

**Target:** mainline competitive battle simulation, Gen 3+ (items/abilities), with eventual
Pokemon Champions compatibility. Singles/doubles as core, triples/rotation/Royal as
stretch. Raids as a distinct-but-possible extension.

**Explicitly excluded:** Legends Arceus (Z-A), Pokemon GO, Pokemon Unite, Masters EX,
TCG, Pokken. If anyone builds these later, they'd be sibling projects sharing our
`pokemon-data` module (if we ever extract one).

## Related diaries

- **Diary 030** — 12 architectural twists + 6 meta-lessons for things IN scope
- **This diary (031)** — the *boundary* of what's in scope vs what deserves forking

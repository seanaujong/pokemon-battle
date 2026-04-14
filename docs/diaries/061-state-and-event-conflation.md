# Diary 061: Game vs pipeline conflation in state and events

**Date:** 2026-04-14
**Status:** Complete (2026-04-14). `BattleState` is game-only; `PipelineState` wraps it with resumption bookkeeping. `BattleEvent` is a marker with `GameEvent` and `ControlEvent` sub-hierarchies. Renderer sees only `GameEvent`s. All 246 tests green; CLI plays U-turn mid-turn correctly.

## The observation

While implementing diary 055 Phase 2, two `when`-statement smells surfaced
that point at the same root cause: **game concerns and pipeline-machinery
concerns are conflated in types that shouldn't carry both.**

### Smell 1: `BattleState` holds two kinds of state

Game state (authoritative facts about the battle):
- `slots`, `bench`, `field`, `sideConditions`, `sideHazards`,
  `gimmicksUsedBySide`, `ruleset`, `turn`.

Pipeline-machinery state (transient, relevant only while a turn is mid-
resolution):
- `pendingInput: InputRequest?` — added in Phase 1
- `partialTurnEvents: List<BattleEvent>` — added in Phase 1
- `pausedPhaseIndex: Int?` — added in Phase 2

Both live on one `BattleState`. A caller that wants to reason about
"who has how much HP?" has to ignore three fields that are only
meaningful inside a running pipeline. A caller wiring resumption has to
cherry-pick three fields out of a large data class. Two audiences,
one type.

### Smell 2: `BattleEvent` holds two kinds of events

Game events (things that happened in the battle, worth rendering):
- `DamageDealt`, `SwitchIn`, `MoveAttempted`, `StatusApplied`, ~30 more.

Control events (pipeline state transitions, no in-game meaning):
- `TurnPausedForInput` — Phase 2, Phase 2
- `TurnInputResolved` — Phase 2

Both implement `sealed interface BattleEvent`. The renderer's exhaustive
`when` has branches like `is TurnPausedForInput -> emptyList()` — a
"legal state, but nothing to say" case that signals the type is trying to
represent two different concepts.

A sharper signal: **a consumer that renders a battle should not even know
control events exist.** Today it has to, because the sealed hierarchy
forces exhaustive handling.

## Why this matters

1. **Readability degrades as we grow.** Every new contributor sees
   `pausedPhaseIndex` on `BattleState` and has to figure out "is this a
   game concept or not?" The name tells them; the location misleads
   them.
2. **Renderer bloat will accelerate.** The next mid-turn mechanic
   (Baton Pass, Revival Blessing) adds more control events. Each one
   gets an `is X -> emptyList()` branch. By the time there are 5,
   it's clearly wrong; today with 2, it still looks "fine."
3. **Event log consumers will be wrong by default.** Anything that
   reads `List<BattleEvent>` and renders or inspects game-meaningful
   events will trip over control events. Today we catch it because
   Kotlin's exhaustiveness forces handling. External consumers
   (analytics, replay tools) won't have that safety net.
4. **The `@Serializable`-on-domain pattern (diary 060) makes it
   permanent.** Once control events are serialized into stored logs,
   splitting the hierarchy later means handling mixed-type historical
   data.

## Proposed split

### State

```kotlin
data class BattleState(
    // Game fields only — no pipeline machinery
    val slots: Map<Slot, PokemonState>,
    val bench: Map<Side, List<PokemonState>>,
    val field: FieldState,
    val sideConditions: ...,
    val sideHazards: ...,
    val gimmicksUsedBySide: ...,
    val ruleset: Ruleset,
    val turn: Int,
)

data class PipelineState(
    val game: BattleState,
    val pendingInput: InputRequest? = null,
    val partialTurnEvents: List<BattleEvent> = emptyList(),
    val pausedPhaseIndex: Int? = null,
)
```

`TurnResolution.NeedInput` carries a `PipelineState` (game + resumption
context). `TurnResolution.Completed` carries a `BattleState` (game only)
since there's nothing to resume. Phases accept `BattleState` — they
don't need to know about resumption machinery; the pipeline passes only
the game slice.

This also makes resumption bookkeeping *opt-in*. Today every `BattleState`
is shaped for resumption even when no mechanic uses it. Split lets
non-pausing flows stay simpler.

### Events

```kotlin
sealed interface BattleEvent  // marker for the event log
sealed interface GameEvent : BattleEvent       // renderable
sealed interface ControlEvent : BattleEvent    // pipeline-only

// Today's events implement GameEvent
class DamageDealt(...) : GameEvent
class SwitchIn(...) : GameEvent
// ...

// Phase 2's additions implement ControlEvent
class TurnPausedForInput(...) : ControlEvent
class TurnInputResolved(...) : ControlEvent
```

Renderer accepts `GameEvent`, not `BattleEvent`. Event log is still
`List<BattleEvent>` (mixed). Replay machinery reads both; consumers that
render or inspect game-meaningful output filter to `GameEvent` first.

### What this buys

- Renderer's `when` no longer has "empty" branches for control events.
- Adding a new control event (pause for Baton Pass, etc.) doesn't
  touch the renderer.
- `BattleState` shrinks; its name stops lying about what it holds.
- Phases that don't pause see a simpler type — no `partialTurnEvents`
  to ignore.

## Scope (when we do this)

- Split `BattleState` into `BattleState` + `PipelineState`.
- Split `BattleEvent` into `BattleEvent` / `GameEvent` / `ControlEvent`.
- Every phase's signature updates: `fun resolve(state: BattleState, ...)`
  instead of (today's) state-with-pipeline-fields. Pipeline bridges.
- `TurnResolution.Completed` and `NeedInput` adjust their carried state.
- Test updates: places that read `partialTurnEvents` off `BattleState`
  now read from `PipelineState` (paused cases) or not at all.
- Renderer signature: `render(event: GameEvent, ...)` — force consumers
  to filter first.

Estimate: ~2 hours. Mechanical with some thought at the boundaries.
Good candidate for IntelliJ's semantic refactor (preflight rule #2
applies).

## Why we're not doing this in Phase 2

Same rationale as diary 060 (serialization DTO split): landing the
*behavior* first and refactoring the *shape* second is cheaper than
mixing them. Phase 2's job is to get mid-turn pauses working
end-to-end; once that's on main, this diary's split is mechanical.

The cost of waiting is one more session's worth of contributors
reading "two kinds of state in one type." Acceptable.

## Updated evidence after Phase 2+3 shipped

The backlog review (post-555 Phase 3) found three new pieces of code
that this refactor would actively *simplify*, not just polish:

1. **`MoveExecutionPhase.slotAlreadyActed` and `slotIsMidSelfSwitch`**
   reverse-engineer pipeline progress by scanning
   `state.partialTurnEvents` for `MoveAttempted` + absence of
   `TurnPausedForInput`. With explicit phase progression on
   `PipelineState`, these heuristics disappear — the pipeline tells the
   phase what to do, instead of the phase guessing.

2. **`BattleState.pausedPhaseIndex`** is the third pipeline-state
   field on the "game state" type (after `pendingInput` and
   `partialTurnEvents`). Three fields a game-only consumer must ignore.

3. **`MoveStep` data class** threads an optional `pauseRequest`
   through `resolveSelfSwitch` → `executeMove` → `checkStatusThenExecute`
   → top-level `resolve`. Fine for one mechanic; will compound for the
   next. With `PhaseOutput.Paused` already carrying the request and a
   cleaner pipeline-progression model, this plumbing simplifies.

These weren't smells *before* Phase 2 — they were the natural shape of
"add mid-turn prompts on top of a single conflated state." Phase 2
proved the conflation costs real code. The refactor is now load-bearing,
not aesthetic.

## Tooling note

This refactor includes:
- Splitting `sealed interface BattleEvent` into a marker + two
  sub-hierarchies (`GameEvent`, `ControlEvent`) → ~35 event subclasses
  need their declared supertype changed.
- Moving 3 fields out of `BattleState`.
- Updating `apply()` signatures on the two control events to take
  `PipelineState` instead of `BattleState`.

Per preflight rule #2 in CLAUDE.md, the ~35-event retype is exactly
IntelliJ's home turf (a semantic refactor knows which subclasses to
move; sed would be brittle and noisy). The plan: hand-write the new
types and the pipeline rewiring; ask the user to run IntelliJ for the
mass retype of event subclasses to `GameEvent`.

## Meta-note: refactor backlog

This is at least the fourth diary of shape "recognized smell, deferred
the fix":

- **Diary 058** — naming + organization review. Deferred mostly
  correctly, but logged 7 observations.
- **Diary 060** — event serialization DTO split. Deferred until a
  forcing function.
- **Diary 061** — this one. Deferred until Phase 2 lands.

Each deferral is individually justified ("don't fix X while
implementing Y"). But the backlog is growing and deserves a
**step-back session** once Phase 2 + CLI integration land. The
alternative is hitting 3-4 of these simultaneously when we add the
next big mechanic, and having to untangle them under pressure.

Proposed trigger: after diary 055 Phase 3 (interactive CLI with
mid-turn prompts) ships, open a **refactor-backlog review** diary that
reads the "observed but deferred" entries across 058/060/061 and
decides which to tackle. Prioritize by (a) which one blocks the next
feature, (b) which one is cheapest, (c) which one compounds if
deferred further.

## Related

- **Diary 055** — Phase 2 surfaced these smells.
- **Diary 060** — companion refactor (serialization DTOs). Both
  deferred for the same reason (land behavior, refactor shape).
- **Diary 058** — earlier naming review. Same deferral pattern.

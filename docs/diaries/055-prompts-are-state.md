# Diary 055: Prompts Are State

**Date:** 2026-04-14
**Status:** Phases 1, 2, and 3 complete (2026-04-14). The CLI plays U-turn end-to-end with mid-turn switch prompts.

## The decision

Mid-turn inputs (U-turn's switch target, Baton Pass's receiver, Revival
Blessing's revivee, hypothetical Gen-5 "set" post-KO picks, etc.) are
modeled as **part of `BattleState`**, not as a side channel.

Concretely, `BattleState` gains (or a companion type gains) a notion of
*"this turn is paused, awaiting input of shape X."* The engine becomes a
pausable state machine: `resolve(state, choices, responses)` returns
either `NeedInput(state, prompt)` or `Completed(state, events)`.

See diary 054 for the full tradeoff write-up. This diary is the commit.

## Why now

Prior framing ("defer until a mechanic forces it") was correct for
simulator-only use. **The driver changed: we want interactive play.** When
a human plays, U-turn hits, then the game asks "switch to who?" — a
genuine mid-turn prompt. Pre-selection (today's `TurnChoice.UseMove.switchTo`)
only feels right from a simulator's perspective.

## Why Option A (state) over Option B (side channel)

1. **Consistency with existing invariants.** This codebase has chosen
   immutability and event sourcing from day one. A pause point that
   lives in `BattleState` extends those invariants; a side-channel
   session breaks them. Every prior diary would point toward A.
2. **Real capability, not speculative.** Any mid-battle state can be
   serialized and resumed — from another client, at a later date, on
   a different machine. That's useful for AI training, cloud saves,
   replay tooling, debugging.
3. **Showdown, the reference simulator, uses this model.** Ten years of
   evidence it scales to full-format Pokemon.

The cost is ergonomics for phase authors: a phase that needs input has
to be split into before/after halves. That cost is paid by us; the
cleaner consumer API is a permanent win.

## The model

### New types

```kotlin
// In model/ or engine/
sealed interface InputRequest {
    val requestId: InputRequestId // stable identity, referenced by response
}
sealed interface InputResponse {
    val requestId: InputRequestId
}

data class SwitchTargetRequest(
    override val requestId: InputRequestId,
    val slot: Slot,
    val reason: SwitchReason, // e.g. SELF_SWITCH_MOVE, POST_KO
    val validTargets: List<Int>,
) : InputRequest

data class SwitchTargetResponse(
    override val requestId: InputRequestId,
    val benchIndex: Int,
) : InputResponse
```

### Pause point in `BattleState`

```kotlin
data class BattleState(
    // existing fields...
    val pendingInput: InputRequest? = null,
    val mostlyResolvedTurnEvents: List<BattleEvent> = emptyList(),
)
```

The paused state contains the events already applied this turn (so
rendering has something to show) plus the `InputRequest` the engine is
waiting on.

### Return type from `TurnPipeline.resolve`

```kotlin
sealed interface TurnResult {
    data class NeedInput(val state: BattleState) : TurnResult
    data class Completed(val state: BattleState, val events: List<BattleEvent>) : TurnResult
}
```

Callers inspect the result; if `NeedInput`, they obtain a response and
call `resolve` again with the updated state. `BattleLoop` handles this
cycle transparently.

### Behavior of existing phases

**No change** for any phase that doesn't need mid-turn input. The `when`
in `TurnPipeline.resolve` checks `state.pendingInput` — if non-null, it
consumes the matching response from the caller's argument and resumes
from where the last phase left off; otherwise it runs phases normally.

Phases that *do* need input return a new sealed variant signaling
"halt here with request X." The pipeline packages that into
`NeedInput(state.copy(pendingInput = request, mostlyResolvedTurnEvents = eventsSoFar))`.

## Migration plan

Three phases, each independently shippable.

### Phase 1 — plumbing only, no consumer ✅ shipped 2026-04-14

- [x] Add `InputRequest` / `InputResponse` sealed hierarchies (empty — Phase
      2 adds concrete variants)
- [x] Add `pendingInput: InputRequest?` + `partialTurnEvents: List<BattleEvent>`
      fields to `BattleState` (both default — no existing behavior changes).
      Chose `partialTurnEvents` over the diary's `mostlyResolvedTurnEvents`
      (shorter, same semantics).
- [x] Add `TurnResolution` sealed type (note: renamed from diary's `TurnResult`
      per diary 057 to avoid collision with `BattleLoop.TurnRecord`)
- [x] `TurnPipeline.resolve` returns `TurnResolution`; all existing phases
      produce `Completed`
- [x] Added `resolveToCompletion` convenience: asserts `Completed`, returns
      the inner variant. Tests and CLI's PlayMain use this since they don't
      yet handle pauses. `BattleLoop` uses `resolve` directly and
      pattern-matches (throws defensively on `NeedInput`).
- [x] All existing tests pass without logic changes (30+ test files touched
      for the call-site rename; no behavior-level changes anywhere).

Green signal achieved: `./gradlew test ktlintCheck detekt` green on main.

### Phase 2 — migrate U-turn as first real consumer ✅ shipped 2026-04-14

- [x] `Phase` signature changed to return `PhaseOutput` (`Completed`
      or `Paused(events, request)`); 5 phase implementations updated.
- [x] `MoveExecutionPhase` self-switch branch: pre-selected `switchTo`
      still works (legacy fallback); otherwise emits `Paused` with a
      `SwitchTargetRequest`.
- [x] On resume, `MoveExecutionPhase` looks at
      `state.partialTurnEvents` for `TurnInputResolved`, extracts the
      `SwitchTargetResponse`, and runs the post-pause switch sequence.
      Slot-skipping helpers (`slotAlreadyActed`,
      `slotIsMidSelfSwitch`) prevent duplicate emission.
- [x] `TurnPipeline.resolve` returns `NeedInput` when a phase pauses;
      new `TurnPipeline.resume(state, choices, response)` continues from
      the paused phase.
- [x] `BattleLoop` accepts an optional `InputResponder` and drives the
      pause/resume cycle internally via `resolveTurnWithPauses`.
- [x] New tests: pause emits `SwitchTargetRequest` with damage
      visible; resume completes the switch sequence.
- [x] `TurnChoice.UseMove.switchTo` still honored as the pre-selected
      path so existing tests keep working without churn.

### Phase 3 — interactive CLI ✅ shipped 2026-04-14

- [x] New `InputResponder` interface alongside `ChoiceProvider` /
      `FaintReplacementProvider`.
- [x] `HumanChoiceProvider` implements `InputResponder` (stdin-driven
      mid-turn prompt). Removed the pre-select sub-prompt — U-turn now
      uses the proper mid-turn UX.
- [x] `TypeAI` and `SidedAI` implement `InputResponder`. `SidedAI`
      routes by `request.userSlot.side`. `SidedAI` constructor accepts
      `SideProviders` (data class) instead of `Pair`.
- [x] `PlayMain.playBattle` drives pauses inline, rendering events
      incrementally so the human sees damage land before being asked
      for a switch target.
- [x] Live smoke test: pick U-turn → see damage → get prompted for
      replacement → see switch happen → opponent responds.

## What stays the same

- Event sourcing: every state change still goes through a `BattleEvent`.
  The `pendingInput` field is set by a new event (`TurnPaused`) and
  cleared by another (`TurnResumed`). Replay from events alone still
  reconstructs every intermediate state.
- All data immutable; no phase mutates state.
- No coroutines, no `Flow`, no `suspend`. Synchronous pausable machine.
- Streaming events (diary 054 C4) is still orthogonal — can add a
  `Flow<BattleEvent>` variant whenever a UI client asks. Not part of
  this work.

## Non-goals for this diary

- **Networked multiplayer.** The serialization-friendliness of
  Option A makes it *possible* later, but we're not building it now.
- **Replay tooling.** Likewise, Option A makes it trivial eventually;
  not part of Phase 1–3.
- **Migrating every self-switch / revival move to mid-turn prompts in
  one go.** U-turn first; Baton Pass / Revival Blessing / Parting Shot
  follow the template once it's proven.

## Exit criteria — when would we reconsider?

Revisit Option A vs Option B if any of these happens:
1. Phases need to split into 3+ halves to express straightforward
   mechanics (signals the state machine is becoming a pseudo-coroutine
   and we should just use real coroutines).
2. Adding a `pendingInput` variant for a new mechanic routinely requires
   significant pipeline refactoring (suggests the abstraction isn't
   pulling its weight).
3. Replay / serialization semantics break in ways we can't fix cleanly.
4. A concrete coroutine-based rewrite would *dramatically* simplify
   the same guarantees, measured on real code not hypotheticals.

None of these are expected. If any materialize, the fact that events
remain plain data (and phases remain the unit of extension) means a
rewrite isn't catastrophic — but it's also not free.

## Forward notes (surfaced during implementation)

- **Serialization DTO split.** Phase 2 adds `@Serializable` directly to
  `InputRequest` / `InputResponse` / `SwitchTargetRequest` / `SwitchTargetResponse`.
  This matches the existing pattern for `BattleEvent` (diary 050), but couples
  domain field names to the on-disk format. The Species/SpeciesJson split
  (diary 041) avoided this for data loading; events and inputs still have the
  coupling. Worth a future diary to migrate the whole event + input hierarchy
  to a DTO layer at once (piecemeal would be inconsistent).

## Open questions

- **Request identity.** `InputRequestId` — is it a turn-scoped counter,
  a UUID, or a structural hash of (turn#, phase, request-kind)? Needs
  to survive serialization. Leaning toward turn-scoped counter for
  simplicity, upgraded later if needed.
- **Multiple concurrent prompts.** Can two prompts be outstanding
  simultaneously (e.g. both sides KO'd, both picking replacements)? If
  yes, `pendingInput` becomes `pendingInputs: List<InputRequest>`. Can
  defer — Phase 2 (U-turn) is always one-at-a-time.
- **`ChoiceProvider` vs new `InputResponder`.** Two ways to extend the
  BattleLoop's input surface. The existing `ChoiceProvider` is already
  being called per-turn; generalizing it to answer mid-turn prompts
  keeps the interface count low but widens a single contract.
  Introducing `InputResponder` separates concerns but adds a type.
  Decide in Phase 2 when we have a concrete consumer.

## Related

- **Diary 039** — `Ruleset.canUseMove` for pre-turn validation. The
  mid-turn equivalent is `Ruleset.canRespond(state, request, response)`,
  added if/when Phase 2 surfaces a need.
- **Diary 054** — the tradeoff that led to this decision.
- **Diary 036** — `Ruleset` stub; likely grows methods here too.

# Diary 054: Interactive Turns & Streaming Events

**Date:** 2026-04-14
**Status:** Thinking document — no action required

## Headline finding

The real architectural question is not "coroutines or not?" — it is **"are
mid-turn prompts part of `BattleState`, or a side channel?"** Every downstream
API decision (suspend, `Flow`, pausable state machine) is a consequence of that
choice. Revisit when, and only when, we hit a mechanic that genuinely cannot be
pre-selected at move-choice time — which is rarer than mainline UX suggests.

## Goal

Understand what it would take for the engine to support:

1. A CLI that asks for input after each turn (no ahead-of-time scripting).
2. A Showdown-like real-time client that streams events as they resolve.
3. A mainline-simulator use case with mid-turn prompts (U-turn, Baton Pass,
   post-KO replacement before opponent acts, bag item use, Revival Blessing).

Decide whether to act now or defer, and if we act, scope the change.

## What works today

- `BattleLoop` already pulls choices per-turn via `ChoiceProvider` (see
  `engine/src/main/kotlin/com/pokemon/battle/loop/BattleLoop.kt:12-14,46`).
  A CLI can block on input inside the provider — no ahead-of-time scripting
  is actually required for the end-of-turn-only case.
- `FaintReplacementProvider` handles post-KO replacements (`BattleLoop.kt:16-21`).
- **U-turn and Volt Switch are already shipped** (`MoveDex.kt:50-68`,
  `MoveExecutionPhase.kt:195-254`). The pattern: `TurnChoice.UseMove` carries an
  optional `switchTo: Int?` that the caller selects at move-choice time. If the
  self-switch move lands damage, the pipeline consumes the pre-selected
  replacement; otherwise it's ignored. No mid-turn prompt required.
- Events are plain data (diary 050 confirmed they serialize). State is
  immutable. Nothing in the types prevents remoting.

So the "requires all moves up front" framing is a misread — but the deeper
concerns behind it are real.

### Implication for the diary's motivating examples

Most of the original "mid-turn prompt" list actually fits the up-front-choice
pattern cleanly:

| Mechanic | Can pre-select? | Notes |
|----------|-----------------|-------|
| U-turn / Volt Switch | ✅ | Already shipped via `switchTo` |
| Parting Shot | ✅ | Same pattern as U-turn |
| Baton Pass | ✅ | `switchTo` plus volatile-transfer flag on the event |
| Revival Blessing | ✅ | `TurnChoice.UseMove` gains a `reviveTarget: Int?` |
| Post-KO replacement (default "shift" style) | ✅ | Already handled by `FaintReplacementProvider` |
| Gen-5 "set" style (replace *before* opponent's move after KO) | ⚠️ | This *is* a genuine pause — but it's a UX style toggle, not standard competitive play |
| Bag item use mid-turn | ⚠️ | Mainline-only, out of scope today |

**Mid-turn prompts are a mainline UX pattern, not a simulation requirement.**
Showdown, the reference simulator, chooses move + switch target together at
decision time. If we never ship a mainline-faithful UI, we may never need them.

## The real constraints

### C1. Turn is the atomic unit of resolution

`TurnPipeline.resolve` (`engine/src/main/kotlin/com/pokemon/battle/engine/TurnPipeline.kt:9-18`)
runs every phase to completion and returns the finished `List<BattleEvent>`.
A UI that wants to animate damage → faint → hazards-on-switch-in in sequence
gets them as a batch and has to replay for timing. Workable for CLI;
awkward for real-time UI.

### C2. No mid-turn choice points

`ChoiceProvider.getChoices` is called once before the pipeline runs.
`FaintReplacementProvider.getReplacement` is called only *after* the
pipeline finishes (`BattleLoop.kt:77-103`). The pipeline itself cannot
pause and ask.

Per the table above, most mechanics that look like they "need mid-turn input"
don't — they fit the pre-selected-choice pattern that U-turn already uses.
The residual genuine mid-turn need is narrow: mainline UX styles (Gen-5 "set",
bag items, move-learning prompts) that are out of our current scope.

Pursuit and similar "trigger on switch" effects are interesting: they're
mid-turn *reactions* to a choice, not mid-turn *prompts*. They should fit
the current model by reordering events within the move-execution phase.

### C3. Synchronous blocking

`ChoiceProvider` is a blocking `fun interface`. A networked server waiting
on a remote player would tie up a thread. Not a correctness issue;
a scalability one.

### C4. Event emission is bulk, not streamed

Relevant for the same reason as C1 but called out separately because the
fix is different: event streaming is about *observation*, mid-turn prompts
are about *input*. A `Flow<BattleEvent>` fixes streaming; it does not by
itself fix C2.

## The actual architectural fork (read this before picking an API)

If we ever do need genuine mid-turn input, two structurally different
models are on the table. **The choice between them is downstream of a
more fundamental question: do prompts belong in state?**

### Model question: is a pending prompt part of `BattleState`?

**Option A — prompts are state.** A turn may halt in a `BattleState`
containing a `pendingInput: InputRequest?` field. The engine is a pure
function `resolve(state, input?): NextState`. Replays are trivially
reconstructable from *just state transitions* — no auxiliary input log.
Natural fit for our event-sourced invariant.

**Option B — prompts are side channel.** A turn-resolution session is a
stateful object that emits events and requests and expects responses,
outside the `BattleState` snapshot. Replays need state *plus* a log of
responses given. The engine becomes a session, not a function.

Option A is more aligned with everything the codebase has built so far
(immutability, event sourcing, state-is-everything). Option B is what
most UI-first engines pick because it makes the implementation easier
at the cost of a subtler replay model.

### API shape follows from the model choice

| Model | Natural API shape | Ergonomic for |
|-------|-------------------|---------------|
| A (state) | **Pausable state machine**: `resolve(state, choices) → Either<NeedInput(prompt, partialState), CompletedTurn>` | Consumers, replays, Java-friendly |
| B (side channel) | **`suspend` + `Flow`**: `suspend fun resolve(state, choices, requestInput): Flow<BattleEvent>` | Phase authors (linear code) |

The `suspend`-based sketch below is *one* concrete realization of Option
B. A pausable state machine is a concrete realization of Option A. We
shouldn't pick the API shape before picking the model, because they
enforce different mental models on consumers forever after.

Showdown, for the record, uses Option A (pausable state machine). It's
the source of its determinism guarantees.

### Sketch of an Option-B `suspend` API (for comparison only)

```kotlin
fun interface Phase {
    suspend fun resolve(
        state: BattleState,
        choices: TurnChoices,
        requestInput: suspend (InputRequest) -> InputResponse,
    ): Flow<BattleEvent>
}
```

This is one change to the core abstraction that addresses C1, C2, C3, C4
together. But it is a **hard-to-reverse** change — every phase, every
test, every client touches this signature. It also commits us to Option B
(side-channel prompts) structurally.

### Decoupling note: streaming is independent

C4 (event streaming) does **not** have to ride on whichever model we pick.
`resolve(...)` returning `Flow<BattleEvent>` is a non-breaking additive
extension of today's `List<BattleEvent>` API (keep `resolve` as a convenience
that collects the flow). We can ship it any time there's a UI client that
benefits. Lumping it into "the one hard-to-reverse change" inflates the
apparent cost of the mid-turn-prompt decision.

## Does this break parallel workflows? (CLAUDE.md §"Parallel Work")

**Yes, significantly, if done as one PR.** A signature change to `Phase`
and `ChoiceProvider` ripples across every phase file, every pipeline
test, the battle loop, the renderer, and any AI choice provider. That is
exactly the "must run alone" invasive-task profile described in
CLAUDE.md's conflict-analysis section — we wouldn't want to run it
alongside other feature work in parallel worktrees.

**Mitigation if we decide to do it:**

- Land it as one dedicated branch, nothing else running in parallel.
- Budget a freeze window (similar to the module split in diary 053).
- Or: split the change into phases that are individually reversible:
  1. Introduce `suspend` on `ChoiceProvider` / `FaintReplacementProvider`
     only. `runBlocking` at the call site. No phase signature change yet.
     This is small and backwards-compatible for every callsite that
     doesn't need suspension.
  2. Introduce a streaming variant of the pipeline (`resolveFlow`) that
     returns `Flow<BattleEvent>`, keeping `resolve` as a convenience
     wrapper that collects to a list. No existing caller breaks.
  3. Add the `requestInput` callback to `Phase.resolve` only when the
     first mid-turn-prompt mechanic (U-turn is the natural first) lands.
     At that point we have a concrete consumer driving the design,
     instead of speculating.

Phased approach is clearly better. It preserves the open/closed property
and keeps each step small enough to parallelize around.

## Does this break outside APIs?

**Current outside surface:**
- `BattleLoop` is consumed by tests only today (no external client yet).
- `data-ingestion` module (diary 041) writes species JSON; does not
  consume the engine's runtime types. Not affected.
- No published artifact, no SDK, no REST API. Our "outside" is internal.

**If we had clients:**
- CLI: fine. A blocking CLI provider wraps a `suspend` provider with
  `runBlocking`.
- Showdown-like web server: benefits from the change. Today they would
  need to thread-block on `ChoiceProvider`; after, they can suspend
  cleanly.
- AI agents (we have `engine/.../ai/`): the `ChoiceProvider` contract
  stays the same shape, just gains `suspend`. AI providers that compute
  synchronously are unaffected aside from the keyword.
- Event serialization (diary 050): unchanged. Events remain plain data.

**Non-JVM consumers (hypothetical):** `suspend` is a Kotlin-only concept.
If we ever expose the engine via Kotlin Multiplatform to JS/native, or
via a Java-friendly API, `suspend` forces a `CompletableFuture`-style
bridge at the boundary. Not a blocker, but worth knowing. Today: no
such consumer exists.

## Recommendation

**Defer everything. Revisit when a mechanic or UX actually forces the issue.**

Rationale:
- C1 and C4 (streaming, bulk emission) don't hurt us today. No UI client
  exists; streaming can be added additively whenever one appears.
- **C2 (mid-turn prompts) is weaker motivation than the original diary
  assumed.** U-turn is already shipped with pre-selected `switchTo`.
  Baton Pass, Revival Blessing, Parting Shot all fit the same pre-selection
  pattern. The only genuinely-mid-turn cases are mainline UX features
  (Gen-5 "set" style, bag items, move-learning prompts) that are out of
  scope.
- C3 (scalability) is hypothetical until we have a networked client.
- Speculating an abstraction without a driver violates the "don't
  design for hypothetical future requirements" guidance in CLAUDE.md.

**When to revisit this diary:**
1. We commit to a mainline-faithful UI (Gen-5 "set" style, bag, etc.).
2. A networked-multiplayer simulator surfaces (scalability forcing function).
3. We want streaming events for a real-time UI (but that's independent —
   handle additively).

**Before writing code when that happens:** answer the headline question
first. State or side channel? Everything else follows from there.

**Action items for this diary:** none — it's a thinking document.

## Open questions

- Does the choice-lock work in diary 039 tell us anything about where
  mid-turn state belongs? (It validates *pre-turn* choices; the mid-turn
  question is different but the location of validation might generalize.)
- If we went with Option A (pausable state machine), what does the
  returned `NeedInput` shape look like — does it carry a `BattleState`
  mid-turn snapshot, a list of partial events, both? This is the "is a
  half-turn a first-class state?" question.
- How does Option A interact with `Ruleset.canUseMove` (diary 039)? A
  `NeedInput` that asks for a switch target would want the ruleset to
  validate the response in the same way it validates move choices.

## Look-ahead

- No engine work queued here. When a mainline-faithful UI, networked
  simulator, or first genuine mid-turn mechanic lands on the roadmap,
  open a new diary that starts by answering the headline question
  (state or side channel) before writing any code.

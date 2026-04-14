# Skill: Add a battle event

**Scope:** `:engine` (event class + `apply`), `:engine` (event serialization DTO),
`:render` (render branch), wherever the event is emitted.
**Level:** user goal.
**Primary actor:** contributor adding a new kind of observable moment in a turn.

## Stakeholders and interests

- **Contributor** — wants the event to flow through the pipeline, render, and
  serialize without editing every consumer individually.
- **Analytics / replay consumers** — want events to be Serializable-stable so
  saved replays load on a later engine version.
- **Engine maintainers** — want the compiler to enforce exhaustiveness via the
  sealed `BattleEvent` hierarchy; no event should be silently ignored.

## Preconditions

- You've confirmed no existing event covers what you need. Reusing (e.g. emitting
  `ItemDamage` from a new hook) is strictly cheaper than adding a new variant.
- Green `./gradlew test`.

## Success guarantees

- The new event is a `GameEvent` (mutates `BattleState`) or `ControlEvent`
  (mutates `PipelineState`) — not both.
- `apply(state)` is pure: no logging, no I/O, no game-rule conditionals. If
  there's an "if" about game rules in `apply`, the logic belongs in a phase.
- The event round-trips through the JSON DTO layer; `EventSerializationTest`
  covers it.
- `TextRenderer` renders it (or explicitly returns `emptyList()` for silent
  events).

## Trigger

A phase wants to record something happening that doesn't fit an existing event
shape — a new hazard type, a new "field effect set" moment, a new mid-turn
pause reason, etc.

## Main success scenario

1. **Pick `GameEvent` vs `ControlEvent`.** `GameEvent.apply(state)` returns a
   new `BattleState`. `ControlEvent.applyTo(pipeline)` returns a new
   `PipelineState` and is used for mid-turn input and resumption bookkeeping
   (diary 061). Almost every new event is a `GameEvent`.
2. **Find the topical events file** in
   `engine/src/main/kotlin/com/pokemon/battle/engine/`. Core events
   (`MoveAttempted`, `DamageDealt`, `PokemonFainted`, `ProtectBlocked`,
   `MoveOrderDecided`, `MoveFailed`) live in `BattleEvent.kt` itself next
   to the sealed hierarchy. Per-concern events live in grouped files:
   `StatEvents.kt`, `HazardEvents.kt`, `ItemEvents.kt`, `AbilityEvents.kt`,
   `StatusEvents.kt`, `WeatherEvents.kt`, `SideConditionEvents.kt`,
   `SwitchEvents.kt`, or `TurnInputEvents.kt` for control events. Add
   your `data class` in the topically-closest file; if none fits,
   `BattleEvent.kt` is a reasonable default.
3. **Implement `apply`.** It must be pure and deterministic. Use
   `state.withPokemon(slot, pokemon.copy(...))` or `state.copy(field = ...)`
   — never mutate. If you find yourself writing `if (someGameRule) { ... }
   else { ... }`, stop: that's phase logic. The event should just record
   the state change.
4. **Add the DTO** in
   `engine/src/main/kotlin/com/pokemon/battle/engine/serialization/BattleEventJson.kt`.
   Pattern: `@Serializable data class <Name>Json(...) : GameEventJson { override
   fun toDomain() = <Name>(...) }`. Then add a branch to the `when (this)` in
   `BattleEvent.toJson()`. The compiler's exhaustiveness check will force
   you to add both the `@SerialName` annotation and the reverse `toDomain`
   mapping.
5. **Add a render branch.** `render/src/main/kotlin/com/pokemon/battle/render/TextRenderer.kt`
   has a `when (event)` you must extend. Put the helper method near other
   events in the same category (`renderItemDamage`, `renderWeatherDamage`).
   Silent events (events that exist for audit but produce no user-visible
   line) return `emptyList()`.
6. **Emit it** from the relevant phase. Phases are pure — they read state
   and return `List<GameEvent>` — so adding an emit is a local change. Do
   not special-case the new event in `TurnPipeline`; the pipeline folds
   generically.
7. **Test it.** Cover the `apply` behavior (state transition is correct),
   the render text, and the serialization round-trip (the existing
   `EventSerializationTest` runs a full-turn scenario — if your event
   fires in that scenario, it's automatically covered).
8. **Validate:** `./gradlew test ktlintCheck detekt`.

## Extensions

**3a. The state change requires information the existing event shape can't
   carry.** Add a field. Events are `data class`es and fields are cheap; the
   JSON DTO mirrors the shape. Don't try to bundle information through a
   side channel.

**4a. Your event should not round-trip through JSON.** Ephemeral pipeline
   machinery (some `ControlEvent`s) may legitimately not belong in a saved
   replay. In that case, skip the DTO step and the serializer's `when`
   exhaustiveness will flag the gap — add an `is YourEvent -> error(...)`
   branch with a comment explaining why it's not serialized. This is
   rare; default to serialising.

**5a. Multiple renderers.** If we eventually have `HtmlRenderer` or
   `JsonRenderer` (speculation in diary 042), each renderer's `when` must
   exhaustively handle your event. That's the cost of using sealed
   hierarchies — and the benefit, because the compiler enforces it.

**6a. Your event needs a new emit site inside an existing phase.** Find the
   right place in the phase's top-level `resolve` or the helper method for
   the relevant trigger (e.g. post-damage hooks, end-of-turn). Do not add a
   new phase just to emit a new event — phases are for *orchestration*,
   not for single events.

**6b. You're splitting an existing field into a dedicated event** (e.g. the
   `CriticalHit` event was extracted from `DamageDealt.critical`). Three
   steps: (1) leave the original field in place for backward compat unless
   you've audited every consumer; (2) add the emit site immediately
   *before* the original event so renderers and analytics see the split
   event first; (3) migrate the render path to the new event —
   searching `"original line"` across `TextRenderer.kt` catches any
   conditional rendering that needs to move. `CriticalHitTest`'s
   "fires alongside DamageDealt" assertion is the template for testing
   the ordering.

## Related information

- **Canonical design:**
  - Diary 060 — event serialization DTO split. The reason every event has a
    `*Json` mirror.
  - Diary 061 — `GameEvent` vs `ControlEvent` hierarchy. Why the pick in
    step 1 matters.
  - Diary 042 — the event stream as a first-class data asset.
  - `docs/architecture.md` — the "Sealed hierarchies as domain catalogs"
    rationale.
- **Worked examples in repo:** look at files you touched for similar
  mechanics — `ItemHealing` / `ItemConsumed` for item-triggered audit
  events; `StatChanged` for per-slot mutations; `SideConditionSet` for
  side-level state.
- **Gotcha:** events should be *mechanical*, not carry game-rule
  conditionals. `SwitchOut.apply()` originally cleared volatiles and stat
  stages. That's a game rule — it belongs in `SwitchPhase`, not in
  `SwitchOut.apply()`. See the "Events are mechanical, rules live in
  phases" lesson in `docs/architecture.md`.

# Diary 092: Terastallization — first real gimmick lands on existing seams

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Diary 091 filed Tera as the first real gimmick. The test it sets up:
can a new battle mechanic introduced in a later generation land on the
seams already in the engine without bending them? Diaries 036 (gimmick
state stub), 016 (type-override seam), 033 (cross-registry interactions),
and 034 (rendering separation) all claimed those seams would support
future mechanics. Tera is the first mechanic that *actually uses* them.

The complementary claim to diary 090: diary 090 proved that swapping a
`DamageCalculator` produces observable behaviour (registry-DI seam
works). This diary tests the orthogonal claim — that the engine
accepts a *new mechanic* without touching any existing phase logic.

## What shipped

- **`Pokemon.teraType: Type?`** — preset at team-build time. Null means
  the Pokemon cannot Terastallize.
- **`PokemonState.terastallized: Boolean`** — runtime activation flag.
  Flipped by the `Terastallized` event.
- **`Terastallized(slot)` event**: sets `terastallized=true`, sets
  `typeOverride = listOf(teraType)`, and appends a `UsedGimmick(TERA, ...)`
  entry. Reuses `withGimmickUsed` and the existing `typeOverride`
  field — no new state shape, no new mutation path.
- **`TurnChoice.UseMove.terastallize: Boolean = false`** — the
  per-turn player signal.
- **`MoveExecutionPhase.maybeTerastallize`** — helper that fires at
  the top of `stepForSlot`, before status checks. Returns the event
  when the ruleset permits, or null to fall through. The existing
  `prepend` helper hands the event into the normal move-step chain.
- **`GenVDamageCalculator` STAB rule** split out as `teraStab(attacker,
  moveType)`:
  - Not Terastallized → 1.5× on type match, else 1.0× (unchanged).
  - Terastallized → 2.0× when move type matches both original species
    types AND the tera type; 1.5× when it matches one; 1.0× when it
    matches neither.
- **`TerastallizedJson`** in the serialization layer so events
  round-trip cleanly. `TextRenderer` gets a `"Charizard
  Terastallized into the DRAGON type!"` message.
- **7 tests in `TerastallizationTest`** covering:
  - STAB 2.0× double-match (Fire Charizard → Fire tera + Fire move).
  - STAB 1.5× single-match (Fire Charizard → Grass tera + Grass move).
  - STAB 1.0× no-match (extensibility-section edge probe).
  - Pipeline emits `Terastallized` before `MoveAttempted`.
  - `Gen9VgcTeraRuleset` rejects a second activation.
  - Default `NoGimmicksRuleset` swallows the request.
  - Non-Terastallized Pokemon keeps vanilla STAB path.

## What *didn't* change

- `MoveOrderPhase`, `EndOfTurnPhase`, `SwitchPhase` — untouched.
- `BattleState` — zero new fields (the `gimmicksUsedBySide` /
  `ruleset` slots existed since diary 036).
- `BattleLoop`, `ChoiceProvider` — unchanged. Tera activation is a
  field on the choice type, not a new choice variant.
- Gen III / Gen V registries — unchanged. Tera doesn't need a
  registry entry; it's an engine mechanic, not a data effect.
- `DamageCalculator` interface — unchanged signature. The STAB rule
  is internal to the calc implementation; `terastallized` reaches it
  via `PokemonState`, which the calc already receives.

Total scope in engine: ~60 lines of new code across 4 files, plus ~20
lines of render/serialization glue. The feature is small *because*
the seams were right.

## Code review

### Diagnostics

- *Testable in isolation:* `teraStab` is a pure function of
  `(PokemonState, Type) → Double`. The 2×/1.5×/1× branches have direct
  test coverage. The event is testable without the pipeline
  (`Terastallized.apply` is a `BattleState → BattleState` function).
  The pipeline integration is covered by the "emits before
  MoveAttempted" test.
- *Readable:* `maybeTerastallize` reads like an English sentence —
  "not requested? null. already terastallized? null. no tera type?
  null. ruleset says no? null. otherwise, emit the event." Four
  early returns, one happy path. `teraStab`'s structure mirrors how
  the mainline rule is stated in Bulbapedia: double-match vs
  single-match vs none.
- *Layer:* mechanics in `:engine`, rendering in `:render`,
  serialization in `:engine/serialization`. Same three-layer shape
  as every other event.
- *Colocation:* everything Tera-specific is colocated —
  `Terastallized.kt` (one file), the STAB fn in `DamageCalc.kt` (next
  to the existing STAB line it replaced), the `maybeTerastallize`
  helper in `MoveExecutionPhase.kt` (next to `prepend`). The
  `terastallize` flag on `UseMove` is visible at the choice site.
- *Hard to reverse?* Low. `teraType: Type? = null` and
  `terastallized: Boolean = false` are purely additive (defaults
  preserve existing callers). Removing Tera = delete 5 files + revert
  4 sites.
- *Auditable:* `Terastallized` events land in the battle log exactly
  like any other event. `formatTag` on recorded battles plus the
  event history is sufficient to reconstruct "did this battle use
  Tera, when, on which slot."
- *Happy path:* choice has `terastallize=true` → `maybeTerastallize`
  confirms legal → emits `Terastallized` → status checks proceed →
  move resolves with new STAB rule via `effectiveTypes` +
  `terastallized` flag.
- *Failure modes:*
  - Choice requests Tera but ruleset forbids → event silently skipped.
    Acceptable because the ruleset is the authoritative gate; a
    misbehaving AI / UI is not the calc's problem. The tera flag is
    advisory.
  - Tera requested but no `teraType` set → silently skipped (same
    reasoning). A stricter engine could `error()` here, but that
    conflates data-model validity with turn-time decisions.
  - Second Tera request → rejected via ruleset (`priorUsage.isEmpty()`
    check in `Gen9VgcTeraRuleset`). Verified by test.
- *Duplicated logic:* none. `teraStab` replaces the inline STAB
  line, doesn't duplicate it.
- *Illegal state:* `Terastallized.apply` errors with a clear message
  if `teraType` is null. That's a defensive assertion for what the
  `maybeTerastallize` gate already prevents — belt-and-braces, since
  external callers could construct the event directly.
- *Invariants:* `terastallized` only transitions false → true, never
  back. This matches the mainline: once you Tera, you stay Tera'd
  for the battle. The engine doesn't enforce this invariant
  statically (no `init` check); the only callsite is
  `Terastallized.apply`, which unconditionally sets it true.
  Acceptable: one narrow write site.
- *Mutation:* none. `copy(terastallized = true)` is the usual pure
  data-class update.
- *Names:* `teraType` on preset matches Showdown / Bulbapedia's
  naming. `terastallized` for the boolean flag mirrors `abilityOverride`
  / `typeOverride` in shape. `maybeTerastallize` signals "may or may
  not — check gates."
- *Layer-blur:* none. Render knows about `Terastallized` but only to
  produce text; it doesn't call into engine logic.
- *Removal:* delete `Terastallized.kt`, `TerastallizationTest.kt`,
  `renderTerastallized` + the when-branch in `TextRenderer.kt`,
  `TerastallizedJson` + the when-branch in `BattleEventJson.kt`, the
  `teraStab` fn (restore the inline STAB line), `maybeTerastallize`
  + the `stepForSlot` hook, the `teraType` / `terastallized` fields.
  Roughly 8 edits in 7 files. Clean.

**No findings beyond a couple of intentional design choices (advisory
Tera flag silently ignored when illegal; one-way `terastallized`
transition not statically enforced) which are discussed above.**

### Industry comparison

Tera introduces a new mechanic, a new event type, and a new choice
field — substantial change. Comparison:

- **Pokémon Showdown** implements Tera as a `volatileStatus`
  (`terastallize`) plus a battle-level hook that modifies STAB
  inside the damage calc. Their STAB logic is the same rule-shape we
  wrote (match both → 2×, match one → 1.5×, neither → 1×) but it
  lives in a mixin over the base calc. Our seam is narrower — the
  STAB function is the seam. Same behaviour, smaller surface.
- **PokéAPI / Bulbapedia** don't formalize "STAB" as a first-class
  concept; the community wiki describes Tera STAB in prose. We made
  STAB a named function (`teraStab`) which is probably better than
  Showdown's inline ternary — a reader grepping for "STAB" finds it
  immediately.
- **Trading-card-game simulators** (Cockatrice, Magic Arena
  scripting) handle one-shot "transform" mechanics by pushing the
  old state onto a stack and emitting a reverse-transform event at
  end-of-game. We don't need that shape because Tera is permanent
  until battle end — there's no reversal to model. If Mega Evolution
  ships next (which ends on switch-out or faint in some gens), we'll
  need a reversal event. Tera's permanence is what makes it the
  cheapest gimmick to ship first.
- **Game engines (Unity ScriptableObject, Unreal DataAsset)** would
  ship Tera as configured data — a JSON blob listing "event =
  Terastallize, activates when = ChoiceFlag, modifies = Type + STAB."
  We ship it as compiled code. For a mechanic with 60 lines of
  logic and tight coupling to the damage formula, compile-time code
  is honest; marshalling it through a config layer would be
  premature abstraction.
- **What we're deliberately not doing:**
  - **Tera Blast** (the move that changes type based on tera type).
    Requires a move that inspects user state at resolve time. Not a
    seam we have. Deferred.
  - **Tera-type-specific damage boosts** (60-BP floor for Tera moves,
    the "Stellar" type). Niche, deferred.
  - **UI prompt for Tera** in the interactive CLI. The engine accepts
    `terastallize=true` on a `UseMove`; wiring a UI prompt is a
    `:cli` concern and can land independently.

### Findings to fix

None filed. Two deferred items flagged in the "deliberately not
doing" list above: Tera Blast and UI prompts. Neither blocks this
shipment.

## Validation

- `./gradlew test ktlintCheck detekt` — green (118 tasks, zero
  failures).
- `./gradlew :engine:test --tests
  "com.pokemon.battle.TerastallizationTest"` — 7 tests, all pass.
- `DiaryConventionTest` enforces `## Code review` on this diary.

## Related

- **Diary 091** — planning placeholder for Tera. Now superseded by
  this diary; the plan's "lean toward (c) StabResolver seam" choice
  was ultimately not taken — we inlined the rule in
  `GenVDamageCalculator` via a named `teraStab` fn, which is narrower
  and avoids introducing a new seam for one consumer. If Gen 1/2
  land with different STAB rules, the seam gets extracted then.
- **Diary 036** — gimmick state stub. The `GimmickKind.TERA` enum
  value, the `UsedGimmick` record, the `gimmicksUsedBySide` map, the
  `ruleset` field on `BattleState` — all from diary 036 and all used
  here without modification. The stub paid off.
- **Diary 016** — overridable types. `typeOverride: List<Type>?` is
  what Tera writes to. Without that seam, Tera would need a new
  field on `PokemonState`.
- **Diary 090** — Gen III damage calculator. Complementary probe:
  090 tests *injection-swap*, this diary tests *new-mechanic
  landing*. Together they span the two extensibility claims.
- **Diary 034** — rendering separation seam. `TextRenderer` got one
  new branch and one new helper; the render module stayed untouched
  otherwise.

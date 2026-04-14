# Diary 072: Move-behavior registry — picking up diary 029

**Date:** 2026-04-14
**Status:** Planning — no code changes. Captures the design now that diary 071
has shipped the items/abilities equivalent and set the pattern.

## Why this diary exists

Diary 029 proposed a move-behavior registry mirroring the items/abilities
pattern. The threshold it named was "3+ shape-A/B/C moves queued" — the
point at which the `when (effect)` in `MoveExecutionPhase.resolveEffect` would
be more painful than an extracted registry.

We're still below that threshold today, so this diary **does not ship** the
registry. Instead it captures the design with the benefit of diary 071's
experience, so when the threshold crosses the work is a matter of *applying*
the pattern rather than *discovering* it.

Separately, the user observed during the items/abilities registry-coverage
test (post-071) that "most moves don't have additional effects" — a key
difference from items/abilities that shapes the design below.

## Why the moves case differs from items/abilities

Items/abilities: of the ~11 items and ~14 abilities in the engine today,
**every one has a behavior worth registering**. The identity-only set in
`RegistryCoverageTest` is empty. Each enum value is shaped like
"this thing does something."

Moves: we currently model ~28 moves in `MoveDex`, and the vast majority
(Tackle, Flamethrower, Ice Beam, Earthquake, Shadow Ball, Aura Sphere,
Thunderbolt, Sludge Bomb) **have no secondary effect**. They just deal
damage. Secondary effects are the minority — stat drops (Growl), multi-hit
(Rock Blast, Double Slap), self-switch (U-turn, Volt Switch), volatiles
(Protect, Trick Room), hazards (Stealth Rock, Spikes, Toxic Spikes, Sticky
Web), hazard-clear (Rapid Spin, Defog), etc.

This asymmetry changes the design. For items/abilities, "registered with a
behavior" is the norm; identity-only is the exception. For moves, **"no
secondary effect" is the norm**; a registered behavior is the exception.

Consequences for the registry shape:

1. **A `MoveEffect` registry should NOT require every move to appear.**
   The lookup returns "no effect" by default, and that's fine.
2. **A `RegistryCoverageTest` equivalent for moves would be noise.** An
   identity-only allowlist bigger than the registered set has flipped the
   signal/noise ratio — you'd be maintaining a parallel copy of `MoveDex`.
3. **The extraction is motivated by* when effects proliferate, not when
   the enum grows.** One new damage-only move is free; the tenth new
   `SetVolatile` variant is the forcing function.

## Today's shape

`MoveEffect` is a sealed interface in
`engine/src/main/kotlin/com/pokemon/battle/model/MoveEffect.kt`. Current
variants:

- `StatBoost(stat, stages, target)` — stat-change side effects.
- `SetVolatile(volatile)` — Protect, Leech Seed, future Substitute.
- `SelfSwitch` — U-turn, Volt Switch.
- `SetTrickRoom` — the field-level flip.
- `SetSideConditionOnUserSide(condition, turns)` — Tailwind.
- `SetHazardOnOpposingSide(hazard)` — Stealth Rock, Spikes, Toxic Spikes,
  Sticky Web.
- `ClearHazardsOnUserSide` — Rapid Spin, Defog.
- `UserStatBoost(stat, stages)` — self-targeted stat boost moves.

Dispatch lives in `MoveExecutionPhase.resolveEffect` as a `when` over the
sealed hierarchy. The compiler flags missing branches.

## What the registry shape would look like

Mirroring diary 071 (items/abilities):

```kotlin
// :engine — the contract
interface MoveBehavior {
    val move: Move
    fun onMoveUsed(...): List<GameEvent> = emptyList()
    fun modifyDamage(...): Double = 1.0
    // ... hooks for the concrete effects we resolve today
}

class MoveBehaviorRegistry(behaviors: List<MoveBehavior>) {
    private val byMove: Map<Move, MoveBehavior> = behaviors.associateBy { it.move }
    fun behaviorFor(move: Move): MoveBehavior? = byMove[move]
}

// :data — the entries (matching GenVRegistries pattern)
val GenVMoveBehaviors: MoveBehaviorRegistry = MoveBehaviorRegistry(listOf(
    ProtectBehavior,
    RapidSpinBehavior,
    // ...
))
```

Plus a `Registries` bundle extension:

```kotlin
data class Registries(
    val items: ItemRegistry,
    val abilities: AbilityRegistry,
    val moves: MoveBehaviorRegistry = MoveBehaviorRegistry(emptyList()),
)
```

`MoveExecutionPhase.resolveEffect` becomes a consult-the-registry lookup
instead of a `when`; the sealed `MoveEffect` hierarchy either stays as a
data shape (structured argument to a behavior) or dissolves into per-move
behavior objects.

## Key design question: does `MoveEffect` dissolve, or stay as data?

Two realistic shapes:

### Option A: `MoveEffect` dissolves into per-move behavior objects

- Each secondary effect becomes a `MoveBehavior` singleton (Protect,
  RapidSpin, UTurn, ...).
- `Move` loses its `effects: List<MoveEffect>` field; move identity is
  the only thing stored.
- `MoveExecutionPhase.resolveEffect` disappears; the pipeline consults
  `registries.moves.behaviorFor(move)` and lets the behavior object emit
  its own events.

Pros: consistent with items/abilities. Adding a new secondary effect is
exactly the same skill as "add an item."

Cons: moves that share the same secondary effect structure (StatBoost-on-hit
with different stat/stages — Growl, Leer, Tail Whip) would need either a
shared delegate (like `PinchTypeBoostEffects`) or duplicate files. Today
they all share one `StatBoost` variant with different data.

### Option B: `MoveEffect` stays as data, behaviors are resolvers

- `Move` keeps `effects: List<MoveEffect>`.
- `MoveBehaviorRegistry` holds generic resolvers: a `StatBoostResolver`, a
  `SetHazardResolver`, etc. — one per *shape* of effect, not per move.
- `MoveExecutionPhase.resolveEffect` becomes
  `registries.moves.resolverFor(effect).resolve(...)`.

Pros: data-shaped moves (Growl, Leer, Tail Whip) share a single resolver.
Adding a new instance of an existing shape is still data-only in `MoveDex`.

Cons: the registry holds ~8 resolvers today; not clear what that buys
relative to the existing exhaustive `when`. Structurally similar to the
"strategy pattern as a Map" smell.

### Leaning (when we get there)

**Option A, with shared delegates for same-shape moves.** Matches items/
abilities, keeps the recipe consistent, and the shared-delegate pattern
(per-item-class singletons like `ChoiceItem` in `ChoiceItemEffects.kt`)
already has precedent. Data-only moves in `MoveDex` keep being data-only;
only moves with *unique* behavior get per-move files.

But we don't know that's right yet. Diary 029 deferred explicitly so we
could see which shapes proliferate. The extraction will be better-informed
if we wait for the forcing function.

## What the forcing function looks like

The threshold I'd use: **three or more moves queued whose behavior doesn't
fit any existing `MoveEffect` variant AND isn't a clean data extension of an
existing variant.** Today nothing qualifies.

Example moves that would *not* qualify (fit existing variants):

- Thunder Wave (`StatusAppliedEffect` would be new but it's a data-shaped
  extension — paralysis is already a `StatusCondition` value).
- Swords Dance (`UserStatBoost` already exists).
- Iron Head (flinch chance — needs a `FlinchChanceEffect` variant; one
  variant alone doesn't cross the threshold).

Example moves that *would* push us over:

- Weather Ball (type *changes* based on weather) — not a secondary effect,
  it's a *damage-calc modifier per-move*. Would need a new hook on
  whatever eventually becomes `MoveBehavior`, not just a variant.
- Hidden Power (type/power derived from IVs) — similar shape.
- Return / Frustration (power scales with happiness) — another
  derive-at-calc-time power.
- Pursuit (intercepts a switch) — changes phase ordering. Not a
  `MoveEffect` at all; it's phase-level mechanics.
- Trademark format's "on-move-use" ability trigger — cross-registry
  interaction.

Three of those five push us over. None is queued today.

## What we're deliberately *not* doing

- Building the registry now. No forcing function. Pre-extraction would be
  the exact speculation trap diaries 042 / 060 / 064 / 067 all guard
  against.
- Writing a `MoveRegistryCoverageTest` like diary 071 shipped for
  items/abilities. As argued above, most moves are behavior-free; the
  allowlist would dominate.
- Picking Option A vs Option B before the work starts. The right answer
  depends on which shapes proliferate first, and we don't know yet.

## What we *could* do now (optional follow-ups)

1. **Write a single-line invariant in `MoveExecutionPhase.resolveEffect`**
   that tracks the current `MoveEffect` variant count and fails if it
   hits 9 (one past today's 8). Forces us to revisit this diary instead
   of silently adding variants until the `when` is unmaintainable.
2. **Add an item to the `add-move.md` skill doc pointing here** when a
   contributor reaches for a new `MoveEffect` variant. Already done —
   the skill doc's Extension 3a references this diary.
3. **Nothing.** Defensible. The `when` is currently small, the exhaustive
   compile check is the safety net, and diary 029 already exists as the
   plan record. The only reason this diary exists is to capture the
   *design update* from diary 071's items/abilities experience for
   future-us. That's done.

## Related

- **Diary 029** — the original "move-behavior registry" deferral, with
  the threshold criterion.
- **Diary 071** — the items/abilities registry-DI refactor. The pattern
  this diary would apply.
- **Diary 047** — parallel agents observed "behavior-shaped vs
  data-shaped" reasoning when adding moves; informs Option A vs B
  above.
- **Diary 066** — original audit that prioritized items/abilities before
  moves. The priority ordering wasn't arbitrary — items/abilities cross-
  reference each other (Klutz suppresses item) and that coupling was the
  urgent smell. Moves are more self-contained.
- **`docs/skills/add-move.md`** — the current task-level recipe.
  References this diary when a contributor considers a new `MoveEffect`
  variant.

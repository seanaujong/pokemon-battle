# Diary 040: Entry Hazards

**Date:** 2026-04-14
**Status:** Complete

## Goal

Implement the four canonical entry hazards: **Stealth Rock**, **Spikes**, **Toxic Spikes**,
**Sticky Web**. These are side-scoped persistent effects that fire when a Pokemon switches
in — a new trigger the engine doesn't have yet. Core VGC/Smogon mechanic; Stealth Rock
alone is in ~70% of competitive teams.

## Mechanics

| Hazard | Setter move | Layers | Effect on switch-in |
|--------|-------------|--------|---------------------|
| Stealth Rock | Stealth Rock | 1 (not layered) | Rock-type damage = `maxHp × rockEffectiveness / 8` |
| Spikes | Spikes | 1-3 | 1L: maxHp/8, 2L: maxHp/6, 3L: maxHp/4 (grounded only) |
| Toxic Spikes | Toxic Spikes | 1-2 | 1L: Poison, 2L: Badly Poisoned (grounded only; Poison types absorb all) |
| Sticky Web | Sticky Web | 1 (not layered) | -1 Speed stage (grounded only) |

**Common:**
- Persistent — no turn counter, stays until removed by Rapid Spin / Defog / Mortal Spin / Tidy Up
- Fire on every non-initial switch-in (voluntary, self-switch, faint replacement)
- "Grounded only" means Flying types and Levitate users skip (future: Air Balloon, Magnet Rise)

## Architectural decision: how to model hazards

Our existing `SideCondition` is `Map<Side, Map<SideCondition, Int>>` where `Int = turns
remaining`. Hazards don't fit — they have layer counts (Spikes, Toxic Spikes) or pure
presence (Stealth Rock, Sticky Web), and they never tick.

### Options considered

**A. Repurpose `Int` to mean "value"** (turns for Tailwind, layers for hazards, 1 for presence).
The EndOfTurnPhase tick logic would need a "which conditions decrement" check.
*Rejected:* ambiguous meaning, gate logic spreads.

**B. Sealed `SideCondition` with per-condition state** (`Tailwind(turns)`, `Spikes(layers)`,
`StealthRock`, etc.).
*Rejected:* map-keying by class type, re-set semantics get tricky, storage becomes
awkward (`Set` vs `Map`).

**C. Parallel `sideHazards` field on `BattleState`.** New `SideHazard` enum, separate
storage, separate events.
**Selected.**

### Why Option C

The distinction between "temporary boon with a counter" (Tailwind, Reflect, Light Screen)
and "persistent trap with layers" (hazards) is semantically real. They have different
lifecycles, different trigger points (end-of-turn tick vs switch-in fire), and different
removal mechanisms. Two fields are clearer than one overloaded field, and the cost is
small: one new enum, one new field, parallel helpers.

### Design

```kotlin
enum class SideHazard {
    STEALTH_ROCK,  // layer count unused (always 1)
    SPIKES,        // 1-3
    TOXIC_SPIKES,  // 1-2
    STICKY_WEB,    // layer count unused (always 1)
}

// On BattleState:
val sideHazards: Map<Side, Map<SideHazard, Int>> = emptyMap()

// Helpers:
fun hazardsOn(side: Side): Map<SideHazard, Int>
fun withHazardLayer(side: Side, hazard: SideHazard, layers: Int): BattleState

// Events:
data class HazardSet(val side: Side, val hazard: SideHazard, val layers: Int) : BattleEvent
data class HazardRemoved(val side: Side, val hazard: SideHazard) : BattleEvent
```

### New hook: switch-in hazard application

After a Pokemon switches in (voluntary, self-switch, faint replacement) and after
switch-in abilities resolve, fire hazards they stepped into:

```kotlin
fun resolveHazardsOnSwitchIn(state: BattleState, slot: Slot): List<BattleEvent>
```

Called at three sites:
- `SwitchPhase` after `resolveSwitchInAbility`
- `MoveExecutionPhase.resolveSelfSwitch` after `resolveSwitchInAbility`
- Future: `BattleLoop` faint-replacement flow (already calls `resolveSwitchInAbility`; needs hazards too)

**Ordering note:** Real games fire switch-in abilities first, then hazards, in most gens. We match that.

## Scope

**In:**
- 4 hazards + 4 setter moves
- `SideHazard` enum + `BattleState.sideHazards` + helpers
- `HazardSet` / `HazardRemoved` events
- `resolveHazardsOnSwitchIn` called from all three switch-in sites
- Grounded check (types + Levitate ability)
- Poison-type absorption of Toxic Spikes
- Tests for each hazard

**Out (deferred):**
- **Hazard removal moves** (Rapid Spin, Defog, Mortal Spin, Tidy Up) — logical next
  diary, but setters alone are enough to validate the architecture
- **Heavy-Duty Boots** item — blocks hazard damage; one `ItemEffect.blocksHazards`
  method away, but adds test permutations; next diary
- **Magic Bounce ability** — reflects hazard-setter moves; defer until more reflect
  moves exist (Taunt etc.)
- **Gravity / Air Balloon / Magnet Rise** — enrich the "grounded" predicate; add when
  first needed

## Plan

### Step 1: Hazard types + state
- [ ] `SideHazard` enum
- [ ] `BattleState.sideHazards` field, `hazardsOn(side)` helper, `withHazardLayer(side, hazard, layers)` helper

### Step 2: Events
- [ ] `HazardSet(side, hazard, layers)` event
- [ ] `HazardRemoved(side, hazard)` event
- [ ] TextRenderer branches for both

### Step 3: Grounded predicate
- [ ] `PokemonState.isGrounded` extension (not Flying type AND not Levitate)

### Step 4: Hazard resolver
- [ ] `resolveHazardsOnSwitchIn(state, slot)` — emits damage/status/stat events per
      hazard present, respecting immunities

### Step 5: Setter moves + `MoveEffect.SetHazardOnOpposingSide`
- [ ] `MoveEffect.SetHazardOnOpposingSide(hazard, maxLayers)` — increments layers up to max
- [ ] `resolveEffect` handler emits `HazardSet`
- [ ] `MoveDex`: STEALTH_ROCK, SPIKES, TOXIC_SPIKES, STICKY_WEB
- [ ] Poison-type absorption of Toxic Spikes handled inside `resolveHazardsOnSwitchIn`
      (grounded Poison type → emit `HazardRemoved(TOXIC_SPIKES)` instead of applying)

### Step 6: Wire switch-in sites
- [ ] `SwitchPhase` — after ability triggers
- [ ] `MoveExecutionPhase.resolveSelfSwitch` — after ability triggers
- [ ] `BattleLoop` faint replacement — after ability triggers (if it calls
      `resolveSwitchInAbility`; verify)

### Step 7: Tests
- [ ] Stealth Rock damage scales with Rock effectiveness (super-effective vs resisted)
- [ ] Spikes layers: 1/8, 1/6, 1/4 maxHp; using Spikes a 4th time doesn't stack further
- [ ] Flying types skip Spikes; Levitate skips Spikes
- [ ] Toxic Spikes 1 layer → Poison; 2 layers → badly poisoned (Toxic counter)
- [ ] Grounded Poison type absorbs Toxic Spikes (all layers removed, no status applied)
- [ ] Sticky Web drops Speed by 1 stage for grounded switch-ins
- [ ] Hazards persist across turns (no decrement)
- [ ] Hazards fire on voluntary switch, self-switch (U-turn), and faint replacement

## Limitations to flag

- **Grounded predicate is minimal** — just types + Levitate. Gravity, Iron Ball, Air
  Balloon, Magnet Rise, Ingrain all interact; we'll enrich `PokemonState.isGrounded`
  as those mechanics arrive.
- **No hazard removal yet** — state can only accumulate, not clear. Tidy up in
  the next diary.
- **Stealth Rock uses type chart** — `SideHazard` enum values can't own the
  "damage formula"; the resolver has a `when` over hazard kinds. This is fine at 4 hazards
  but if more are added (future Stealth Steel, Sticky Rock variants), consider a
  `HazardBehavior` mini-registry. Below threshold today.

## Related

- **Diary 024** — `SideCondition` infrastructure this parallels
- **Diary 029** — move-behavior registry; setter moves are generic `MoveEffect`, not
  behavior-registry material
- **Diary 030** — twist #9 (FieldState as declarative query layer) — hazards + weather +
  terrain all want a shared "does this attribute apply here?" API eventually

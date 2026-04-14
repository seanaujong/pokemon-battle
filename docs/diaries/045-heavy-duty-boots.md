# 045 — Heavy-Duty Boots

## Goal

Implement the Heavy-Duty Boots held item. The holder ignores all entry-hazard
damage and effects when switching in (Stealth Rock, Spikes, Toxic Spikes,
Sticky Web).

## Context

Diary 040 added entry hazards and the `resolveHazardsOnSwitchIn` function in
`HazardResolver.kt`. Items live behind the `ItemEffect` interface
(`engine/item/ItemEffect.kt`), registered in `ItemRegistry`. Item lookups from
behavior code go through `ItemRegistry.effectForHolder(pokemon)` so that
ability-based suppression (Klutz, future Embargo/Magic Room) is respected.

## Decisions

- **New hook on `ItemEffect`: `blocksHazards(holder): Boolean = false`.** This
  is a simple predicate rather than an event-emitting hook — Boots don't
  produce any event (no consumed, no heal, no damage), they just gate the
  hazard resolver. Keeping it a Boolean avoids forcing other items to
  participate in hazard resolution.
- **Hazard resolver consults `effectForHolder`**, not `effectFor`. That way
  Klutz (ability-suppression) deactivates Boots, matching mainline behavior
  (Klutz suppresses all held items' effects).
- **All-or-nothing gate.** If Boots is active, `resolveHazardsOnSwitchIn`
  returns `emptyList()` before any hazard triggers. No per-hazard whitelist
  — Heavy-Duty Boots ignores every entry hazard in mainline.
- **No render text object.** Boots emit no events, so there's nothing to
  render. Skipping `HeavyDutyBootsText`.

## Plan

- [x] Add `Item.HEAVY_DUTY_BOOTS` to the enum
- [x] Add default `blocksHazards(holder): Boolean = false` hook to `ItemEffect`
- [x] Create `HeavyDutyBootsEffect` object overriding `blocksHazards` to return `true`
- [x] Register in `ItemRegistry`
- [x] Gate `resolveHazardsOnSwitchIn` on `effectForHolder(pokemon)?.blocksHazards(pokemon)`
- [x] Write `HeavyDutyBootsTest` covering each hazard + a regression (no-Boots) case
- [x] `./gradlew test ktlintCheck detekt` green

## Validation

- All new tests green
- Existing `EntryHazardsTest` still green (regression — default `blocksHazards = false`)
- ktlint + detekt clean

## Outcome

Heavy-Duty Boots now bypasses all four entry hazards. The change is a single
new hook on `ItemEffect` plus a one-line gate in `HazardResolver`. Klutz
correctly disables Boots because the resolver uses `effectForHolder`.

## Look ahead

- Magic Guard (ability) is another "ignore hazards" candidate — it's broader
  (ignores all indirect damage) and would be an ability-side gate rather than
  an item hook. Would share no code with Boots except the "return emptyList"
  pattern in the resolver.
- Air Balloon is a similar Item with a narrower scope (ignores Ground-type
  moves and Spikes/Toxic Spikes, but not Stealth Rock or Sticky Web). Would
  need a richer hook than `blocksHazards: Boolean` — maybe
  `blocksHazard(hazard: SideHazard): Boolean`. Defer that generalization until
  we actually add Air Balloon.

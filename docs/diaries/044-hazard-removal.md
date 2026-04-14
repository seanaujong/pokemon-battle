# Diary 044: Hazard Removal (Rapid Spin, Defog)

**Date:** 2026-04-13
**Status:** Complete

## Goal

Implement the two canonical hazard-removal moves — **Rapid Spin** and **Defog** — that
clear entry hazards set by Diary 040. These close the loop: hazards could accumulate
but never be cleared except by Toxic-Spikes-absorption. Without removal the meta-game
mechanic isn't real.

## Scope

**In:**
- New `MoveEffect.ClearHazardsOnUserSide` variant — emits `HazardRemoved` for every
  hazard currently on the user's side.
- `RAPID_SPIN` (Normal, Physical, 50 BP, Gen 8+) with two effects: clear user-side
  hazards AND +1 Speed to user.
- `DEFOG` (Flying, Status, 0 BP) with one effect: clear user-side hazards.
- Handler in `MoveExecutionPhase.resolveEffect` for the new variant.
- Tests in `HazardRemovalTest.kt` for both moves.

**Out (deferred):**
- **Defog clearing opponent-side screens / hazards and terrain.** Mainline Defog also
  clears opposing side hazards, lowers target's Evasion by 1, and removes terrain. We
  implement only the user-side-hazard-clear piece scoped by this task. Flag loudly;
  future diary can extend.
- **Mortal Spin, Tidy Up, Court Change.** Different mechanics, out of scope.
- **Magic Bounce interactions.** Hazard-setter deflection already deferred in 040.

## Decisions

### One new effect variant, or reuse?

Considered adding `clearsUserSideHazards: Boolean` to an existing effect. Rejected —
effects are sealed union members representing distinct intents. "Clear all hazards on
the user's side" is its own verb. New variant fits the existing pattern cleanly
(`SetHazardOnOpposingSide` + `ClearHazardsOnUserSide` make a matched pair).

### Where does "user side" come from?

Effects receive `targetSlot`. For moves with `MoveTarget.SELF`, the target slot *is*
the user's slot, so `targetSlot.side == user.side`. The handler uses `targetSlot.side`
and reads all hazards on that side.

### Failing silently when no hazards present

Mainline: Rapid Spin still deals damage and boosts Speed even when no hazards exist;
Defog still lowers Evasion even with no hazards. Our implementation emits zero
`HazardRemoved` events in that case — correct. The other effects (damage, Speed
boost) live in separate slots on the Move, so they still fire. Good.

## Plan

- [x] Add `MoveEffect.ClearHazardsOnUserSide` data object.
- [x] Handle it in `MoveExecutionPhase.resolveEffect` — read `state.hazardsOn(targetSlot.side)`
      and emit a `HazardRemoved` event for each entry.
- [x] Register `RAPID_SPIN` and `DEFOG` in MoveDex. Rapid Spin targets SELF (since its
      non-damage effects apply to the user; the damage hits the opponent via the
      existing damage pipeline... wait — see note).
- [x] Tests: Rapid Spin clears hazards from user side; Defog same; both leave opposing
      side hazards intact; Rapid Spin also raises user Speed.
- [x] Run `./gradlew test ktlintCheck detekt`.

### Target-resolution wrinkle

`MoveTarget` controls where *effects* are applied (and for damage moves, the damage
target). Rapid Spin in mainline is a damaging move that also spins. Our engine treats
damage via the Move's base power + category separately from the effect list applied
via target resolution. Looking at `Earthquake` (target = ALL_OTHER) and `Swords Dance`
(target = SELF) — the effect-list target is the target for effects; damage uses the
same resolution.

For Rapid Spin we want the move to *hit the opponent* for damage, but clear our own
hazards. Two options:

**A.** Use `MoveTarget.ONE_OPPONENT` for damage, and let `ClearHazardsOnUserSide` be
intrinsically user-sided regardless of targetSlot — the handler uses the *attacker's*
side, not the effect's targetSlot side.

**B.** Split the damage and effect targets.

Option B is a bigger refactor. Option A works if the handler knows "user side" from
context. But `resolveEffect` currently only gets the `targetSlot`. We'd need either
(a) pass the attacker, or (b) have the effect self-describe "user-side".

Simpler: the effect's *semantic* is "clear hazards on the **user's** side". When
fired, we know the user is the attacker. `resolveEffects` already has access to the
attacker via the calling scope... let me look again.

Re-reading `executeMove`: `resolveEffects` is called with `unblockedTargets` — the
target slots after ability/immunity filtering. We don't have the attacker's slot in
`resolveEffect`. We can thread it in — small change.

**Chose:** thread the attacker slot into `resolveEffect`. The effect is user-sided
by definition; we read hazards on `attackerSlot.side`.

This also means `RAPID_SPIN` can keep `MoveTarget.ONE_OPPONENT` (for damage) without
the hazard-clear effect getting confused.

## Outcomes

- 1 new MoveEffect variant (data object)
- 2 new moves (RAPID_SPIN, DEFOG)
- `resolveEffect` now takes an `attackerSlot: Slot` parameter; `resolveEffects`
  threads it through. Pure refactor for existing effects — they ignore the new param
  except where semantically user-sided (just the new one).
- 5 new tests in `HazardRemovalTest.kt`.

## Limitations to flag

- **Defog is incomplete.** Real Defog: clears *both sides'* hazards, lowers target's
  Evasion, clears terrain. We only clear the user's own side. This matches Rapid
  Spin's behavior. Future diary should split these.
- **Rapid Spin damage is just 50 BP Normal.** No spin-trap break (Wrap / Fire Spin
  escape) — those volatiles aren't modeled.

## Related

- **Diary 040** — entry hazards (this closes the loop)
- **Diary 029** — move behavior registry; still not needed

# Diary 091: Terastallization — first real gimmick mechanic (planning)

**Date:** 2026-04-14
**Status:** Planning. Not started.

## Why this diary exists

Diary 036 shipped the gimmick *stub* — `GimmickKind { MEGA, Z_MOVE,
DYNAMAX, TERA }`, a `UsedGimmick` record, a `GimmickUsed` event, and
a `Ruleset` budget gate. Four gens of signature mechanics named, zero
mechanics implemented. The slots exist; nothing slots in.

User asked (2026-04-14): *"did we ever get around to implementing actual
gimmicks?"* — no, we didn't. Filing this so it doesn't slip again.

## Why Tera first (not Mega / Z / Dynamax)

Minimal new plumbing. The seams Tera needs already exist:

- **Type override** — `PokemonState.effectiveTypes` already supports
  overrides (diary 016, `TypeOverrideTest`). Tera *is* a type override
  plus a STAB rule tweak. We reuse the seam.
- **Event + state slot** — `GimmickUsed` event already exists and
  already writes to `BattleState.usedGimmicks`. Tera just emits one.
- **Ruleset budget** — `Ruleset` already gates gimmick legality. Tera
  fits the existing 1-per-battle budget.

Mega needs a *form* dimension on `Pokemon`/`Species` — stats, ability,
and sometimes typing all change. That's a new axis in the data model.
Z-Move and Dynamax need turn-scoped move replacement, which we also
don't have. Tera exercises the claim "new gimmicks land on existing
seams" at the lowest cost; if Tera doesn't land cleanly, neither will
the others, and we learn that cheaply.

## Plan (when picked up)

1. `TerastallizeChoice` (or extend existing `Choice`) — player signals
   "this turn, activate Tera with type X." Choice layer validates
   budget via `Ruleset`.
2. `Terastallized(slot, teraType)` event. `apply` sets the type
   override on `PokemonState` and records a `UsedGimmick` entry.
3. STAB rule tweak: the Tera STAB is 2.0x when the move's type matches
   the Tera type AND the original type (1.5x otherwise when only one
   matches). This is a damage-calc modifier — belongs in
   `GenIXDamageCalculator` (new) so Gen V/IV/III calculators stay
   unchanged. Alternatively, a Tera-aware STAB seam on
   `DamageCalculator` if we don't want a whole new gen calc.
4. Matrix-runner exercise: a team pool with a Tera choice wired in,
   run it, confirm Tera activates, confirm damage numbers change on
   the activating turn.
5. Diary with code review + industry comparison (Showdown's Tera
   handling is the obvious analog).

## Open design questions

- **STAB-by-gen-calc vs STAB-by-seam.** Current damage calcs compute
  `stab = if (move.type in attacker.effectiveTypes) 1.5 else 1.0`. Tera
  wants a per-attacker rule that reads "original types" vs "Tera type"
  separately. Options:
  - (a) Pass both to the calc — wider `DamageCalculator` interface.
  - (b) New `GenIXDamageCalculator` that knows the rule — clean
    layering, grows the gen-calc zoo.
  - (c) A `StabResolver` seam, same shape as `SpeedResolver` /
    `TypeChart`. Narrower than widening the calc interface.
  - Lean: (c). Mirrors the seam pattern we've been using.
- **Where does Tera type live?** On `PokemonState` as a new field, or
  on `Pokemon` (preset), or both (preset + activated flag)? Tera type
  is chosen at team-build time, activated at battle time — both fields
  probably.
- **Do we need a `GenIXRegistries`?** Plenty of Gen 9 abilities/items
  aren't modelled yet. The *minimal* Tera ship doesn't require one —
  Tera works with GenVRegistries, just adds a new damage calc + a new
  choice type. Keep scope tight.

## Non-goals for this diary

- Mega Evolution. Needs species-form data model. Separate lift.
- Z-Moves. Needs turn-scoped move replacement. Separate lift.
- Dynamax. Same as Z-Moves plus HP scaling. Separate lift.
- Tera Blast (the move that changes type based on Tera type). Nice to
  have; not required to prove the gimmick-landing claim.

## Validation signal (when shipped)

- A battle where side 1 activates Tera Fire on a Water-type Pokemon,
  uses a Fire-type move that turn, and damage is ≥1.5x what the same
  move would have dealt without Tera (because Tera STAB kicks in).
- `GimmickUsed` event appears in the recorded battle log at the right
  turn, with the right kind and slot.
- `Ruleset` rejects a second Tera activation in the same battle.
- `./gradlew test ktlintCheck detekt` green.

## Related

- **Diary 036** — original gimmick state stub; this is the first real
  consumer of that slot.
- **Diary 016** — type override seam; Tera reuses it.
- **Diary 090** — Gen 3 implementation (in flight); tests the
  *injection* claim. Tera tests the *new mechanic* claim. Different
  litmus tests for the same architecture.

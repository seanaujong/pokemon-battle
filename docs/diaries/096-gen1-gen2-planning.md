# Diary 096: Gen 1 / Gen 2 implementations — forcing the CategoryResolver seam (planning)

**Date:** 2026-04-14
**Status:** Planning. Not started.

## Why this diary exists

Diary 090 shipped Gen III as a near-copy of Gen V with one
mechanical difference: physical/special is determined by move type,
not per-move category. The review called out the duplication
(`GenIIIDamageCalculator` copies ~70 lines of `GenVDamageCalculator`)
and explicitly rejected extracting a seam *yet*, on the "shape a seam
after you have three data points, not one" principle.

Gen 2 and Gen 1 are the other two data points. Both share the Gen
I–III physical-by-type rule (so they're `isPhysicalType` consumers
like Gen III), but each has its own *additional* differences that
compound honest signal about where the calc-DI seam needs more
breakdown.

Filing this so it doesn't slip.

## Why this is parked

Same shape as 095 (Mega): not a one-session ship. Gen 1 and Gen 2
each have multiple structural deltas:

**Gen 2 deltas from Gen III:**
- No abilities (ability registry is empty — `abilityMod` always 1.0).
- Held items exist but fewer (Leftovers, Lum Berry — trivial subset
  of our item registry).
- Crit formula uses move-specific rates (High Critical Hit Ratio was
  a move property in Gen 2, not a generic field).
- Stat-based crit chance (speed-derived rather than flat 1/16).

**Gen 1 deltas from Gen 2:**
- No Special split: one `Special` stat (SpA == SpD). Our
  `StatType.SPECIAL_ATTACK` / `SPECIAL_DEFENSE` split doesn't model
  this. Either we collapse them at calc time (cheaper) or add a
  `SPECIAL` stat variant (structural).
- No held items at all.
- Crit formula is speed/2 % chance, capped at ~255/256.
- Frozen is permanent (no thaw chance) in Gen 1 — affects
  `MoveExecutionPhase.checkStatusThenExecute`, which currently uses
  the Gen 2+ 20% thaw rate.
- Partial trapping (Wrap, Fire Spin) prevents the target from
  moving while locking the attacker — a whole mechanic we don't
  have.

Any one of Gen 1's quirks (Special collapse, permanent freeze,
partial trapping) is an architectural conversation. Gen 2 is
achievable in a single diary; Gen 1 is two or three.

## The seam question (the real payoff)

Three damage calculators (Gen III, Gen II, Gen I) all share
"isPhysicalType" and diverge on other axes. At three data points
the seam extraction becomes honest:

- `CategoryResolver` — maps `(move) -> isPhysical`. Two
  implementations: per-move (Gen IV+) and per-type (Gen I–III).
- `CritResolver` — decides crit chance and multiplier. Gen V flat
  vs Gen III move-specific vs Gen I speed-derived.
- `StabResolver` — the seam we declined in diary 091. Three gens
  all share the 1.5× rule though, so this one might not need
  extraction until Tera lands in a gen-specific calc.

A stretch goal of this work: refactor `GenVDamageCalculator` and
`GenIIIDamageCalculator` to share a core and differ only on the
resolvers they inject. That removes the ~70-line duplication flagged
in diary 090 and proves the architecture can do the thing we
rejected doing prematurely.

## Rough plan (when picked up)

- **Diary N+1: CategoryResolver seam.** Extract the resolver
  interface, plumb it into the existing Gen III + Gen V calcs. No
  behaviour change. Tests prove byte-for-byte equivalence on the
  matrix.
- **Diary N+2: Gen II damage calc + registry bundle.** Empty
  ability registry, minimal item registry. Matrix runner accepts
  `gen2`. Observable delta: no Intimidate, no Life Orb, etc.
- **Diary N+3: Gen I `Special` stat collapse.** Probably a
  `StatType` refactor — consolidate SpA/SpD into Special at calc
  time, or let the calc compute them both as the same value.
- **Diary N+4: Gen I calc + registry.** Matrix runner accepts
  `gen1`. Compare to Gen II to isolate the Special split's damage
  impact.

## Open design questions

- **`CategoryResolver` as damage-calc-scoped or
  MoveExecutionPhase-scoped?** Damage calc is fine for the physical
  vs special split, but other phases also read `move.category`
  (e.g. MoveOrderPhase for priority ordering in certain edge cases).
  If the resolver is calc-scoped, those phases still use
  `move.category` directly — inconsistent. If phase-scoped, it's
  plumbed through many phases.
- **Does the Pokedex need gen-specific base stats?** Some Pokemon
  had different base stats in Gen 1 vs Gen 2 (e.g., Mewtwo's SpD
  was 154 in Gen 1 because Special was one stat, then split to
  154/90 in Gen 2). Probably out of scope for a "first Gen 1" ship
  — use Gen 2+ stats and note the discrepancy.
- **Crit resolver scope.** Gen 1's crit formula uses the attacker's
  *base* speed, not stage-modified. That's a cross-gen detail worth
  isolating in a `CritResolver` vs inlining.

## Validation signal (when shipped)

- Matrix runs under `gen1`, `gen2`, `gen3`, `gen4`, `gen5` each
  produce a different distribution of outcomes against the same
  seeded corpus. If Gen 1 and Gen 2 produce identical output,
  something is wrong.
- `CategoryResolver` extraction preserves existing Gen III vs Gen V
  matrix outputs byte-for-byte (the 161/180 match from diary 090).
- `./gradlew test ktlintCheck detekt` green after each ship.

## Related

- **Diary 090** — Gen III ship. This diary is its honest
  continuation; the "shape a seam after three data points" principle
  from 090 is what drives 096's plan.
- **Diary 095** — Mega Evolution planning. Both 095 and 096 are
  architecture-exercising pushes; either is a legitimate next step
  after Smogon-team integration (diary 097 and onward).
- **Diary 028** — data shape divergence. Early-era thinking about
  gen differences; the injectable-interface pattern it described is
  exactly what we're extending here.
- **Diary 017** — injectable type chart. Same pattern this diary
  proposes for CategoryResolver: narrow interface, default
  implementation, gen-specific variants.

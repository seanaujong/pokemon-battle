# 046 — Multi-hit moves (Rock Blast, Double Slap)

## Goal

Implement multi-hit moves: ROCK_BLAST (Rock, Physical, 25 BP) and DOUBLE_SLAP
(Normal, Physical, 15 BP). Each use hits 2–5 times against the same target.
Each hit independently rolls damage, crit, and runs the per-hit intercept
pipeline (Sturdy, Focus Sash, etc.).

## Decisions

- **`Move.hitCount: IntRange?`** — `null` means single hit (preserves existing
  behavior). Non-null means "roll within this range for hit count." Chose
  `IntRange` so different moves could in principle specify 2..2 (Double Kick)
  or custom ranges, without inventing a whole new sealed hierarchy.
- **Uniform distribution via `roll(move.hitCount)`** — real-game distribution
  is 35/35/15/15 across 2/3/4/5, but the task spec (and simplicity) says use
  uniform. We document this is a simplification; weighted distribution can be
  added later by composing a different `roll` lambda or a dedicated helper.
- **Faint mid-chain** — stop hitting if target faints between hits. Matches
  in-game behavior and avoids emitting DamageDealt against a 0-HP target.
- **Per-hit intercept logic preserved** — Focus Sash, Sturdy, crit roll, damage
  calculation all run inside the loop, unchanged. Any per-hit faint emits
  `PokemonFainted` and breaks the loop.
- **Test `roll` lambda** — using `{ 100 }` breaks because `roll(2..5)` would
  return 100 (out of range). Tests inject a smarter lambda:
  `{ range -> if (range == 2..5) desired else 100 }` so hit count is fixed
  while damage rolls stay at 100.

## Plan

- [x] Add `hitCount: IntRange? = null` to `Move`
- [x] Extract per-hit logic in `MoveExecutionPhase` into a helper; wrap it in
      a loop driven by `hitCount`
- [x] Register `ROCK_BLAST` and `DOUBLE_SLAP` in `MoveDex`
- [x] Add tests in `MultiHitMovesTest.kt`:
  - ROCK_BLAST with hit-count roll of 5 produces 5 DamageDealt events
  - ROCK_BLAST with hit-count roll of 2 produces 2 DamageDealt events
  - DOUBLE_SLAP also respects hit count
  - Focus Sash + multi-hit: earlier hits consume Sash, later hit KOs
  - Multi-hit stops when target faints
- [x] `./gradlew test ktlintCheck detekt`

## Outcomes

Implementation landed cleanly. One surprise: with hitCount in the `Move` data
class, detekt didn't complain about an extra nullable field because it's a
simple `val` with a default. The damage-per-target helper was already
refactored enough that the loop wrapper is a thin addition — most logic stays
in `applyOneHit`.

## Next up

- Weighted hit-count distribution (35/35/15/15) — probably a dedicated
  `HitCountRoller` interface so tests can inject a fixed count.
- Skill Link ability (always 5 hits) becomes a one-liner on top of this.
- Loaded Dice (item: 4–5 hits) — same.

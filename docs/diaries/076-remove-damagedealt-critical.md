# Diary 076: Remove `DamageDealt.critical`, make `CriticalHit` the single source

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Diary 069 step added `CriticalHit` as a parallel event alongside
`DamageDealt.critical`. That was correct staging: introduce the new event
without breaking existing consumers, then migrate.

After migrating `BattleAnalyzer` (commit `5b2ef61`) the boolean on
`DamageDealt` had **one remaining purpose** — it was asserted by three
tests in `CriticalHitTest.kt`. Production code, analytics, and rendering
all consume `CriticalHit` events. That's the signal to delete: a field
with only test-code consumers is no longer a contract, it's just noise.

## What changed

- `DamageDealt` loses its `critical: Boolean` parameter.
- `DamageDealtJson` DTO loses the field. Wire protocol bumped implicitly —
  but per diary 069's protocolVersion discussion, v1 is a mismatch
  detector, not a BC commitment. The Python smoke test and every
  in-repo client move together.
- `MoveExecutionPhase` stops passing `isCritical` to `DamageDealt`; it
  still emits `CriticalHit` ahead of damage when the crit roll hits.
- Three `CriticalHitTest` assertions migrate from
  `filter { it.critical }` to `filterIsInstance<CriticalHit>()`.
- `TextRenderer` comment updated: the crit line is single-sourced via
  `renderCriticalHit`.

## Validation signal

- `./gradlew test ktlintCheck detekt` green.
- Python smoke test passes with rebuilt server binary.
- `grep -r "\.critical\b" --include="*.kt" .` returns zero matches.

## Why this is safe now but wouldn't have been at diary 069

Diary 069 needed backward-compat because:
- Analytics counted crits via `.critical` (now migrated).
- Rendering rendered the "A critical hit!" line via `.critical` (now
  single-sourced on `CriticalHit`).
- No positive test coverage for `CriticalHit` existed yet (added in
  `5b2ef61` and this commit).

Each of those dependencies went away between 069 and now. Deletion is
the payoff for doing the migration in stages rather than one atomic
cutover.

## Related

- **Diary 069** — introduced `CriticalHit` as a parallel event.
- **Diary 042** — "the event log is a first-class data asset." Each
  observable moment wants a dedicated event; boolean flags on other
  events are a lesser shape.
- **`docs/skills/add-event.md` extension 6b** — the "splitting a field
  into a dedicated event" recipe. This diary is the completion of that
  recipe for the critical-hit case. Extension 6b now has two worked
  examples (the split in 069, the delete here).

# 105 — omit generations where the evolved form doesn't exist

**Status:** Complete

## Goal

`adviseDelays roselia` printed a gen III block for "Delaying Roselia →
Roserade" that flagged *every* Roselia level-up move as "must delay". That is
nonsense: Roserade and the shiny stone are gen IV; in gen III the evolution
cannot be performed at all. Fix: an edge contributes no delay flags in a
version group where its evolved form doesn't exist.

## Diagnosis

`EvolutionDelayAdvisor` evaluated every edge in every version group the *line*
appears in (`line.versionGroups()` is the union across all stages). For a
gen-III game, Roselia exists but Roserade has no learnset, so
`retainedByLevelUp` found nothing retained and flagged all of Roselia's moves
with `alternativeAccess = NONE`. The rule was missing a precondition: you can
only lose a move to an evolution that exists in this game.

## Fix

- `EvolutionLine.hasLearnsetIn(species, versionGroup)` — the bundle records a
  version-group entry for a form only in games the form appears in, so a present
  key is the signal that the form exists there. This is deliberately *keyed*, not
  list-non-empty: a form that exists but learns nothing of interest (a gutted
  stone-evo learnset) still has an entry and its edge is real.
- `EvolutionDelayAdvisor`: skip an edge whose evolved form fails
  `hasLearnsetIn(edge.to, versionGroup)`. The rule lives in the advisor (the pure
  core), so presentation needs no change — the CLI's by-generation view already
  omits a generation whose every version group yields no flags, so gen III now
  drops out for both Roselia edges.

## Validation

- New golden test `roselia into roserade is not flagged before gen IV`: asserts
  no `roserade` flags in `ruby-sapphire` (the bug) **and** that `platinum` still
  returns them (proves the guard is precise, not a blanket suppressor).
- The pre-existing synthetic test that gives the evolved form a present-but-empty
  learnset (`"beta" to mapOf("test" to emptyList())`) still passes — the keyed
  presence check distinguishes it from a non-existent form. (This is exactly why
  the proxy is key-presence and not `movesOf(...).isEmpty()`.)
- `./gradlew :data:test ktlintCheck detekt` green; manual `adviseDelays roselia`
  now starts at gen IV.

## Code review

Walked the checklist.

- **Right layer?** The precondition is part of the rule ("what would evolving
  cost?"), so it belongs in the advisor, not the CLI. Presentation is unchanged.
- **Invariant enforced, not assumed?** The "form exists in a game" fact now has a
  named method (`hasLearnsetIn`) reporting a data fact, while the advisor's
  `evolvedFormExists` carries the rule interpretation — the data fact and its
  meaning are separated and each documented.
- **One name for two concepts?** Caught during implementation: "empty learnset"
  conflates "form absent" with "form present but gutted". Keyed presence keeps
  the two distinct; the gutted-learnset stone evolutions (Simisage, Delcatty)
  still flag correctly.
- **Failure modes / illegal states?** No new states; the edge loop simply skips.
  A form with no learnset at all anywhere is unreachable in real data and would
  just yield no flags.
- **Auditable / readable?** A reader of `adviseDelays` sees the guard and its
  rationale inline; the absent gen III block is now correct-by-omission.
- **Industry comparison:** trivial — this is a missing-precondition bug fix in
  existing pure logic, no new module/interface/format.

No findings to defer.

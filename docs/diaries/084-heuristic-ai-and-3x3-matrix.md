# Diary 084: `HeuristicAI` and the 3×3 eval matrix

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

The 2×2 matrix from diary 079 answered "is TypeAI better than random?"
but couldn't answer "is TypeAI the *best* simple heuristic?" The
answer requires a third strategy.

The motivating question for this diary: **does spending turn 1 on a
stat-boost move pay off against non-trivial opponents?** A strategy
that says "set up first, sweep later" is a staple of competitive play
and has a simple-enough implementation that this diary can ship it in
one session.

## What shipped

### `HeuristicAI`

Delegates to `TypeAI` for attack choices but picks a self-targeted
stat-boost move on turn 1 if one is available and the user's attack /
special-attack / speed stages are all 0. "Already boosted" means any
positive stage; once the user has boosted, HeuristicAI transitions to
attack mode for the remainder of its time on the field.

Detection looks at `pokemon.statStages`. Delegation composes cleanly:
`HeuristicAI` internally holds a `TypeAI` and falls through to it when
setup isn't applicable. If TypeAI's scoring changes later,
HeuristicAI's attack behavior changes with it — intentional.

### `Strategy.HeuristicAI` wired into the matrix runner

Enum gained a third value, `buildSidedAI`'s `when` gained a branch,
`MatrixEvalMain` now runs 3×3 = 9 matchups. No string typos possible
per diary 083's `Strategy` enum.

### Move pool tweaks

Charizard and Venusaur lost `ICE_BEAM` / `GROWL` respectively in favor
of `NASTY_PLOT`, so HeuristicAI has setup options on the active slot
(not just bench). Garchomp and Lucario already had `SWORDS_DANCE`.

### Tests

Three `HeuristicAI` tests in `AITest.kt`:
- picks setup when available and unboosted
- delegates to TypeAI when already boosted
- always attacks when no setup move in pool

## The signal (3×3 matrix, 15 battles per cell)

```
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI       100% (15/15)   100% (15/15)   100% (15/15)
  RandomAI        0% (0/15)     87% (13/15)     27% (4/15)
  HeuristicAI    87% (13/15)   100% (15/15)   100% (15/15)

TypeAI overall vs RandomAI: 100.0% across 30 battles
TypeAI overall vs HeuristicAI: 56.7% across 30 battles
RandomAI overall vs HeuristicAI: 13.3% across 30 battles
```

Five findings:

1. **TypeAI and HeuristicAI are near-peers** at 56.7% orientation-
   averaged (TypeAI's edge). HeuristicAI isn't the trivial loss the
   "wastes turn 1" framing suggested.
2. **Side 1 advantage remains structural.** Every matchup's side-1
   cell is ≥ 87%. Speed + first-move + strong-offensive team on
   Charizard's side dominates regardless of strategy.
3. **HeuristicAI dominates RandomAI** (100% side 1, 73% side 2,
   86.7% orientation-averaged). Pattern matches TypeAI's dominance
   over RandomAI — both smarter-than-random strategies crush random
   by similar margins.
4. **RandomAI-vs-HeuristicAI side 2 is the most interesting cell:**
   HeuristicAI on side 2 wins 11/15. Suggests the setup sometimes
   pays off even from the structurally-disadvantaged position —
   occasional RandomAI missteps (e.g. picking Growl) give
   HeuristicAI the breathing room to use its +2 boost profitably.
5. **TypeAI mirror (100% side 1) unchanged from diary 079**, confirming
   the determinism observation — mirror matches are purely speed-
   decided under fixed rolls.

## Code review

### Diagnostics

- *Testable:* three unit tests cover the core branching (setup /
  post-boost / no-setup-in-pool). Matrix runner is an integration
  test exercising the full path.
- *Readable:* `pickSetupMove` has one branch per decision; internal
  `isPositiveSelfBoost` is a named predicate that matches domain
  language.
- *Layer:* `HeuristicAI` lives in `:ai` alongside TypeAI and RandomAI.
  ✓ No cross-module leaks. No touch to `:engine`. `StatStages` read-
  access is the existing public API.
- *Colocation:* decision logic and setup detection live in one file.
  ✓
- *Hard-to-reverse:* deletion reverts the matrix to 2×2 + removes one
  file. Cheap.
- *Auditable:* `playerTags` preserves `"HeuristicAI"` on each battle
  so the corpus shows which strategy chose what. `clientInfo` carries
  the seed so any specific battle can be replayed.
- *Happy path:* turn 1 checks setup → uses if applicable → else
  delegates to TypeAI. Clean.
- *Failure modes:* a HeuristicAI pokemon with a setup move that also
  appears in the same slot as a damaging move would still pick setup
  turn 1 — if the pokemon is already at low HP, that's fatal. Known
  simplification; a smarter heuristic would check HP and opponent's
  likely next damage. Out of scope for v1. Filed as a future
  enhancement.
- *Silent:* HeuristicAI's choice flows through standard `MoveAttempted`
  events; battle log is self-documenting.
- *Duplicate logic:* none. The `isPositiveSelfBoost` predicate is the
  only piece of `MoveEffect` inspection logic in `:ai`; if a third AI
  needed the same check it would lift to a helper.
- *Illegal state:* none — enum-driven dispatch is exhaustive.
- *Invariants:* `HeuristicAI` assumes `StatStages` positive values
  only come from the user's own setup moves. In this codebase that's
  true (opponent debuffs apply to opponent, not to the HeuristicAI
  user). If a future move "swaps stat stages" or similar, the
  "already boosted" check could misfire. Acceptable gap; noted.
- *Mutation:* none; all reads immutable.
- *Names:* `HeuristicAI` is ambiguous — what heuristic? Considered
  `SetupAI` (more specific), decided against because future
  refinements (HP-aware choice, switch-out logic) would outgrow the
  name. `HeuristicAI` = "general-purpose heuristic" accepts
  extension. Might split later (`SetupAI`, `ConservationAI`,
  `SwitchoutAI`) when we have more variants.
- *Layer-blur:* none. `:ai` imports from `:engine` for types; nothing
  else.
- *Removal:* one file, one enum value, one `when` branch, three
  tests. Reversible.

### Industry comparison

Matches the shape of scripted agents in RL baselines: `DQNAgent`,
`PPOAgent`, `RandomAgent` all live side-by-side and a matrix eval
produces a comparison table. Stable-baselines3 has this exact shape
(vectorized environments × registered policies). Ours is hand-rolled;
the difference is we're at 3 policies × 1 environment and they're at
hundreds × thousands, so framework overhead would outweigh
benefit.

For the "set up once then attack" logic: this is a common
competitive-Pokemon heuristic (Smogon articles call it "setup
sweeper" strategy) and the simplest scripted version looks like what
HeuristicAI does. More sophisticated versions check HP, opponent's
expected damage, team preview — those are real papers (ML for
Pokemon) but several orders of magnitude beyond scope.

### Findings to fix

None urgent. The "HeuristicAI boosts even at critically low HP"
simplification is noted for a future diary; adding HP-gating would be
a ~10-line change when someone cares.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `./gradlew :cli:matrixEval --args="15"` produces a 3×3 matrix with
  135 persisted battles, all carrying `playerTags` and
  `clientInfo=seed=N`.
- `DiaryConventionTest` passes (this diary has the `## Code review`
  section).

## Related

- **Diary 079** — 2×2 matrix eval, the precursor. HeuristicAI was the
  "when you have 3+ strategies" threshold that diary anticipated.
- **Diary 083** — `Strategy` enum + seed recording. Made adding a
  third strategy cheaper — one enum value, one `when` branch, already-
  reproducible.
- **Diary 081** — industry comparison; the RL-agents analogy is
  extended here.
- **`cli/src/main/kotlin/com/pokemon/battle/cli/MatrixEvalMain.kt`** —
  the runner.

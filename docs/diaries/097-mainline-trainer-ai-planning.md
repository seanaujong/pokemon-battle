# Diary 097: Mainline trainer AI — the specific quirky one, not "a smarter AI" (planning)

**Date:** 2026-04-14
**Status:** Planning. Not started.

## Why this diary exists

Prior-conversation direction-setting mentioned MCTS / minimax as a
"stronger AI" push. That's the generic version. The *cool* version
is **replicating Game Freak's actual mainline trainer AI** — the
one in Ruby/Sapphire, Black/White, X/Y, Sun/Moon, Sword/Shield, etc.
— as it really behaved, with its documented quirks.

That's a different shape from "be good at Pokemon":

- Mainline Gen III trainer AI scores each move on a ladder of
  adjustment flags (`AI_FLAG_CHECK_BAD_MOVE`, `AI_FLAG_TRY_TO_FAINT`,
  `AI_FLAG_CHECK_VIABILITY`, ...) set per-trainer. A Youngster
  doesn't get the same flags as the Elite Four.
- Gen V's AI famously leaks knowledge of the player's held items
  via the "would this status affect them?" check — an actual bug
  that speedrunners exploit.
- The "smart" AI in Battle Subway / Battle Tree makes specific
  suboptimal choices (e.g., over-values status moves, mispredicts
  switches) because the underlying heuristic is simple.

Replicating one of these is interesting because:

1. It's **testable against a known-good oracle** — disassembly
   projects (pokeemerald, pokeruby, pret/pokered) have the
   decompiled AI code. We can match their output trace exactly on
   known game states.
2. It exercises **state-inspection surface** we haven't stressed.
   Mainline AI reads: opponent's typing, item, ability, moves used
   so far, stage, status, weather, our own stages, our likely
   faint. Our `BattleState` has all of this; we've never had a
   consumer that touches all of it.
3. It's **historically interesting** — the AI-flag design is the
   same pattern people later formalized as MCTS scoring rollups.
   Modeling it would retroactively document an entire era of game
   design.
4. It produces **better signal than MCTS** at diary scale. MCTS
   needs thousands of rollouts per choice; mainline AI runs in
   constant time per turn. The matrix runner can do 10k battles
   vs 100.

## Why this is parked

Scope. Replicating one generation's trainer AI faithfully takes
real reference work. Rough sizing:

- **Gen 3 (pokeemerald) trainer AI** — `data/battle_ai_scripts.s`
  is ~2000 lines of assembly-like script defining the flag
  mechanics. The C helpers are another ~3000 lines in
  `battle_ai_main.c`. Not all of this is needed (many flags only
  fire in doubles / Battle Frontier), but the subset that matters
  for 1v1 is probably 500–1000 lines of Kotlin.
- **Gen 5 (pokeblack etc.)** — less disassembly maturity, less clean
  to lift. Probably don't start here.
- **Gen 1 (pret/pokered)** — simplest AI of all gens, probably
  ~200–300 lines of Kotlin. Start here for the first pass; the
  architecture claim scales, and Gen 1 AI + Gen 1 damage calc
  (diary 096) become a cohesive "play Gen 1" pair.

Also: our current 3-AI roster (RandomAI, TypeAI, HeuristicAI) is
homogeneously "modern analytics" shape. A `GenIIITrainerAI` or
`GenITrainerAI` would sit alongside them as a deliberately-period-
specific agent. That pairing (period-specific registry + calc +
AI) is what makes "Gen X play" a real thing rather than a damage
number.

## Rough plan (when picked up)

- **Diary N+1 (prereq): one of the gen implementations lands**
  (diary 096, Gen 1 or Gen 2). Without a period-specific calc, the
  AI has nothing to consult that makes its quirks visible.
- **Diary N+2: BattleState inspection audit.** Document exactly
  which queries the mainline AI needs (e.g., "how many of
  opponent's moves have I seen used?" — that's a history question
  we may not currently answer without scanning the event log).
- **Diary N+3: Port the smallest faithful slice.** Gen 1's
  "AI_FLAG" equivalent is the simplest; pick a trainer class (e.g.,
  Cooltrainer) and implement its exact flag set.
- **Diary N+4: Oracle test.** Run known-game-scenario inputs
  through both our port and the decompilation-project binary;
  assert the move choice matches.
- **Diary N+5: Matrix comparison.** `GenITrainerAI` vs our modern
  AIs on a Gen-1-equivalent team pool. The win rate is less the
  point than the *shape* of the mistakes the period AI makes.

## Open design questions

- **Whose behaviour do we claim to replicate?** "The mainline AI"
  isn't one thing — there's one per gen per trainer class. Picking
  one specific trainer class + gen keeps scope honest.
- **Legal / licensing framing.** The decompilation projects (pret,
  pokeemerald) are reverse-engineered but Nintendo has historically
  tolerated them. We'd be *studying* the logic and writing a new
  Kotlin implementation from understanding, not copying assembly.
  Probably fine; worth noting in the eventual diary.
- **How faithful is "faithful"?** The Gen 5 item-peek bug is a
  specific exploit that requires us to replicate the bug. Do we
  preserve it? (Yes — the whole point is historical accuracy, not
  "good play.")
- **UI integration.** A period AI is more fun if a human can
  play against it. That's a :cli push, separate from the AI
  module work.

## Validation signal (when shipped)

- A concrete test: given a known Gen 1 battle state (Pokemon
  Yellow, Cooltrainer Matt, turn 3), our `GenITrainerAI` picks
  the move the decomp project's AI picks. Matches ≥ 95% of a
  curated oracle set (some tolerance for RNG-driven branches we
  can't fully deterministic).
- `./gradlew test ktlintCheck detekt` green.
- Matrix run with the period AI produces visibly different
  mistake distributions than HeuristicAI — e.g. over-uses status,
  mispredicts switches, leaves KOs on the table.

## Related

- **Diary 094** — HeuristicAI Tera loop. Called out that a smarter
  AI would produce more signal. This diary is the interesting
  version of that — not "a smarter AI" but "a specific AI with
  documentable behaviour."
- **Diary 096** — Gen 1 / Gen 2 implementation (planning). Prereq.
  Without a period-specific calc, the period AI has nothing
  period-specific to reason over.
- **Diary 095** — Mega planning. Orthogonal; the two can land in
  either order.
- **Diary 087** — Gen IV registry as a DI probe. Same
  "period-specific behaviour" theme, different axis (registry
  rather than AI).
- **Potential future:** interactive CLI (diary 056's arc) + period
  AI = "play Pokemon Yellow against its actual trainers" is a
  marketable demo outcome.

## The killer app: Nuzlocke prep

A Nuzlocker's planning loop is: *"Given my team, my opponent's known
team, and the actual in-game AI behaviour of that trainer class, what
are my moves that guarantee no faint?"* Today they do this by hand or
with damage calculators + intuition about AI behaviour. A system that
simulates the *actual* in-game AI — not a generic "optimal" AI — is
directly useful.

This reframes the whole thread. Historical accuracy isn't a curio;
it's the feature. The Nuzlocker wants:

- "What does Norman's Slaking do on turn 1 against my Combusken?"
  (Answer depends on Gen III's `AI_FLAG_PREFER_STRONGEST_MOVE` +
  Slaking's Truant ability + Norman's specific flag set.)
- "If I switch to Makuhita at 40% HP, will Whitney's Miltank
  Rollout or Attract?" (Answer depends on Gen II AI's stage-2
  Rollout targeting logic.)
- "How many turns can I burn with Protect before Lance's Dragonite
  uses its setup move?" (Answer depends on Gen II/III Elite Four
  flags.)

Each of those questions is answerable once we have period-specific
calc + period-specific AI. Our engine already does the state
simulation; period AI is the missing piece.

Scope-expanding, yes — but it gives this diary a target that
matters beyond "interesting exercise." Shipping a Nuzlocke-prep
tool (query: "game X, trainer Y, my team Z → battle outcome space")
would be a distinct consumer for the engine alongside the matrix
runner. Filing as an explicit downstream push:

- **Diary N+6: Nuzlocke query harness.** CLI or notebook interface
  that takes (my team, opponent trainer, game) and runs N simulated
  battles with period AI + calc, producing an outcome distribution.
  The matrix runner is 80% of this already — swap "all AI
  strategies" for "the specific trainer's AI" and add a "my team
  from Smogon-team format" input path (diary 097-ish Smogon
  integration).

This use-case is probably the best single test of "is the engine
honest?" we could ship. If our Gen III simulation of Norman's
Slaking produces a different outcome than the real game, the bug is
somewhere specific — calc, registry, AI, or state — and the fix
teaches us which.

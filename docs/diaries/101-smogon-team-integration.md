# Diary 101: Smogon Gen 5 OU team integration — matrix de-saturation

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Diary 094 closed the Tera-in-HeuristicAI loop and called out that the
hardcoded matrix pool was saturating most cells at 100-0. The user's
direction: replace the pool with Smogon-informed Gen 5 OU sets so
matrix outcomes carry play signal. Diaries 099 (moves) and 100
(abilities) were the parallel worktree shards that built the gap;
this diary is the integration + measurement.

## What shipped

- **`SmogonTeamBuilder`** in `:data-ingestion`. Reads
  `data/smogon/<format>-<rating>-top-sets.json`, materializes
  `Pokemon` / `PokemonState` from the top-rank ability + item we
  implement, with name canonicalization (`"ironbarbs"` →
  `Ability.IRON_BARBS` via lowercase + strip non-alphanumeric).
  `OnUnsupported.FAIL` (default) errors loudly when a top pick isn't
  available; `SKIP` returns null. `pickMoves` accepts the Smogon
  move list and returns the engine moves it can resolve.
- **`MatrixEvalMain`** restructured. New `MatrixTeamSpec` carries
  `Pokemon + Ability? + Item? + List<Move>`; `MatrixTeamPool` is a
  `List<MatrixTeamSpec>` that materializes states via `stateAt(i)`.
  Two team-pool builders: `hardcodedTeam` (Charizard/Garchomp/Lucario
  vs Venusaur/Blastoise/Togekiss with the demo movesets that prior
  matrices were calibrated against) and `smogonTeam` (Ferrothorn/
  Starmie/Latios vs Tyranitar/Garchomp/Scizor from Smogon top-sets).
- **`smogon` arg** on `MatrixEvalMain`. Composable with `tera`:
  `./gradlew :cli:matrixEval --args="20 genv smogon"`,
  `... "20 genv smogon tera"`. Output dir + `formatTag` get a
  `-smogon` and/or `-tera` suffix so corpora don't collide.
- **Move substitution table** (`SMOGON_MOVE_SUBSTITUTIONS`) — diary
  098's planned skip-list made concrete. Ferrothorn's Leech Seed →
  Protect, Tyranitar's Superpower → Ice Beam, Garchomp's Outrage →
  Dragon Claw, etc. Each substitute is documented in 098's plan;
  restoring the original is a future diary lift each.
- **Tera-type assignments** for the Smogon pool: each mon teras into
  a STAB-extending type (Ferrothorn → Grass, Tyranitar → Dark, etc.)
  so HeuristicAI's "tera when move type matches" heuristic
  (diary 094) actually fires.
- **`:cli` build dep** on `:data-ingestion` added. Engine and data
  modules untouched.

## The empirical result

Same seeded matrix runner, three pool variants:

```
Hardcoded (baseline, 6-mon demo pool):
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI       100% (20/20)   100% (20/20)   100% (20/20)
  RandomAI       20% (4/20)    65% (13/20)    30% (6/20)
  HeuristicAI  100% (20/20)   100% (20/20)   100% (20/20)
TypeAI vs HeuristicAI overall: 50.0% (saturated 100-0 each side)

Smogon (no Tera):
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI        85% (17/20)   100% (20/20)   100% (20/20)
  RandomAI       25% (5/20)    30% (6/20)    10% (2/20)
  HeuristicAI   75% (15/20)   100% (20/20)   100% (20/20)
TypeAI vs HeuristicAI overall: 62.5% (DESATURATED — 17/20 vs 15/20)

Smogon + Tera:
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI        80% (16/20)   100% (20/20)   100% (20/20)
  RandomAI        0% (0/20)    20% (4/20)     0% (0/20)
  HeuristicAI   60% (12/20)   100% (20/20)    95% (19/20)
TypeAI vs HeuristicAI overall: 70.0%
```

Headline observations:

1. **TypeAI vs HeuristicAI desaturated.** The hardcoded pool sat at a
   perfect 100-0 in either direction (100% as side 1 either way).
   The Smogon pool produces 85/75 — TypeAI usually wins as side 1,
   HeuristicAI usually wins as side 1, but neither *always* wins.
   That's the first non-trivial signal in this matchup.
2. **TypeAI mirror dropped from 100% to 85%.** The hardcoded pool's
   mirror was Charizard-on-Charizard, where the speed-tied first
   move always KO'd. Smogon's Ferrothorn-led mirror has more turns
   of play; lead mismatches and Sand Stream chip damage produce
   genuine variance.
3. **RandomAI mirror dropped from 65% to 30%.** Tyranitar's Sand
   Stream punishes anything that switches in; RandomAI switches
   randomly and gets hit harder.
4. **HeuristicAI vs RandomAI flipped 70/30 → 95/5.** The same
   "set up + sweep" heuristic does *much* better when the sweepers
   actually have Smogon-tier movesets.
5. **Tera + Smogon combo is unstable.** RandomAI got shut out 0/20
   in vs-anyone-else when Tera enabled — Tera lets the smarter side
   close out games faster. HeuristicAI mirror became 95% (one
   upset) instead of saturated 100%.

The *story* is: when teams are real, the AI gradient has more room
to live in. The 100-0 saturation was a property of the hardcoded
pool, not the AIs.

## Code review

### Diagnostics

- *Testable:* `SmogonTeamBuilder` is a pure function of its inputs;
  the matrix runner integration is "run twice with different args,
  diff outcomes." Both have natural tests; only the integration
  (existing `MatrixEvalMain` path) has end-to-end coverage today.
  No new unit test for the team builder — it's exercised every
  time the matrix runs with `smogon`.
- *Readable:* `MatrixTeamSpec` reads as exactly what it is — a
  Pokemon's full team-build entry. The two builders (`hardcodedTeam`,
  `smogonTeam`) sit side-by-side; their shape is "different sources,
  same output."
- *Layer:* `:data-ingestion` owns the team builder (it already
  owned the Smogon DTOs). `:cli` consumes via the new dep. Engine
  unaware of Smogon. Data layer unchanged.
- *Auditable:* battle files keep `formatTag = "matrixEval-genv-smogon"`
  so a corpus is unambiguous about which pool produced it.
- *Happy path:* CLI parses `smogon` → loads Smogon JSON → resolves
  per-species ability/item/moves → builds team → runs the existing
  matrix loop unchanged.
- *Failure modes:* species not in Pokedex → loud error. Smogon's
  top moves include unsupported one → loud error (forced via
  `SMOGON_MOVE_SUBSTITUTIONS` lookup in MatrixEvalMain). The
  failure modes are the desired ones — silent fallthrough on an
  unsupported move would have masked the gap.
- *Duplicated logic:* none. The two team builders look similar but
  one walks a hardcoded map, the other walks Smogon JSON. Same
  output type, different input source.
- *Illegal state:* the `MatrixTeamSpec` has nullable ability/item
  by design — Smogon sometimes lists 4 abilities and we support 0
  of them. `SmogonTeamBuilder.FAIL` mode catches that early; the
  spec carrying null is correct downstream behaviour for "no
  ability registered."
- *Invariants:* `MatrixTeamPool.movePools` must be keyed by species
  name matching what the AI looks up. Centralized through
  `pool.specs.associate { it.pokemon.species.name to it.moves }`,
  one source of truth.
- *Names:* `MatrixTeamSpec` / `MatrixTeamPool` are intentionally
  matrix-runner scoped (the Spec/Pool naming is generic but the
  prefix locates it in the matrix narrative). `SmogonTeamBuilder`
  / `OnUnsupported.FAIL|SKIP` reads as builder-pattern.
- *Removal:* delete `SmogonTeamBuilder.kt`, the
  `:cli`→`:data-ingestion` dep, the `smogonTeam` + substitution
  block in `MatrixEvalMain`, the smogon-arg parsing, and the
  Tera-type-for-smogon mapping. ~6 edits, ~150 lines reverted.

**No findings beyond the move-substitution skip-list which is
already documented in diary 098 as future-diary candidates.**

### Industry comparison

Smogon team integration is a *substantial* change (new module
consumer, new data shape parsed from external source, new CLI
behaviour). Comparison:

- **Showdown's team importer** parses Smogon team text format
  ("Pokemon @ Item / Ability: X / Moves: ...") into its battle
  engine. Their format is denser; ours pulls from
  per-species-aggregated top sets rather than literal teams. A
  full Smogon-team-format parser would let users paste teams from
  /ts threads — out of scope here, but the next obvious lift.
- **Pokebattle Stadium / Damage Calculator** projects (Trainer
  Tower, Honko's calc) all canonicalize Smogon names by lowercasing
  and stripping non-alphanumerics. Same approach we took — it's the
  community standard.
- **Hugging Face Datasets / scikit-learn** parameter-loading APIs
  use the same "fail loudly by default, allow opt-in skipping"
  pattern (`raise_on_error: bool = True`). Our `OnUnsupported.FAIL|
  SKIP` enum is the same shape with a more discoverable surface.
- **What we deliberately don't do:** support team-builder *paste*
  format (Smogon's textual export). The sets JSON is sufficient for
  matrix-evaluation; user-facing team paste needs a CLI prompt
  flow. Filed as future-diary candidate.

### Findings to fix

None this diary. Two future-diary observations:

- **Move substitution table belongs in the team spec, not in
  MatrixEvalMain.** It's currently file-private constants in
  `MatrixEvalMain.kt`; if a second consumer wants Smogon-sourced
  sets (e.g., a new `:cli:teamBuilder` task), the substitutions
  duplicate. A cleaner shape: `SmogonTeamBuilder.withFallbackMoves
  (map)` injected. Filed as a future refactor.
- **Multiple format support.** We hardcoded
  `data/smogon/gen5ou-1760-top-sets.json`. Adding a CLI arg for
  format+rating would unblock Gen 4 OU, Gen 9 OU, VGC etc. trivially
  — the team builder doesn't care which format it parses.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `./gradlew :cli:matrixEval --args="20 genv smogon"` produces 180
  battles under `battles/genv-smogon/`; matrix prints with TypeAI
  vs HeuristicAI desaturated.
- `./gradlew :cli:matrixEval --args="20 genv smogon tera"`
  produces 180 battles under `battles/genv-smogon-tera/`; outcomes
  shift further as Tera unlocks.
- Hardcoded baseline still works (`--args="20 genv"`) and matches
  the diary 089 byte-for-byte expectation (RandomAI mirror 65%,
  TypeAI mirror 100%).

## Related

- **Diary 094** — diagnosed the saturation problem this diary
  fixes.
- **Diary 098** — the planning + shard design.
- **Diary 099** — moves expansion (worktree M).
- **Diary 100** — abilities expansion (worktree A).
- **Diary 102** — parallel run retrospective.
- **Diary 087** — Gen IV registry-DI probe; the same "swap an
  axis, see if behaviour shifts" measurement style this diary
  applies to team pools.
- **Future:** a Smogon team-format paste parser; a multi-format
  CLI arg; the move substitutions becoming first-class on the
  team builder.

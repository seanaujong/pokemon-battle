# Diary 090: Gen III damage calculator — DamageCalculator-DI deep litmus test

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Diary 087 swapped `GenVRegistries` → `GenIVRegistries` and proved the
*registry* seam: different item/ability set, same engine binary,
different observable behaviour. After we fixed the inflation sources
(contact gating in 088, seeded RNG in 089), that diff collapsed to
zero — our team pool simply didn't reach any Gen-V-only effect, so
the "registry swap" was observationally a no-op. Honest, but not
conclusive about the *other* injection seam: the `DamageCalculator`
itself.

Gen III is the natural probe. The rule that's easy to state and
hard to cheat on: **in Gen I–III, physical vs special is determined
by move type, not per-move category.** Sludge Bomb was Special until
Gen IV and is Physical in Gen III. Aura Sphere is Fighting-type
(Physical in Gen III, Special in Gen IV+). Our matrix team pool uses
both — so a correct Gen III implementation must demonstrably produce
different outcomes than Gen V. That's the litmus.

## What shipped

- **`engine/src/main/kotlin/com/pokemon/battle/engine/GenIIIDamageCalculator.kt`**
  — new `DamageCalculator` implementation. Parallel in shape to
  `GenVDamageCalculator`; the one mechanical difference is:

  ```kotlin
  val isPhysical = isPhysicalType(move.type)
  ```

  where `isPhysicalType` returns true for Normal / Fighting / Poison /
  Ground / Flying / Bug / Rock / Ghost / Steel — the Gen I–III
  physical-type set.

- **Public factory functions** on `DamageCalc.kt`:

  ```kotlin
  fun genVDamageCalculator(registries = Registries.empty): DamageCalculator
  fun genIIIDamageCalculator(registries = Registries.empty): DamageCalculator
  ```

  The calc classes themselves remain `internal` (the public surface is
  the interface + factories, not the concrete classes). This keeps
  `TypeChart` internal to `:engine` — consumers can't accidentally
  depend on it.

- **`data/Registries.kt`** gains `GenIIIRegistries` — strict subset of
  `GenIVRegistries`. Dropped items (Gen IV+): Life Orb, Focus Sash,
  Choice Scarf, Choice Specs. Dropped abilities: Snow Cloak, Ice
  Body, Klutz. Kept: Intimidate, Drizzle, Drought, Levitate, Sand
  Veil, Blaze, Overgrow, Torrent, Sturdy, Natural Cure, Leftovers,
  Choice Band, Sitrus Berry.

- **`MatrixEvalMain`** accepts `gen3` / `geniii` as its second arg.
  The runner now picks *both* the registry bundle and the damage
  calculator based on gen:

  ```kotlin
  val damageCalculatorFor: (Registries) -> DamageCalculator =
      when (genArg) {
          "geniii", "gen3" -> ::genIIIDamageCalculator
          else -> ::genVDamageCalculator
      }
  // ... later ...
  MoveExecutionPhase(
      registries,
      damageCalculator = damageCalculatorFor(registries),
      roll = { range -> range.random(engineRandom) },
      chanceCheck = { percent, _ -> engineRandom.nextInt(100) + 1 <= percent },
  )
  ```

## The empirical result

Same 180-battle matrix, same team pools, same seeds. Only the damage
calc + registry bundle changes.

```
Gen III: TypeAI vs RandomAI        95% (across 40 orientation-avg'd)
Gen V  : TypeAI vs RandomAI        90%
Gen III: RandomAI mirror           50%
Gen V  : RandomAI mirror           65%
Gen III: RandomAI vs HeuristicAI   10%
Gen V  : RandomAI vs HeuristicAI   15%
```

**Outcome-matching pairs: 161/180.** Nineteen battles flip their
winner between Gen III and Gen V.

The mechanism: Lucario and Togekiss both run Aura Sphere. In Gen V,
Aura Sphere is a Special move (uses Lucario's / Togekiss's SpA).
In Gen III, Fighting is a physical type, so Aura Sphere uses Atk.
Togekiss has mediocre Atk vs excellent SpA — its damage output drops
materially in Gen III. Venusaur runs Sludge Bomb (Poison → physical
in Gen III), which flips from SpA-based to Atk-based; Venusaur's Atk
and SpA are comparable so the effect is smaller but nonzero.

The 19-battle divergence is **purely mechanical** — we have seeded
randomness (diary 089) and contact-gated Rocky Helmet (diary 088).
There's no noise floor left to hide behind.

## Contrast with diaries 087–089

| Diary | Claim under test | Observable delta | Verdict |
|-------|------------------|------------------|---------|
| 087 | Registry swap shifts behaviour | Inflated by bugs; real delta = 0 on current team pool | Honest no-op |
| 088 | Contact-gating fix changes signal | Collapsed inflated 087 signal | Confirmed |
| 089 | Seeded RNG removes noise | 1-battle residual → 0 | Confirmed |
| **090** | **DamageCalculator swap shifts behaviour** | **19 / 180 battles flip** | **Confirmed** |

Registry-DI is a no-op *on this team pool*. DamageCalculator-DI is
observably not — the P/S split reaches our existing moves.

## Code review

### Diagnostics

- *Testable:* the claim is itself the test. `./gradlew :cli:matrixEval
  --args="20 gen3"` and `--args="20 genv"` produce different
  aggregates. 161/180 pair-matching, reproducible with the same seeds.
  No new unit test — the test shape is "run the matrix twice, diff" —
  integration-level, same as 087/089.
- *Readable:* `GenIIIDamageCalculator` reads nearly side-by-side with
  `GenVDamageCalculator`; the only substantive divergence is the
  `isPhysical` derivation. A reader who knows the Gen V calc can
  understand the Gen III calc in under a minute.
- *Layer:* `GenIIIDamageCalculator` lives in `:engine` — correct;
  it's engine mechanics. `GenIIIRegistries` lives in `:data` — correct;
  it's a catalog of data. `:cli` picks which pair to wire — correct;
  entry points own strategy selection.
- *Auditable:* battle files still record `clientInfo = "seed=N"` plus
  `formatTag = "matrixEval-gen3"`. The gen is round-trippable from the
  persisted metadata.
- *Happy path:* gen3 arg → pick registries + calc → inject both into
  `MoveExecutionPhase` → phase uses calc to resolve damage → events
  flow as usual. No phase logic changed.
- *Failure modes:* unknown `genArg` → `error("Unknown gen: ...")`.
  Caught at startup; error message lists supported values.
- *Duplicated logic:* **yes — intentional.** `GenIIIDamageCalculator`
  duplicates ~70 lines of `GenVDamageCalculator`. Considered extracting
  a `CategoryResolver` seam or using `open` template-method inheritance;
  rejected for now. Rationale: if Gen I/II land, they'll also need
  formula differences (no special split in Gen I, different crit
  formulas, no abilities / no items). A seam now would be shaped by
  one data point; a seam after three gens ship would be shaped by
  reality. Duplication is honest; the cost is bounded at ~70 lines per
  gen calc; the test surface for each is the matrix runner anyway.
- *Illegal state:* none new. `isPhysicalType` covers all 18 types
  exhaustively via an explicit `when` with `else`; Fairy falls through
  to Special which is its Gen VI+ category (Fairy didn't exist in
  Gen III, so it's not meaningful in mainline Gen III play — but if
  a custom-format user puts a Fairy-type on the field under Gen III
  rules, defaulting to Special is the less-surprising choice).
- *Invariants:* `DamageCalculator.calculate` contract unchanged —
  same signature, same `DamageResult`. No phases know or care which
  gen they're running.
- *Mutation:* none — pure function of inputs.
- *Names:* `genIIIDamageCalculator` / `genVDamageCalculator` use
  lowercase Roman numerals matching the existing `GenIIIRegistries` /
  `GenVRegistries` pattern. Factories are lowercase (functions); the
  bundles are uppercase (vals). Consistent with the existing style.
- *Layer-blur:* none. `:cli` imports `Registries` from `:engine` and
  `GenIIIRegistries` from `:data` — the same layering the runner
  already used.
- *Removal:* delete `GenIIIDamageCalculator.kt` + the two factory
  functions + the `GenIIIRegistries` val + revert the `MatrixEvalMain`
  wiring. Four changes, maybe ten minutes, zero other code touched.

**No findings to fix beyond the duplication note above, which is a
deliberate architectural choice, not a defect.**

### Industry comparison

This is a substantial change (new engine file, new public factory
surface, new data bundle, new CLI arg). Comparison:

- **Pokémon Showdown** handles cross-gen simulation by dispatching
  through a `Format` object that carries gen rules. Each gen folder
  overrides scripts that the default (latest-gen) resolves. That's
  the same shape as our registry-DI + calc-DI: narrow seams, default
  = latest, gens override. Our version is smaller (3 seams: registry,
  calc, speed resolver) but mechanically equivalent.
- **Board-game / TCG simulators** (e.g., OCTGN, Tabletop Simulator
  scripting) tend to use *ruleset objects* that bundle every
  configurable behaviour into one injection point. We instead keep
  orthogonal injections (registry, calc, resolver) — each can be
  swapped independently. Cost: more wiring at the entry point.
  Benefit: a custom format can mix-and-match (Gen III damage with
  Gen V items, for testing). Matches the library-not-framework
  design goal.
- **Game engines generally (Unity's ScriptableObject, Unreal's
  DataAsset)** ship "data-driven" behaviour via serialized
  configuration files. We ship it as compiled Kotlin objects in
  `:data`. The tradeoff is developer-velocity (change a number,
  recompile) vs modder-velocity (change a JSON, reload). For an
  experimental rules sandbox, compile-time safety and test coverage
  beat hot-reload; we're not the right shape for player-facing
  modding.
- **What we're deliberately not doing:** wire gen resolution into a
  `Format` object that the engine receives at construction. The
  current phases take their seams independently; they don't know
  there's a "gen" at all. Keeping the engine gen-agnostic means gens
  are a library-consumer concept, not an engine concept — honest
  layering.

### Findings to fix

None filed. One deferred observation:

- If Gen I or Gen II ships, the `CategoryResolver` seam (or a
  `DamageCalculator` factory that takes a `CategoryResolver`) becomes
  attractive: Gen I/II share the type-based P/S rule with Gen III
  (minus the SpA/SpD split in Gen I). At that point duplication
  crosses three calcs and the seam is worth extracting. Not today.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `./gradlew :cli:matrixEval --args="20 gen3"` → produces 180 battles
  under `battles/gen3/`, matrix prints.
- `./gradlew :cli:matrixEval --args="20 genv"` → same seeds, 180
  battles under `battles/genv/`.
- Python diff on outcomes: **161/180 outcome-matching pairs** —
  19 battles' winners flip between gens. Deltas are mechanical,
  not noise (diary 089 removed the noise floor).
- `DiaryConventionTest` passes.

## Related

- **Diary 087** — GenIVRegistries, the registry-DI claim. Partner to
  this: together they exercise both injection axes.
- **Diary 088** — contact-gating; removed one inflation source.
- **Diary 089** — seeded RNG; removed the other inflation source.
  Without 088 + 089, a "Gen III differs from Gen V" result would
  carry noise and be un-attributable.
- **Diary 018** — second-gen implementation planning (foreshadow).
- **Diary 091** — Tera (first real gimmick) — the complementary
  probe of the *mechanic-landing* claim vs this diary's
  *injection-swap* claim.

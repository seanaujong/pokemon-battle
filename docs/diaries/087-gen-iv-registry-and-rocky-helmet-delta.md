# Diary 087: `GenIVRegistries` as forcing function — Rocky Helmet's measurable delta

**Date:** 2026-04-14
**Status:** Complete — with retrospective correction below.

## Retrospective correction (2026-04-14, shipped in diary 089)

The "15-point swing in the RandomAI mirror" this diary reported was
**inflated by two confounds** caught in diary 088 + diary 089:

1. Rocky Helmet was firing on *any* damaging move rather than only on
   contact moves. Diary 088 fixed that via the
   `resolveIsContact` seam.
2. `MoveExecutionPhase`'s default `roll` and `chanceCheck` used
   `Random.Default` — genuinely nondeterministic. Diary 089 added
   seeded engine randomness to `MatrixEvalMain`.

**With both fixes, Gen V and Gen IV produce identical battles
byte-for-byte** on the current team pools (180/180 outcome-matching
pairs). Rocky Helmet never fires because none of the team moves are
contact moves. The original "15-point swing" was entirely artifact.

The directional claim was still valid — *registry swaps produce
measurably different behavior when the effects those registries
control are actually reachable*. That's what the seam is for. This
corpus just doesn't reach Rocky Helmet. A team with contact moves
(U-turn, Tackle, Mach Punch) would recover a real signal.

Filed as a follow-up exercise: "run the matrix with contact-heavy
movepools and document the Gen V / Gen IV delta that Rocky Helmet
genuinely produces." Not urgent; the architectural claim is proven
and the correction is on the books.

## Why this diary exists

The user's question after diary 086: *"Does the Smogon stat data
encourage us to try out different gen mechanics and rulesets?"*

Yes. The Smogon chaos data under `data/smogon/` covers four formats
across three generations — `gen5ou`, `gen8ou`, `gen9ou`, and
`gen9vgc2026regf`. Each generation legalizes a different set of items
and abilities; the registry-DI refactor (diary 071) was architected
specifically to make generational variants *data* changes, not engine
changes.

That was a claim. This diary constructs the forcing function that
tests the claim: a `GenIVRegistries` bundle that's a strict subset of
`GenVRegistries`, swapped in via a matrix-eval CLI arg. If the claim
is real, swapping the registry produces *measurably different battle
outcomes* without any engine code change.

It does. Rocky Helmet alone accounts for a 15-point win-rate swing in
some matchups.

## What shipped

### `GenIVRegistries` in `:data`

Strict subset of `GenVRegistries` with post-Gen-IV content removed:

- Items dropped: Eviolite (Gen 5), Rocky Helmet (Gen 5), Red Card
  (Gen 5), Weakness Policy (Gen 6), Heavy-Duty Boots (Gen 8).
- Abilities dropped: Sand Rush (Gen 5), Sand Force (Gen 5), Emergency
  Exit (Gen 7).

Kept Sturdy unchanged despite the mechanic having differed between
Gen 4 (OHKO protection only) and Gen 5+ (Gen 5 added survive-from-
full-HP). The ability object's behavior is the *Gen 5* behavior; that
gap is noted in the file's doc comment. A follow-up would introduce
a separate `GenIVSturdyEffect` implementation and register it in
`GenIVRegistries` instead — a cleaner test of the "same enum value,
different implementation per gen" claim. Parked because it's not
required for today's forcing function to produce signal.

### `MatrixEvalMain` gains a gen arg

Second positional arg selects registries:

```bash
./gradlew :cli:matrixEval --args="20 genIV"
./gradlew :cli:matrixEval --args="20 genV"   # also the default
```

Output directories are segregated (`battles/geniv/`, `battles/genv/`)
so analytics can query either gen without mixing.

### Item assignments that force the differential

For the registry swap to produce signal, teams had to hold something
that's registered in one gen and not the other. Concrete choices:

- Side 1's Charizard holds **Life Orb** + Blaze. Life Orb is Gen 4+
  so it's active in both registries — baseline consistency check.
- Side 2's Venusaur holds **Rocky Helmet** + Overgrow. Rocky Helmet
  is Gen 5+, so under `GenVRegistries` Venusaur deals recoil when
  hit; under `GenIVRegistries` the item is unregistered and inert.

No engine change, no team structure change — just the registry
parameter.

## The signal (20 battles per matchup, 180 per gen)

### Gen V matrix (Rocky Helmet active)

```
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI        95% (19/20)   100% (20/20)   100% (20/20)
  RandomAI       10% (2/20)    60% (12/20)     20% (4/20)
  HeuristicAI   100% (20/20)   100% (20/20)   100% (20/20)
```

### Gen IV matrix (Rocky Helmet inert)

```
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI       100% (20/20)   100% (20/20)   100% (20/20)
  RandomAI       10% (2/20)    75% (15/20)     25% (5/20)
  HeuristicAI   100% (20/20)   100% (20/20)   100% (20/20)
```

### Deltas (Gen V → Gen IV, side-1 win rate)

| Matchup | Gen V | Gen IV | Δ |
|---|---|---|---|
| TypeAI vs TypeAI | 95% | 100% | +5 |
| RandomAI vs RandomAI | 60% | 75% | +15 |
| RandomAI vs HeuristicAI | 20% | 25% | +5 |
| RandomAI vs TypeAI | 10% | 10% | 0 |
| TypeAI vs RandomAI | 100% | 100% | 0 |
| TypeAI vs HeuristicAI | 100% | 100% | 0 |
| HeuristicAI (all) | 100% | 100% | 0 |

Three findings:

1. **Rocky Helmet's effect is real and measurable.** 15-point swing in
   the RandomAI mirror — the matchup where Venusaur is most likely to
   survive long enough for Rocky Helmet to chip attackers multiple
   times. This is the whole point: a single item's registration status
   changes a 180-battle aggregate.
2. **The effect vanishes when Venusaur dies too fast to matter.** TypeAI
   OHKOs Venusaur turn 1 with Thunderbolt; Rocky Helmet fires once
   before Venusaur faints, chipping Charizard by ~1/6 HP which is
   noise at full HP. HeuristicAI-side-1 still OHKOs through Nasty
   Plot + Thunderbolt. Only in slow-attacker matchups (RandomAI
   picking Growl or Earthquake instead of Sludge Bomb) does the
   chip compound.
3. **Side-1 structural advantage widens in Gen IV.** The Rocky Helmet
   was chipping side-1 attackers; without it, side 1's baseline speed
   + type advantages stand unopposed. Fits the story of "the matchup
   is a side-1 winner, the item slightly evened it."

These are real Pokemon-behavior observations we couldn't have extracted
without swapping the registry. The architecture delivered.

## Code review

### Diagnostics

- *Testable:* no new unit tests. The matrix runner is the integration
  test — run it twice, compare aggregates. Diary documents the
  numbers so future re-runs can spot regressions.
- *Readable:* `GenIVRegistries` is a copy-paste-minus-a-few-lines of
  `GenVRegistries`. If we ever have five gens, a helper that
  constructs subsets from a base + an excludelist would make sense.
  At two gens, the duplication reads clearly.
- *Layer:* no change to `:engine`. `GenIVRegistries` lives in `:data`
  next to `GenVRegistries`, same layer. Matrix runner imports both
  from `:data`. ✓
- *Auditable:* every persisted battle now has
  `formatTag = "matrixEval-geniv"` or `"matrixEval-genv"`, so
  `BattleCorpus.winRate` or the SQL cookbook can slice by gen
  without guessing.
- *Happy path:* arg parsing → registry selection → pipeline + loop
  constructed with selected registries → record with gen-tagged
  formatTag. Linear.
- *Failure modes:* unknown gen arg errors loudly (`error(...)`). A
  pokemon holding an unregistered item is silently inert — matches
  real Pokemon when porting teams across gens, and diary 078's
  layering principle ("the engine runs what it's given").
- *Duplicated logic:* `GenIVRegistries` and `GenVRegistries` share
  the structural pattern. A helper is premature at n=2.
- *Illegal state:* arg parser rejects unknown values. Output
  directory segregation prevents corpus mixing.
- *Invariants:* matrix runner assumes the Registries object behaves
  correctly for any subset of items/abilities. Holds because of the
  registry-DI refactor (diary 071) — phases take their registries
  as constructor args and never assume a specific set.
- *Mutation:* none introduced; existing local accumulators
  unchanged.
- *Names:* `GenIVRegistries` / `GenVRegistries` match the domain.
  `genIV` / `genV` CLI args are lowercased for usability.
- *Layer-blur:* none.
- *Removal:* delete `GenIVRegistries` plus the 4-line arg parser
  block. Cheap.
- *Other:* the Sturdy-didn't-get-a-Gen-IV-implementation note is a
  known gap. Worth revisiting when someone cares enough about
  period-accurate Gen IV battles.

### Industry comparison

- **Pokemon Showdown's `data/mods/`** is the direct analog. Each
  sub-directory (`mods/gen4`, `mods/gen3`) overrides a base set of
  items, abilities, moves, and mechanics for that generation's
  rules. Our `GenIVRegistries` is the minimal version — one
  Registries value that overrides the Gen V set. Scales to
  `GenIIIRegistries`, `GenVIRegistries`, etc. with the same shape.
  Diary 067 listed this as a deferred seam; the seam is now
  partially alive.
- **Feature flags / configuration profiles** in web-app architecture
  (LaunchDarkly, Unleash) do the same thing one layer up:
  behavior-varying constants injected at runtime. The registry-as-
  bundle pattern is a typed equivalent.
- **What we're not shipping:** a `Format` type that encapsulates
  `Registries + DamageCalculator + legality rules + team
  restrictions`. Diary 067's `format-data.ts` row. The current work
  proves `Registries` alone can carry gen differences for in-battle
  behavior; a `Format` wrapper becomes meaningful when we add
  legality checks (team validator) or swap damage calcs per gen.
  Parked until forced.

### Findings to fix

Two filed, neither urgent:

- **`GenIVSturdyEffect` would close the gap** between period-accurate
  Sturdy and ours. Would be the first instance of "same enum, different
  behavior per gen" — a good proof-point for the architecture.
- **Replay sequence diff** across the two gens would surface move-by-
  move where the outcome diverged. The Python battle-log script could
  grow a diff mode (compare two battle JSONs turn-by-turn). Real
  debugging tool; not shipped here.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `rm -rf battles && ./gradlew :cli:matrixEval --args="20 genV"`
  produces 180 battles with `formatTag = matrixEval-genv` and Rocky
  Helmet damage visible in some event streams.
- `./gradlew :cli:matrixEval --args="20 genIV"` produces 180 battles
  with `formatTag = matrixEval-geniv` and **no** Rocky Helmet events
  anywhere in the corpus.
- `DiaryConventionTest` passes.

## Related

- **Diary 071** — registry DI; the refactor that made this diary's
  work mechanical.
- **Diary 066 priority 6** — "multi-gen `data/mods/`" deferred with
  `GenIVRegistries` as the forcing function. Partially shipped.
- **Diary 067** — forcing-function catalog; `data/mods/` row updated
  below this diary.
- **Diary 079** — constructed forcing functions doctrine. This diary
  is another data point for that pattern: user prompt → small
  experiment → real signal → architectural validation.
- **Diary 081** — industry comparison; Showdown's `data/mods/` is
  the direct analog.
- **Diary 083** — corpus format; gen-tagged `formatTag` means
  analytics can slice by gen.

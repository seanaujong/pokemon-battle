# Diary 088: Contact resolution seam — Rocky Helmet / Punching Glove / Long Reach

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Diary 087 introduced a Gen IV-vs-Gen V registry comparison and
measured a 15-point win-rate swing in the RandomAI mirror, attributing
it to Rocky Helmet's presence. The user caught the bug: *"Did we
adjust Rocky Helmet so it only affects on contact moves?"*

We hadn't. Rocky Helmet was firing on every damaging move — including
Flamethrower, Thunderbolt, Ice Beam, Sludge Bomb, Earthquake — all of
which are non-contact under real Gen 5 rules. The "signal" from
diary 087 was partially an artifact of the simplification.

The user's follow-up raised a harder question: Gen 9's **Punching
Glove** negates contact specifically for punching moves. A naive
static `move.contact: Boolean` flag wouldn't handle that — contact is
attacker-context-dependent, not a property of the move alone.

This diary ships the *seam* that handles both:

- Static `Move.contact` flag (defaults to false).
- Attacker-side `overridesContact` hook on `ItemEffect` /
  `AbilityEffect` (default null = no opinion; true / false override).
- `resolveIsContact(move, attacker, items, abilities)` helper in
  `:engine` that layers overrides on top of the static flag.
- Rocky Helmet gated on the resolved contact value.

Punching Glove and Long Reach aren't implemented (Gen 9 / out of
scope), but the seam's contact-override test uses stub effects to
prove both paths would work.

## What shipped

### `Move.contact: Boolean = false`

New field. Marked true on the contact moves in our current `MoveDex`
(matches real Gen 5 rules):

- Tackle, Double Slap, Mach Punch, U-turn, Rock Blast, Fake Out,
  Rapid Spin.

Every other move remains non-contact by default, which is the right
answer for Flamethrower, Earthquake, Sludge Bomb, Ice Beam, Thunderbolt,
etc.

### `overridesContact` hook on both `ItemEffect` and `AbilityEffect`

Default: `fun overridesContact(move: Move): Boolean? = null`. Existing
effects don't override; only new effects that *need* to speak up do.
Punching Glove (future) would return `false` for punching moves;
Long Reach (future) would return `false` for everything.

### `resolveIsContact` helper

Layers: **ability override > item override > static `Move.contact`**.
Ability wins over item by game-rule-of-thumb convention. Both default
to null, so they only participate when they have something to say.

### `onHolderTookDamage` signature extension

Added `contact: Boolean` parameter. The phase computes it once via
`resolveIsContact` and passes to the hook. Rocky Helmet gates on it
(`if (!contact) return emptyList()`); Red Card and Weakness Policy
accept the parameter but ignore it — they're contact-agnostic in
real games too.

### Tests

- `RockyHelmetTest` gained a non-contact assertion (Flamethrower does
  not trigger the helmet). Existing contact-case tests updated to use
  Tackle.
- `ContactResolutionTest` exercises the seam: default resolution,
  item override wins over static flag, ability override wins over
  item override, absent registry entries fall through. No real
  Punching Glove / Long Reach, just stubs — but the stubs prove the
  resolution order is correct.

## The corrected signal

### Gen V matrix (post-fix, contact-gated Rocky Helmet)

```
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI       100% (20/20)   100% (20/20)   100% (20/20)
  RandomAI       10% (2/20)    70% (14/20)     25% (5/20)
  HeuristicAI   100% (20/20)   100% (20/20)   100% (20/20)
```

### Gen IV matrix (unchanged, baseline)

```
                  vs TypeAI    vs RandomAI vs HeuristicAI
  TypeAI       100% (20/20)   100% (20/20)   100% (20/20)
  RandomAI       10% (2/20)    75% (15/20)     25% (5/20)
  HeuristicAI   100% (20/20)   100% (20/20)   100% (20/20)
```

### Diff

- Diary 087's 15-point swing in RandomAI mirror collapses to a
  **1-battle difference** (14 vs 15).
- `grep -l "ROCKY_HELMET" battles/genv/*.json | wc -l` returns **0**
  — zero Rocky Helmet events in the Gen V corpus. Contact gating
  works as intended.
- Every other matchup is identical between gens (the 100%/0%/10%/25%
  cells match byte-for-byte).

### What the 1-battle residual actually is

**Not** Rocky Helmet. The confound is that
`MoveExecutionPhase` defaults `roll` and `chanceCheck` to
`Random.Default`, which is genuinely nondeterministic across runs.
Crits and other chance-based events land in different battles on each
invocation, regardless of the AI's seeded `Random`.

Running Gen V twice produces different 14-vs-15 vs 15-vs-14 splits on
that same cell. This was a hidden confound in diary 087's original
"15-point swing" — some of that swing was Rocky Helmet inflation, but
some was crit-roll noise across runs.

**What this changes for diary 087:** the headline finding was right
directionally (a Gen V item added a measurable effect), but the
quantified magnitude was inflated by the simplification. The real
Gen V effect from Rocky Helmet with contact-correct mechanics, given
the current team pools, is **zero** — none of our team moves are
contact. Smogon gen5ou teams use plenty of contact moves (Swords
Dance + Outrage Garchomp, Mach Punch Conkeldurr, etc.), so a
differently-composed team pool would recover real signal.

### The right follow-up to make the matrix truly reproducible

Pass seeded `roll` and `chanceCheck` to `MoveExecutionPhase` from the
matrix runner. That would eliminate the remaining 1-battle noise and
make Gen V and Gen IV battles with no contact moves bit-for-bit
identical. ~5 lines. Filed; not done in this diary.

## Code review

### Diagnostics

- *Testable:* seam has 4 dedicated unit tests (`ContactResolutionTest`).
  Rocky Helmet's contact gate has 2 dedicated tests (fires on Tackle,
  doesn't fire on Flamethrower). Test coverage is denser than the
  hook's complexity warrants — which is correct for a seam
  multiple future effects will hang off.
- *Readable:* `resolveIsContact` reads as a three-step layering; the
  docstring names the resolution order and the canonical Gen 9
  example.
- *Layer:* `Move.contact` in `model/`. Hooks in `engine/{item,ability}/`
  interfaces. `resolveIsContact` in `engine/`. `:data` concrete
  effects override the hook. Matrix runner uses everything via the
  hook, not by reaching past it. ✓
- *Auditable:* Rocky Helmet's `ItemDamage` event still fires the same
  way when it does fire; the audit trail is unchanged. The corpus
  query `grep ROCKY_HELMET` works on any saved corpus.
- *Happy path:* move-with-contact → hook returns true → Rocky
  Helmet fires. Move-without-contact → hook returns false → Rocky
  Helmet skips.
- *Failure modes:* if an item or ability overrides contact with a
  typo (returns true when it should return false), Rocky Helmet fires
  incorrectly but the event is visible, not silent. Debuggable.
- *Duplicated logic:* `ItemEffect.overridesContact` and
  `AbilityEffect.overridesContact` have identical signatures. A
  shared trait / interface would be over-engineering at n=2.
- *Illegal state:* the hook returns `Boolean?`. null means no
  opinion, true means "force contact," false means "negate." No
  other states.
- *Invariants:* ability override wins over item override. Enforced by
  ordering in `resolveIsContact` and tested explicitly.
- *Mutation:* none.
- *Names:* `overridesContact` names the intent (not "isContact" —
  that would suggest the hook itself decides, rather than adjusting
  the default).
- *Layer-blur:* none. Defender-side effects (Rocky Helmet) never
  inspect attacker's items/abilities directly; they consume the
  resolved boolean.
- *Removal:* delete the hook + helper, revert Rocky Helmet to
  firing on any damage, drop `Move.contact`. ~6 files, maybe 50 LOC.
  Reversible if the architecture proves wrong.

### Industry comparison

- **Pokemon Showdown's implementation** handles this via
  `battle.events.PREPARE_TO_HIT_MOVE` and ability-declared
  `onTrapPokemon` / similar hooks. Showdown's hooks are looser
  (more dynamic, less typed) and have grown ad-hoc over a decade.
  Our typed hook with a narrow signature is a smaller surface —
  appropriate for where we are; Showdown's sprawl reflects having
  every edge case they've ever encountered.
- **The "attacker-context-dependent property" pattern** is common in
  RPG-mechanics engines: RPG Maker plugins, TTRPG rules engines
  (Foundry's pf2e system) all layer "ability modifies move property"
  on top of base move data. Our resolution order (ability > item >
  static) matches D&D 5e's general rule: specific character
  features (like a racial trait) beat equipment features.
- **What we're not shipping:** Protective Pads (negates contact
  *consequences* like Rocky Helmet recoil without negating contact
  itself — different seam), Frisk (ability that peeks at opponent's
  item — different hook entirely), Mold Breaker (ignores certain
  defender abilities — attacker-suppresses-defender, a third shape).
  Each gets its own hook when forced. None today.

### Findings to fix

Two filed, one urgent:

1. **Seed `roll` and `chanceCheck` in `MatrixEvalMain`** — the 1-battle
   residual between Gen V and Gen IV is this noise. Should be fixed to
   make future gen comparisons bit-for-bit reproducible. ~5 lines.
2. **Revisit diary 087's "15-point swing" claim** with the corrected
   data. Directionally right, quantitatively wrong-by-inflation.
   Worth a one-paragraph addendum to 087 noting the correction.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `./gradlew :cli:matrixEval --args="20 genV"` produces 180 battles,
  zero `"ROCKY_HELMET"` events in the corpus.
- `./gradlew :cli:matrixEval --args="20 genIV"` produces 180 battles,
  matchup matrix matches Gen V within 1 battle in one cell.
- `ContactResolutionTest` verifies the seam's resolution order
  (default, item override, ability override).
- `RockyHelmetTest` covers contact-yes and contact-no explicitly.
- `DiaryConventionTest` passes.

## Related

- **Diary 087** — the Gen IV vs Gen V experiment whose "signal" was
  half-artifact. This diary fixes the mechanics; the corrected finding
  is that the matrix's team moves are all non-contact, so Rocky Helmet
  produces zero signal *in this matrix*. A team with contact moves
  (typical competitive Gen 5 OU sets do use them) would recover real
  signal.
- **Diary 071** — registry DI. The reason adding a hook to `ItemEffect`
  + `AbilityEffect` was mechanical.
- **Diary 079 / 082 / 086** — constructed-forcing-function doctrine.
  Punching Glove as forcing function is the third distinct case this
  session (after matrix eval, contact resolution) where a user-posed
  interaction drove a small architectural ship.
- **Diary 081** — industry comparison; RPG-mechanics-engine pattern
  is cited there; resolution-order choice is called out here.

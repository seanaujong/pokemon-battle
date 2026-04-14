# Diary 089: Seeded engine randomness — bit-reproducible matrix runs

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Diary 088's follow-up list included: *"seed `roll` and `chanceCheck` in
`MatrixEvalMain` — the 1-battle residual between Gen V and Gen IV is
this noise."* That 1-battle residual had puzzled me: with contact-
gated Rocky Helmet + zero contact moves in the team pool, Gen V and
Gen IV should have been identical, but they differed by exactly one
battle in one cell.

Root cause: `MoveExecutionPhase`'s default `roll` and `chanceCheck`
use `Random.Default`, which is genuinely nondeterministic across
invocations. Crits and chance-based events land in different battles
on each run, even with a fixed AI seed. That noise was disguising
what would otherwise be a bit-for-bit equivalence.

## What shipped

- `MatrixEvalMain` now constructs a per-battle `Random(seed xor
  SEED_SALT_ENGINE)` and passes seeded closures into `MoveExecutionPhase`:

  ```kotlin
  MoveExecutionPhase(
      registries,
      roll = { range -> range.random(engineRandom) },
      chanceCheck = { percent, _ -> engineRandom.nextInt(100) + 1 <= percent },
  )
  ```

- The `SEED_SALT_ENGINE` xor mixes the AI's battle seed into a
  distinct stream so AI choice and engine rolls don't share the
  same Random. Derived from the same battle seed, so the whole
  battle is reproducible.

- Back-edit on diary 087 noting the "15-point swing" was inflated by
  this confound + the contact-gating bug.

## The empirical result

Running the matrix twice under Gen V produces *identical* aggregates:

```
Gen V run 1 → RandomAI mirror: 13/20 (65%)
Gen V run 2 → RandomAI mirror: 13/20 (65%)
```

Running Gen IV with the same seed:

```
Gen IV        → RandomAI mirror: 13/20 (65%)
```

**DuckDB diff on 180 battles:**

```
Gen V battles: 180, Gen IV seeded: 180, outcome-matching pairs: 180/180
```

With contact-gated Rocky Helmet + seeded engine randomness + zero
contact moves in the team pool, **Gen V and Gen IV produce every
outcome byte-for-byte identical**. The registry swap has zero
observable effect — as expected.

The architectural claim from diary 087 is still valid: registry swaps
produce measurably different behavior *when the effects those
registries control are actually reachable*. Our current team pools
don't reach Rocky Helmet. Swapping registries when nothing's touched
is a no-op, which is the correct behavior for the registry pattern.

## Code review

### Diagnostics

- *Testable:* the claim "Gen V = Gen IV byte-for-byte" is itself the
  test. Ran it (180/180 matching pairs). No new unit test because
  the test shape is "run the matrix runner twice and diff" —
  integration-level.
- *Readable:* `engineRandom` is a named local per iteration. Salt
  constant is named `SEED_SALT_ENGINE` with a comment explaining
  why it's arbitrary-but-distinct.
- *Layer:* `:cli` is the correct place to decide seeding strategy —
  it's the entry point that owns "which battle gets which seed."
  `:engine` stays seed-agnostic, which is right because phases are
  pure fold functions over state.
- *Auditable:* every battle file still carries `clientInfo =
  "seed=N"` per diary 083. Replaying a specific battle now actually
  reproduces it (was nominally possible before, actually noisy due to
  unseeded Random.Default).
- *Happy path:* iteration builds Random → hands closures to phase →
  phase uses them for every roll → all state transitions reproduce.
- *Failure modes:* if a caller forgets to pass seeded closures,
  `MoveExecutionPhase` falls back to `Random.Default` and
  reproducibility is quietly lost. The defaults exist for convenience
  (tests that don't care about reproducibility). Acceptable tradeoff,
  but worth flagging for future matrix-runner-like entry points:
  they should pass seeded closures the same way.
- *Duplicated logic:* none. The two closures reference the same
  `engineRandom` instance.
- *Illegal state:* none.
- *Invariants:* Random consumption order is fixed by the phase's
  implementation. If the phase reorders which roll happens first,
  same seed produces different outcomes. Our phase hasn't changed
  shape in weeks and probably won't soon, but *philosophically* a
  replay is bound to the engine version that produced it.
- *Mutation:* `engineRandom` is a mutable pseudorandom generator
  by design — that's what PRNG means. Not a bug.
- *Names:* `engineRandom` / `SEED_SALT_ENGINE` convey the scope
  without being verbose.
- *Layer-blur:* none.
- *Removal:* delete the three lines, replace `MoveExecutionPhase(...)`
  with the no-arg form. One-minute revert.

### Industry comparison

- **Seeded-random instances for reproducibility** is the standard
  shape in ML evaluation (`torch.manual_seed`, numpy `np.random.seed`,
  Tensorflow seeding guide). We're rolling our own at a tiny scale
  because that's all we need. A framework like Hydra would take
  this over as a config-driven knob in a larger project.
- **Separate RNG streams for different concerns** (AI choice vs
  engine rolls) is also standard — the `SeedSequence` API in numpy
  formalizes this by deriving child streams from a parent. We do
  the lightweight version with an xor salt; functionally equivalent
  at this scale.
- **What we're not shipping:** a cross-session replay feature that
  takes a `battleId` and reproduces the exact battle from the saved
  JSON (with replayed rolls). That would need the engine to *replay*
  recorded `roll` outputs rather than re-roll them — different
  architectural commitment. Parked; the current work is enough for
  gen-comparison reproducibility.

### Findings to fix

One filed, low-priority:

- The Python smoke test (`scripts/smoke-test-external-client.py`) runs
  the server, which doesn't seed engine randomness. Tests pass
  regardless, but crit counts in the server-recorded battles will
  vary across runs. If diary 088's follow-up "contact-heavy team pool
  matrix" ever ships, it should also pass seeded closures through
  `ServerSession`. ~5 lines.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `./gradlew :cli:matrixEval --args="20 genV"` twice → identical
  aggregates.
- `./gradlew :cli:matrixEval --args="20 genIV"` → identical aggregates
  to Gen V on this corpus.
- DuckDB diff: 180/180 outcome-matching pairs between Gen V and Gen IV.
- `DiaryConventionTest` passes.

## Related

- **Diary 087** — amended with a retrospective correction pointing
  here.
- **Diary 088** — contact-gating; the other half of the fix.
- **Diary 083** — `clientInfo = "seed=N"` recording; the seed
  existed, just wasn't all the way through.
- **Diary 081** — industry comparison; experiment-tracking shape
  informs the salt pattern.

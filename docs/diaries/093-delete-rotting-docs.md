# Diary 093: Delete rotting prose docs — tests are the worked examples

**Date:** 2026-04-14
**Status:** Complete.

## Why this diary exists

Audit (prompted by a "are the example docs out of date" question after
shipping diaries 090 and 092): `docs/guide.md`,
`docs/example-simple.md`, and `docs/example-extended.md` had drifted
materially from the engine. Not a little — their core examples used
API shapes that no longer exist:

- `BattleState(pokemon1, pokemon2, field, turn)` — constructor gone;
  current is `BattleState.singles(...)` with `slots: Map<Slot, PokemonState>`.
- `MoveOrderDecided(firstAttacker=..., reason=...)` — signature is
  `MoveOrderDecided(order: List<Slot>, leadReason: OrderReason)`.
- `DamageDealt(..., critical=Boolean)` — the `critical` field is gone;
  crits are a separate `CriticalHit` event (diary 076).
- `TurnPipeline.resolve` shown returning state directly; actually
  returns `TurnResolution.Completed | NeedInput`.
- `calculateDamage(...)` shown as public; it's `internal`, the public
  seam is `DamageCalculator.calculate(...)`.

Four distinct API drifts across three docs. The diaries (076, 036,
061, 062) that made those changes all landed without updating the
prose — because the prose wasn't part of the compile gate.

## What shipped

Three deletions:

- `docs/guide.md`
- `docs/example-simple.md`
- `docs/example-extended.md`

Three edits:

- `docs/index.md` — point readers at the two existing tests as the
  canonical worked examples, drop links to the deleted files.
- `CLAUDE.md` — update the Design Documents list; add a Docs principle
  section codifying "prose that restates API shapes rots; prefer tests
  as worked examples."
- `engine/src/test/kotlin/com/pokemon/battle/CharizardVsVenusaurTest.kt`
  — docstring now calls itself the canonical simple example rather
  than claiming to replay a markdown doc that no longer exists.

The two existing tests that already served as worked examples:

- `CharizardVsVenusaurTest` — single-turn KO with type effectiveness.
  Was already exactly `example-simple.md` in executable form.
- `InfernapeVsSwampertTest` — priority, burn, sandstorm, Leftovers
  in a single turn. Was already exactly `example-extended.md` in
  executable form.

## Why this matters

The smell wasn't "three docs are stale" — it was "we were
maintaining the same worked examples in two places, and only one
place had a compiler forcing it to stay correct." Prose was going to
lose that race every time.

What doesn't belong on the chopping block:

- **Diaries** — append-only, timestamped, not expected to be current.
- **`corpus-format.md` / `corpus-queries.md`** — describe a data
  format / query shape that evolves slowly and isn't restated in
  code.
- **`skills/*.md`** — recipes ("how to add an item"), mostly point
  at code rather than restating its signatures.
- **`CLAUDE.md` / `CONTRIBUTING.md` / `architecture.md`** — the three
  meta docs that ARE updated in place when conventions change. One
  canonical source for the type spec.

The killed docs all shared one trait: they enumerated API shapes that
already existed elsewhere (either in code or in `architecture.md`).
That's the property that makes prose rot.

## Code review

### Diagnostics

- *Testable:* the two test files that replace the prose are already in
  the existing test suite — they can't drift without the engine
  noticing.
- *Readable:* `CharizardVsVenusaurTest` and `InfernapeVsSwampertTest`
  read as narratives (name the scenario, set up state, fire a turn,
  assert the event shape). A new contributor can learn the engine by
  stepping through those tests in a debugger — arguably better
  learning than reading prose.
- *Layer:* these docs were documentation-layer; no code impact. The
  principle addition to `CLAUDE.md` is workflow-layer.
- *Auditable:* `git log docs/*.md` shows the removal; the two tests
  predate this diary by many months, so they're unambiguously
  load-bearing.
- *Happy path:* reader opens `docs/index.md`, clicks through to
  `architecture.md` + the two tests. That flow works.
- *Failure modes:* if a reader bookmarked `guide.md` externally, they
  now hit a 404 on GitHub. Acceptable cost — the content was
  actively misleading.
- *Duplicated logic:* none *now*. The deletion resolves the
  duplication.
- *Removal:* this diary IS a removal, and it's clean.

**No other findings.**

### Industry comparison

- **"Docs as tests"** is a known pattern — Python's `doctest`, Rust's
  doctests, JavaDoc's `@snippet` (Java 18+). They solve the same
  problem: prose examples that compile. We're doing the lighter
  version — regular tests named as worked examples — because our
  worked-example count is small and the existing test suite is
  already the living document.
- **"Single source of truth"** is the framing every docs-tooling
  vendor (Diataxis, Stripe's docs eng, Divio) pushes. Our version:
  `architecture.md` owns the type spec, tests own the worked
  examples, diaries own the history. Three homes, orthogonal jobs.
- **What we're deliberately not doing:** ship a doctest-style
  literate-code tool. At our scale, that's more infrastructure than
  the problem deserves. Two tests + a link are sufficient.

### Findings to fix

None. The deletion itself is the fix.

## Validation

- `grep -r "example-simple\|example-extended\|docs/guide"` returns
  only the expected callers (the diary references in 002 / 006 —
  which are historical, so keeping those stale references is
  correct — and the header docstring on `CharizardVsVenusaurTest`
  which this diary updated).
- `./gradlew test ktlintCheck detekt` green.
- `DiaryConventionTest` passes.

## Related

- **Diary 082** — code-review-in-diary enforcement. Same theme:
  workflow that relies on humans remembering rots; workflow enforced
  by a failing test holds.
- **Diary 061** — "state and event conflation" renamed things the
  deleted example docs still referenced.
- **Diary 076** — extracted `CriticalHit` as its own event; the
  deleted docs still showed the old `DamageDealt.critical` field.
  Exemplary of the drift.
- **Diary 092** — Tera landed without any docs update. That's fine
  *now* because the docs don't claim to enumerate mechanics; it
  would not have been fine under the old prose regime.

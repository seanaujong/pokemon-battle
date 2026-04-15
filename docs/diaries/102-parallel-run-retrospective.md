# Diary 102: Parallel run retrospective — Smogon expansion (M + A)

**Date:** 2026-04-14
**Status:** Complete.

## What was the run

Two worktree subagents launched in parallel (per CLAUDE.md
parallel-variant workflow):

- **Agent M:** add 12 Gen 5 OU damage moves to `MoveDex.kt`.
- **Agent A:** add 4 Gen 5 OU abilities (Iron Barbs, Rough Skin,
  Sand Stream, Technician), including new engine hook
  `AbilityEffect.onHolderTookDamage` + `AbilityDamage` event +
  serialization + render.

Conflict analysis launched with the run: M touches only
`MoveDex.kt`. A touches new ability files + `Registries.kt` +
`Ability.kt` + new engine hook (`AbilityEffect.kt`,
`AbilityEvents.kt`, `MoveExecutionPhase.kt`, serialization, render).
Zero file overlap.

Sequential tail (me): `SmogonTeamBuilder` + `MatrixEvalMain`
restructure + matrix runs + diaries 099 / 100 / 101 / 102.

## The 5-question retro (per CLAUDE.md)

### 1. Did the tasks conflict during merge?

No. Fast-forward of M succeeded directly. A required a rebase on
top of M (since M had merged into main) but the rebase was
conflict-free — A touched no files M had modified.

### 2. Did any agent drift from conventions?

Two minor drifts, both worth flagging for prompt template updates:

- **Agent M** initially wrote `// TODO:` comments for skipped
  secondary effects. Detekt's `ForbiddenComment` rule blocks TODO.
  Agent caught and replaced with `// Skipped:` mid-session. Prompt
  improvement: tell agents the project bans TODO comments and to
  use `Skipped:` / `FIXME:` instead.
- **Agent A's test** used `Pokedex.loadFromClasspath()` to load
  Blissey, but that loader reads the legacy 21-mon CSV which
  doesn't include Blissey. The 101-mon
  `Pokedex.loadJsonFromClasspath()` does. Agent A's test crashed
  with NPE — the dual-loader gotcha is non-obvious because both
  return the same `Map<String, Species>` type. Prompt improvement:
  agents writing tests should default to `loadJsonFromClasspath()`
  unless the test specifically tests the CSV path. Diary 100 logs
  this as a CLAUDE.md-addition candidate.

### 3. Did any agent independently arrive at a useful abstraction worth adopting broadly?

**Yes.** Agent A factored Iron Barbs and Rough Skin (mechanically
identical) into a private `ContactRecoil` class instantiated twice
via Kotlin delegation:

```kotlin
private class ContactRecoil(override val ability: Ability) : AbilityEffect { ... }
object IronBarbsEffect : AbilityEffect by ContactRecoil(Ability.IRON_BARBS)
object RoughSkinEffect : AbilityEffect by ContactRecoil(Ability.ROUGH_SKIN)
```

This is cleaner than the precedent of two near-duplicate effect
files (e.g., `BlazeEffect` + `OvergrowEffect` + `TorrentEffect`
each carry the same 1.5× pinch-type-boost logic but as
copy-pasted classes). The pinch-type-boost trio is a candidate for
the same delegation refactor.

Adopting broadly: the refactor is small (~30 LOC saved across the
three pinch-boost files). Filed as a future-diary candidate; not
worth doing under the integration's pressure.

### 4. Wall-clock time vs serial estimate — was parallelism worth it?

Agent M wrapped in ~2.5 minutes. Agent A's process died with the
machine restart at ~5–6 minutes in (estimated 80% complete based on
file state).

Honest accounting:
- Serial estimate for the same scope (me writing both shards):
  ~25–30 minutes.
- Parallel actual: ~6–8 minutes wall-clock for the agent work +
  ~5 minutes for me to inspect + fix the test bug + commit + merge.
  Plus ~10 minutes I spent waiting / cycling on unrelated tasks
  while agents ran.

Net: ~13 minutes wall-clock for parallel work that would have been
~30 minutes serial. ~2.3× speedup, roughly matching diary 052's
2.6× claim.

The machine restart cost some of the win (had to manually merge
A's incomplete commit). Without the restart, the speedup would have
been higher.

### 5. Did the tasks expose anything about the codebase that was previously hidden?

Three things:

- **Two Pokedex loaders, one map type.** `loadFromClasspath()` (21
  Kanto mons via CSV) and `loadJsonFromClasspath()` (101 ingested
  via JSON manifest) return the same `Map<String, Species>` shape,
  silently. Test code that picks the wrong one fails at
  `pokedex["X"]!!` with a NullPointerException far from the load
  site. Worth: a CLAUDE.md note + maybe a deprecation of the CSV
  loader once we're confident the JSON loader covers all use cases.
- **Detekt's TODO ban is non-obvious.** Agent M hit it and recovered
  mid-session. Putting a "no TODO comments" line in CLAUDE.md
  upstream would have saved the recovery cycle.
- **The pinch-type abilities (BlazeEffect, OvergrowEffect,
  TorrentEffect) are duplication.** Agent A's `ContactRecoil`
  delegation pattern would clean them up. Hidden by virtue of the
  three abilities being defined at different times across multiple
  diaries; only when a fourth identical-shape pair (Iron Barbs +
  Rough Skin) showed up did the pattern become extractable. Filed
  as future cleanup candidate.

## Process improvements queued from this run

For the next parallel-launch prompt template:

1. State the project's `// TODO:` ban and `// Skipped:` /
   `// FIXME:` convention upfront.
2. When an agent's task involves writing tests, default Pokedex
   loader to `loadJsonFromClasspath()` unless the test specifically
   exercises the CSV path.
3. When an agent might add 2+ similar effects, mention the
   `private class X : Effect by ...` delegation pattern as the
   preferred shape.

## Related

- **Diary 099 / 100 / 101** — the work this retro is about.
- **Diary 052** — the canonical 3-agent parallel-run retrospective
  this retro modeled on.
- **Diary 047** — broader parallelism analysis (chokepoints latent
  at current scale).
- **Diary 043** — original parallelism organization principles.

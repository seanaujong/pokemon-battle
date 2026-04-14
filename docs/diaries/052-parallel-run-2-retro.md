# Diary 052: Parallel Run #2 Retro — Three Infrastructure Tasks

**Date:** 2026-04-14
**Status:** Retrospective; informs any formal workflow update

## What ran

Three subagents in parallel, each in isolated worktrees, from the same commit base:
- **Agent 1** — contribution guide (`CONTRIBUTING.md`, diary 049, `CLAUDE.md` pointer)
- **Agent 2** — `@Serializable` on events (plugin + dep + annotations, diary 050, new test)
- **Agent 3** — shared test fixtures (`BattleTestFixture`, migrate 5 files, diary 051)

Conflict analysis before launch predicted no overlap. Task 4 (module split) was
explicitly excluded because it would conflict with all three.

## The 5-question retrospective

### 1. Did the tasks conflict during merge?

**No conflicts.** All three fast-forwarded or rebased cleanly:
- Agent 1: fast-forward straight to main (no intervening work when it started)
- Agent 2: rebased onto main (after Agent 1's merge), then fast-forwarded
- Agent 3: rebased onto main (after Agent 2's merge), then fast-forwarded

The only "shared file" across agents was `build.gradle.kts` (Agent 2) and `CLAUDE.md`
(Agent 1). No one else touched either. The conflict-analysis prediction held.

### 2. Did any agent drift from conventions?

No real drift:
- All three wrote a diary entry in the expected format
- All three ran `./gradlew test ktlintCheck detekt` before committing
- All three used correct commit style (no co-author trailer)
- All three worked from the diary-driven iteration loop described in CLAUDE.md

Minor observation: Agent 2 used its own discretion on the `kotlinx-serialization-json`
version (1.7.3). That's fine here, but for future runs we might specify versions in
the prompt if we care about them.

### 3. Did any agent independently arrive at a useful abstraction worth adopting broadly?

- **Agent 2's `@Transient` on `Move.hitCount`** — pragmatic call. `IntRange` has no
  default serializer; the runtime hit count is already captured in the `DamageDealt`
  event chain. Skipping it at serialization time is the right trade. This pattern
  ("skip compile-time move metadata at runtime because the runtime events already
  capture the observed behavior") may generalize.
- **Agent 3's `fixedRoll(value)` factory** (vs a fixed `fixedRoll` constant) — allows
  min-roll and max-roll tests to reuse the same helper without a new constant each
  time. Small but nice.
- **Agent 1's restraint** — added a 4-line pointer in CLAUDE.md rather than
  duplicating any content. Kept each doc doing one job.

### 4. Wall-clock time vs serial estimate

- Agent 1: 247 seconds
- Agent 2: 281 seconds
- Agent 3: 258 seconds
- Wall-clock total (parallel): ~5 minutes
- Serial estimate: ~13 minutes (sum of durations)

**~2.6× speedup.** The overhead of launching/merging was small compared to the work
itself. Parallelism was worth it.

### 5. Did the tasks expose anything about the codebase I didn't know?

- **Event count is bigger than I tracked.** Agent 2 had to annotate 30 concrete event
  subclasses across 11 files + 4 model types. "Events" had quietly grown into a
  substantial taxonomy.
- **The test-file boilerplate was ~18 lines × ~15 files** (not all migrated). That's
  ~270 lines of duplicated `pipeline()` / `noChance` / `fixedRoll` across tests.
  Agent 3's `BattleTestFixture` removed ~90 lines in the 5 files it migrated, with
  ~180 LOC still available for future migration. Accumulated technical debt I hadn't
  quantified.
- **Minor infrastructure gotcha:** Gradle daemon cached the worktree's directory
  after I `cd`'d into one. Running `./gradlew test` from the main repo silently used
  the worktree as project root, which skipped the new serialization test. Fix:
  `pkill -f GradleDaemon` when switching between worktrees, OR use
  `gradlew -p <absolute-path>`. Worth noting for future parallel runs.

## Changes to make based on this retro

### Adopt

- **The 5-question retrospective itself feels useful.** It took ~10 minutes to write
  and surfaced real observations (the Gradle daemon gotcha, the latent test
  boilerplate). Promote to CLAUDE.md as a formal parallel-work postscript.
- **Mention `gradlew -p` or daemon-management in parallel-work guidance.** The
  daemon-in-worktree bug is subtle and lost me ~3 commands of debugging.

### Defer

- **Version-pinning in prompts** (the kotlinx-serialization 1.7.3 call) — not needed
  yet; adopt if we see drift later.
- **More aggressive fixture migration** — Agent 3 did 5 files on purpose. The
  pattern is now documented; remaining tests can migrate as they're touched.

## Formalization

Based on this run's results being useful, I plan to add to `CLAUDE.md`:
1. A short **Parallel Work** section pointing to this retro as an example
2. The 5-question template
3. The Gradle-daemon caveat + `gradlew -p <path>` workaround

Deferring the formalization to a follow-up unless it happens inline.

## Related

- **Diary 043** — chokepoint analysis (predicted overlaps; none materialized)
- **Diary 047** — parallel stress test run #1 (similar clean-merge finding)
- **Diary 048** — the sealed-interface walkback (lesson: don't preemptively fix
  latent chokepoints)
- **Diaries 049 / 050 / 051** — the three pieces of work this retro covers

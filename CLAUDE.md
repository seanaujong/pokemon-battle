# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Agent / contributor onboarding

New to this repo? Read `CONTRIBUTING.md` at the repo root. It covers the how-tos (adding an item, ability, move, event), testing conventions, build/lint expectations, commit style, and merge discipline — so you don't have to reverse-engineer them. This file (`CLAUDE.md`) covers the *why* and the iteration workflow; `CONTRIBUTING.md` covers the *how*.

## Project Overview

Kotlin project implementing an event-sourced turn-resolution pipeline for Pokemon singles battles. The design is specified in `docs/`. Implementation is underway — see diary entries in `docs/diaries/` for current progress.

**Stack:** Kotlin 2.2.10, JVM 17, Gradle 8.14, kotlin-test + JUnit Platform, ktlint.
**Package:** `com.pokemon.battle`
**Linting:** `./gradlew ktlintCheck` runs ktlint. `./gradlew ktlintFormat` auto-fixes. Disabled rules are documented in `.editorconfig`.
**Static analysis:** `./gradlew detekt` runs detekt. Config in `detekt.yml` with documented threshold adjustments.
**Coverage:** `./gradlew test` generates JaCoCo report at `build/reports/jacoco/test/html/index.html`.

## Architecture

The core pattern is a **phase-based pipeline** where immutable `BattleState` flows through a sequence of `Phase` functions, each emitting `BattleEvent`s that produce the next state.

Key types:
- **BattleState** — immutable snapshot (both Pokemon, field conditions, turn counter). Stats are derived at calc time, never stored.
- **BattleEvent** — sealed hierarchy; each subclass has `apply(state): BattleState`. Events form the complete turn history.
- **Phase** — `fun interface` that reads state + choices and returns `List<BattleEvent>`. Pure function, no mutation.
- **TurnPipeline** — nested fold (outer over phases, inner over events). Orchestration only.

Data layering: `Species` (shared definition) → `Pokemon` (specific instance + level) → `PokemonState` (in-battle: HP, stat stages, status, volatiles).

Initial phases: `MoveOrderPhase` → `MoveExecutionPhase` → `EndOfTurnPhase`. New mechanics are added by defining new event subclasses and phases without modifying existing code.

## Design Documents

- `docs/architecture.md` — complete type definitions, phase pipeline, extensibility model
- `engine/src/test/kotlin/com/pokemon/battle/CharizardVsVenusaurTest.kt` — worked example, executable form
- `engine/src/test/kotlin/com/pokemon/battle/InfernapeVsSwampertTest.kt` — extended example (priority, burn, weather, items)

## Docs principle

Prose that restates API shapes (constructors, event fields, phase return
types) rots the moment the engine evolves. Prefer tests as worked examples,
and let `docs/architecture.md` own the one canonical description. Diary 093
is the rationale for deleting `guide.md` / `example-simple.md` /
`example-extended.md`: they were restating API shapes we already had in
tests, which meant we were maintaining the same thing in two places and
only the tests were forced to stay correct. New API docs should follow the
same rule — if it has a signature that can go stale, prefer the test.

Docs must also be **self-contained**: each canonical doc makes sense on its
own, inter-doc links live only in `docs/index.md`, and doc bodies never
reference diary entries. Diaries are the event log (rationale at the time);
canonical docs are the materialized view (current truth) — distill durable
conclusions up, leave the narrative in the diary. The full convention,
including the canonical-vs-diary two-tier split, lives in `docs/index.md`.

## Design Principles

These are the engine's **key invariants** — the canonical list. `CONTRIBUTING.md`
and `docs/architecture.md` point here rather than restating them, so if one of
these changes, this is the one place to edit.

- All state changes happen exclusively through events (event sourcing). No phase
  mutates state directly.
- All data classes use `val` only — no mutable state anywhere in the engine.
  Top-level and class-level `var`s under `engine/src/main` are blocked by
  `EngineImmutabilityInvariantTest` (diary 070); function-local `var`
  accumulators are fine. This is what lets N battles run concurrently
  in one JVM without shared-state contamination.
- Phases are pure functions of `(state, choices) -> List<BattleEvent>`, testable
  in isolation.
- Sealed hierarchies (`BattleEvent`, `MoveEffect`, `Volatile`, ...) enable
  exhaustive `when` so the compiler catches missing branches.
- Extensibility via new events + phases + registry entries (open/closed
  principle) — existing code stays untouched when mechanics are added.
- The engine package has zero I/O and zero serialization dependencies. Rendering,
  persistence, AI, CLI, and any future UI depend on the engine, never the other
  way around.

## Workflow

How work moves through this project: **Preflight → Iteration Loop →
(back to Preflight for the next task)**. The Parallel variant is the
same loop, sharded across worktree subagents when Preflight #1 says so.
Code Review is *step 5 of the Iteration Loop*, not a separate gate.

### Preflight (before starting)

Before entering the Iteration Loop, run a quick check for decisions that are
easy to miss under momentum. These are judgment calls, not steps:

1. **Can this be parallelized?** If the task splits into ≥2 independent
   chunks that touch disjoint files, launching them as worktree subagents
   is usually faster than serial work. Default *to* parallel when overlap
   is low; the Parallel variant subsection below covers conflict analysis
   and launch shape. Know when *not* to parallelize (small tasks, coupled
   work, skeleton-level refactors).

2. **Tool-match for the change shape.** Large mechanical renames (>3
   files, cross-module, package moves) are IntelliJ's home turf —
   semantic refactors beat grep/sed because they know which `.resolve(`
   belongs to a pipeline vs a phase. Ask the user to run the IntelliJ
   refactor rather than burning iterations on regexes. Small, surgical
   edits stay inline.

3. **Name collision check.** Before introducing a new type, interface,
   or package, grep for the name across the repo. A rename discovered
   after five dependent commits costs more than a 10-second check
   up front. Diary 057 is the canonical example of the collision we
   *did* catch; diary 058 is the broader review pattern.

4. **Scan the diary backlog.**
   `grep -nE "^\*\*Status:\*\*" docs/diaries/*.md` lists each diary's
   status. Skim for anything marked *deferred*, *planning*, or
   *not started* that might touch the area you're about to work on.
   Diaries 058/060/061 are the canonical deferred-refactor set at the
   time of writing; the point isn't those specific diaries, it's the
   habit. Two outcomes:
   - A deferred refactor is directly relevant → fold it in, or
     consciously skip it and note why.
   - Nothing relevant → proceed, but the backlog grew one entry taller;
     schedule the refactor-backlog review mentioned in diary 061 if
     the pile keeps growing.

5. **Identify the validation signal.** Before starting, name the
   concrete green signal that ends the work — the same kind of signal
   the Iteration Loop's step 4 demands at every checkbox. For code:
   usually `./gradlew test ktlintCheck detekt`. For docs: structural
   (diff is small and intentional, no broken cross-references). For
   ingestion / config: a measurable behavior change (a new file
   produced, a new species fetched, a CLI runs end-to-end). If you
   can't name it now, you'll struggle to know when to stop.

   **For subagent prompts**: restate the validation signal explicitly
   so the agent doesn't have to guess. The launch templates' "Verify"
   block exists for this — fill it with the specific commands or
   diff-shape constraints that mean "done."

6. **Forcing functions can be constructed, not just awaited.** Many
   diaries (067, 069, 072, 073, 078) end with "wait for a real
   consumer" — the forcing-function-driven posture that prevents
   speculative work. That posture is valuable: diary 042's "event log
   is a first-class data asset" framing didn't become the analytics
   pipeline until a consumer demanded it, and that delay kept us out
   of the speculation trap.
   
   But *awaiting* and *exercising* are different moves. When
   architecture is at rest — tests green, diaries current, backlog
   deferred — there is an active move available: pick a deferred seam
   and **construct** a consumer that would use it. The matrix-eval
   runner (diary 079) forced `BattleMetadata.playerTags` and
   `BattleCorpus.matchupWinRates`; a one-session exercise that
   validated layering commitments and produced real signal about AI
   behavior. The discipline is the same as diary 066's audit — plan
   first, layer honestly, ship one slice — but the motivation is
   deliberate rather than external.
   
   Rule of thumb: if your last three diaries are all "planning — wait
   for a forcing function," the next active move is probably
   constructing one. The architecture's claim of "ready for X when X
   arrives" is untested until you run X against it.

The Iteration Loop below starts with scope clarification (step 1) and a
diary entry for non-trivial work (step 2) — those remain the mandatory
steps; the preflight is the short pause *before* step 1.

### Iteration Loop

Each feature or chunk of work follows this cycle:

1. **Ask questions** — Before writing code, clarify scope, edge cases, and design decisions with the user. Don't assume.
2. **Write a diary plan** — Create a numbered entry in `docs/diaries/` (e.g., `001-project-setup-and-core-types.md`) covering:
   - Goal: what we're trying to achieve
   - Questions asked and answers received
   - Plan: concrete steps with checkboxes
   - Validation: how we'll know each step is done (compile, test, manual check)
   - Open design questions discovered along the way
3. **Implement incrementally** — Work through the plan step by step. Run `./gradlew compileKotlin` or `./gradlew test` after each step to stay green.
4. **Validate** — Every step must have a concrete "green" signal before moving on. Tests are the primary validation mechanism since the core logic is pure functions.
5. **Code review — write it in the diary. Not optional. Not a temp
   file. Not "later."** Before this diary is marked complete, it MUST
   contain a `## Code review` section. The section MUST walk the
   diagnostic checklist below. The section MUST state "no findings"
   explicitly if the walk found nothing — a missing section is
   indistinguishable from a skipped review. Fix obvious findings in
   the same commit; flag architectural ones for a follow-up diary.

   **If you are about to close out a diary without a `## Code review`
   section, stop and write it.** This is the rule that silently failed
   before diary 082 — the `docs/diaries/temp/` convention made the
   step invisible when skipped, so it got skipped across a whole
   refactor wave. In-diary sections show up on grep:

   ```
   grep -L "## Code review" docs/diaries/*.md
   ```

   lists diaries missing the section. Any "Status: Complete" diary
   that shows up there is a bug.

   **Diagnostics (walk all of these; note which apply):**
   - Is it testable in isolation? If not, what's blocking it?
   - Is it readable? Is it intuitive? Could a new team member
     understand the intent and would the API match how you'd explain it?
   - What layer does each piece belong to? Does it depend on things it
     shouldn't?
   - Is understanding colocated, or is one concept scattered across the
     codebase?
   - Are we making a choice that's hard to reverse later?
   - Is it auditable? If this produced unexpected output, could you
     trace back *why* from the system's own records?
   - What's the happy path? Can you state the main success flow simply?
   - What happens when this fails? Are the failure modes visible, not
     silent?
   - Is there duplicated logic?
   - Can this represent an illegal state that shouldn't exist?
   - What assumptions or invariants does this code rely on? Are they
     enforced or just in our heads?
   - Is there mutation where a pure function is expected?
   - Do the names match the domain? Would a domain expert use these
     words, or have we invented jargon? Are we using one name for two
     different concepts?
   - Do the changes fit cleanly into the existing layers, or is the
     new code blurring them together?
   - If we needed to remove this, how easy would it be? Is it
     entangled with code that should be independent?
   - Does anything else feel off that isn't covered above? Flag it —
     the checklist has blind spots.
   - Think about other codebases, domains, or resources on the
     Internet (use web search). Is there a question we can add to the
     checklist to catch any problems we had this time? Can we add an
     axis to our thinking?

   **Mandatory for substantial changes — industry comparison.** If the
   change introduces a new module, a new interface consumers depend
   on, a new data format, or a new workflow shape, name the closest
   industry analog and state explicitly where we agree, disagree, or
   deliberately differ. Diary 081 mapped our batch/analytics pipeline
   against Parquet / Kafka / Iceberg / Gym / DuckDB; that comparison
   belongs *in the review*, not as a separate diary that only happens
   if someone thinks to ask. For small changes (new item, new event,
   bug fix) the comparison is usually trivial — say so and move on.
6. **Clean up** — Previous guidance pointed at `docs/diaries/temp/`;
   that convention is retired as of diary 082. Reviews live in their
   diary permanently.
7. **Update the diary** — Mark steps done, record decisions made, note anything surprising.
8. **Look ahead** — Where should we go from here? What do these changes let us work on next?
9. **Commit** — When the user asks, commit the completed work.

Diary entries are the paper trail. They capture *why* decisions were made, not just *what* was built.

### Parallel variant

When Preflight #1 says shard, this is the Iteration Loop executed across
worktree subagents. Same steps, same diary discipline, same review — just
parallelized. We saw ~2.6× speedup on a recent 3-agent run (diary 052).

#### 1. Conflict analysis before launch

List each task's expected touched files. Check pairwise for overlap. Classify each
task as "parallelizable with all," "parallelizable with subset," or "must run alone."
Invasive tasks (module splits, sweeping renames, build-config rewrites) are usually
"must run alone" — don't launch them alongside other work.

Record the conflict analysis in the message that launches the run, so it's auditable
later.

#### 2. Launch pattern

Spawn subagents with `isolation: "worktree"` so each gets its own branch. Each agent's
prompt must include:
- Project context (point to this CLAUDE.md and `CONTRIBUTING.md`)
- Clear scope, with the specific files or areas the task should touch
- Anti-scope ("don't touch X, Y, Z") to reduce accidental conflicts
- Diary-entry requirement at a specific diary number
- Convention reminders: iteration loop, no co-author trailer, pre-commit lint expectations
- A concise-return-summary request

#### 3. Merge strategy

Fast-forward-only (`merge.ff = only` in `.git/config`). Merge the first branch directly;
rebase subsequent branches onto main before fast-forwarding. Any rebase conflict is a
real signal — don't paper over it.

Gradle daemon gotcha: if you `cd` into a worktree to rebase, the daemon caches that
directory as its project root. Run `gradlew -p /absolute/path/to/repo test` or
`pkill -f GradleDaemon` before switching back. (Learned in diary 052.)

#### 4. Post-run retrospective (5-question checklist)

Write a short diary entry — proportional to the run. For a 3-agent run, ~10 minutes.
The five questions:

1. **Did the tasks conflict during merge?** Which files, which hunks?
2. **Did any agent drift from conventions?** Signal for prompt improvements.
3. **Did any agent independently arrive at a useful abstraction worth adopting broadly?**
4. **Wall-clock time vs serial estimate — was parallelism worth it?**
5. **Did the tasks expose anything about the codebase that was previously hidden?**

Each recurring entry in question 2 is either (a) a CLAUDE.md rule to add, or (b) a
prompt-template improvement. The retro feeds back into this document.

#### When NOT to parallelize

- The task is small (single feature, <1 hour serial). Overhead isn't worth it.
- Tasks are semantically coupled (one depends on the other's output).
- The work involves a sweeping refactor that touches the module's skeleton.

See diaries 043 and 047 for the broader parallelism analysis and the empirical
finding that chokepoints are latent at current scale. Diary 052 is the canonical
worked example of a successful 3-agent run.

## Tooling Principles

When adding or configuring linters, static analysis, or other tools:

1. **Start with defaults.** Run the tool with no configuration and see what it flags before writing any overrides. This shows you what the tool actually cares about.
2. **Fix code before configuring thresholds.** If the tool flags something, the first question is "is the code wrong?" not "is the threshold too strict?" Most findings are real.
3. **Use inline `@Suppress` with rationale over global threshold changes.** A global threshold loosens the net for all future code. An inline suppress documents why *this specific case* is an exception.
4. **Disable a rule honestly rather than configuring it into irrelevance.** If you're ignoring 25 magic numbers, you've disabled the rule with extra steps. Just disable it and document why.
5. **Check the tool's version.** Configuration property names change between versions. Search for the exact version's docs instead of guessing.
6. **Gradle specifically: the wrapper is the source of truth.** `gradle/wrapper/gradle-wrapper.properties` pins our exact Gradle version (currently 8.14). When suggesting Gradle DSL or build config, always match the docs URL to that version (`docs.gradle.org/<version>/userguide/...`), not "current." Gradle 7 → 8 deprecated a lot of DSL, and 8 → 9 turned configuration-cache on by default and removed more APIs — what works in one major doesn't always work in another. Run `./gradlew --warning-mode all test` periodically to surface any deprecations ahead of the next upgrade; those are the early-warning signal for "this will break on Gradle 9." Our current build emits zero deprecation warnings on 8.14 (checked 2026-04-14).

## Testing Principles

1. **Don't calculate — let the code tell you.** Don't do mental math for damage formulas or stat calculations. Run the code with fixed inputs, read the output, then assert on that value. Mental math is error-prone; the code is deterministic.
2. **Assert on relationships, not assumptions about direction.** Write `assertNotEquals(genV, simplified)` rather than `assertTrue(genV > simplified)` unless the relationship is guaranteed by the mechanic. "Different" is safer than "bigger" when you haven't verified which formula produces more.
3. **Use exact values at fixed rolls.** With `roll = { 100 }`, damage is deterministic. Assert `assertEquals(133, result.damage)` — not a fuzzy range. The code is the authority.
4. **Use qualitative checks for type interactions.** `assertEquals(Effectiveness.SUPER_EFFECTIVE, ...)` — the type chart knows the answer, you don't need to multiply.
5. **Separate mainline tests from extensibility tests.** Within a test file, group tests under two banner comments: a `Mainline Pokemon mechanics — reachable in normal play` section, and a `Custom-format / extensibility` section for scenarios that aren't reachable in mainline play (e.g. constructing a `Volatile` directly to test interaction logic). Extensibility tests have value — they verify the engine's flexibility for hypothetical custom moves, abilities, or multi-turn effects — but they shouldn't be confused with "this is how Pokemon works." Each extensibility test should explain (a) why the scenario is artificial and (b) what engine property it's verifying.

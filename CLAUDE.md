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
- `docs/example-simple.md` — worked example: Charizard KOs Venusaur with Flamethrower
- `docs/example-extended.md` — worked example: multi-phase turn with priority, status, weather, items

## Design Principles

- All state changes happen exclusively through events (event sourcing)
- All data classes use `val` only — no mutable state anywhere
- Sealed hierarchies for `BattleEvent` and `Volatile` enable exhaustiveness checking
- Phases are pure functions testable in isolation
- Extensibility via new events + phases (open/closed principle)

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
5. **Code review** — After implementation, review all changed files by asking these diagnostic questions. Write findings to `docs/diaries/temp/` as a temporary review doc. Fix the obvious ones, flag architectural questions for discussion.
   - Is it testable in isolation? If not, what's blocking it?
   - Is it readable? Is it intuitive? Could a new team member understand the intent and would the API match how you'd explain it?
   - What layer does each piece belong to? Does it depend on things it shouldn't?
   - Is understanding colocated, or is one concept scattered across the codebase?
   - Are we making a choice that's hard to reverse later?
   - Is it auditable? If this produced unexpected output, could you trace back *why* from the system's own records?
   - What's the happy path? Can you state the main success flow simply?
   - What happens when this fails? Are the failure modes visible, not silent?
   - Is there duplicated logic?
   - Can this represent an illegal state that shouldn't exist?
   - What assumptions or invariants does this code rely on? Are they enforced or just in our heads?
   - Is there mutation where a pure function is expected?
   - Do the names match the domain? Would a domain expert use these words, or have we invented jargon? Are we using one name for two different concepts?
   - Do the changes fit cleanly into the existing layers, or is the new code blurring them together?
   - If we needed to remove this, how easy would it be? Is it entangled with code that should be independent?
   - Does anything else feel off that isn't covered above? Flag it — the checklist has blind spots.
   - Think about other codebases, domains, or resources on the Internet (use web search). Is there a question we can add to the checklist to catch any problems we had this time? Can we add an axis to our thinking?
6. **Clean up** — Delete the temp review doc from `docs/diaries/temp/` once fixes are applied.
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

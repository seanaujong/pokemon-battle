# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

## Iteration Loop

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

## Tooling Principles

When adding or configuring linters, static analysis, or other tools:

1. **Start with defaults.** Run the tool with no configuration and see what it flags before writing any overrides. This shows you what the tool actually cares about.
2. **Fix code before configuring thresholds.** If the tool flags something, the first question is "is the code wrong?" not "is the threshold too strict?" Most findings are real.
3. **Use inline `@Suppress` with rationale over global threshold changes.** A global threshold loosens the net for all future code. An inline suppress documents why *this specific case* is an exception.
4. **Disable a rule honestly rather than configuring it into irrelevance.** If you're ignoring 25 magic numbers, you've disabled the rule with extra steps. Just disable it and document why.
5. **Check the tool's version.** Configuration property names change between versions. Search for the exact version's docs instead of guessing.

## Testing Principles

1. **Don't calculate — let the code tell you.** Don't do mental math for damage formulas or stat calculations. Run the code with fixed inputs, read the output, then assert on that value. Mental math is error-prone; the code is deterministic.
2. **Assert on relationships, not assumptions about direction.** Write `assertNotEquals(genV, simplified)` rather than `assertTrue(genV > simplified)` unless the relationship is guaranteed by the mechanic. "Different" is safer than "bigger" when you haven't verified which formula produces more.
3. **Use exact values at fixed rolls.** With `roll = { 100 }`, damage is deterministic. Assert `assertEquals(133, result.damage)` — not a fuzzy range. The code is the authority.
4. **Use qualitative checks for type interactions.** `assertEquals(Effectiveness.SUPER_EFFECTIVE, ...)` — the type chart knows the answer, you don't need to multiply.
5. **Separate mainline tests from extensibility tests.** Within a test file, group tests under two banner comments: a `Mainline Pokemon mechanics — reachable in normal play` section, and a `Custom-format / extensibility` section for scenarios that aren't reachable in mainline play (e.g. constructing a `Volatile` directly to test interaction logic). Extensibility tests have value — they verify the engine's flexibility for hypothetical custom moves, abilities, or multi-turn effects — but they shouldn't be confused with "this is how Pokemon works." Each extensibility test should explain (a) why the scenario is artificial and (b) what engine property it's verifying.

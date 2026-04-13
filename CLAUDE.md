# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kotlin project implementing an event-sourced turn-resolution pipeline for Pokemon singles battles. The design is specified in `docs/`. Implementation is underway — see diary entries in `docs/diaries/` for current progress.

**Stack:** Kotlin 2.2.10, JVM 17, Gradle 8.14, kotlin-test + JUnit Platform.
**Package:** `com.pokemon.battle`

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
   - Is it readable? Could a new team member understand the intent?
   - Is it intuitive? Does the API/structure match how you'd explain it to a junior?
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
   - If we needed to remove this, how easy would it be? Is it entangled with code that should be independent?
   - Does anything else feel off that isn't covered above? Flag it — the checklist has blind spots.
   - Think about other codebases, domains, or resources on the Internet. Is there a question we can add to the checklist to catch any problems we had this time? Can we add an axis to our thinking?
6. **Clean up** — Delete the temp review doc from `docs/diaries/temp/` once fixes are applied.
7. **Update the diary** — Mark steps done, record decisions made, note anything surprising.
8. **Commit** — When the user asks, commit the completed work.

Diary entries are the paper trail. They capture *why* decisions were made, not just *what* was built.

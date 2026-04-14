# Contributing

Audience: new human contributors and AI agents picking up work in this repo. The goal of
this document is to short-circuit the reverse-engineering step — what diary 047 observed
when three parallel subagents each re-derived the same conventions from scratch.

Read this first, then the specific how-to sections you need. For deeper design rationale,
follow the pointers into `docs/architecture.md` and the numbered diaries under
`docs/diaries/`.

---

## 1. Project goals and scope

- **What it is:** a Kotlin, event-sourced turn-resolution pipeline for Pokemon singles
  battles. Pure engine, no I/O, no UI.
- **What it is not:** a full game client, a server, or a replica of every Pokemon
  generation's quirks. The current target is roughly Gen V mechanics with a shape that
  admits gen-specific variants later.
- **Stack:** Kotlin 2.2.10, JVM 17, Gradle 8.14, `kotlin-test` + JUnit Platform,
  ktlint, detekt, JaCoCo. Package root `com.pokemon.battle`.
- **Architecture in one sentence:** immutable `BattleState` flows through a sequence of
  `Phase` functions; each phase emits `BattleEvent`s; events are the sole means of
  mutation. See `docs/architecture.md` for the full treatment.

Key invariants (do not break these without a diary entry):

- All data classes use `val` only. No mutable state anywhere in the engine.
- Every state change goes through a `BattleEvent`. No phase mutates state directly.
- Phases are pure functions of `(state, choices) -> List<BattleEvent>`.
- Sealed hierarchies (`BattleEvent`, `MoveEffect`, `Volatile`) enable exhaustive `when`.
- The engine package has zero I/O. Rendering, persistence, AI all depend on the engine,
  never the other way around.

---

## 2. The diary-driven iteration loop (essentials)

Every feature, refactor, or non-trivial change follows this loop. The full version lives
in `CLAUDE.md` — this is the short form.

1. **Ask questions.** Clarify scope and edge cases before writing code.
2. **Write a diary plan** at `docs/diaries/NNN-short-title.md` with:
   - Goal, questions + answers, plan with checkboxes, validation signals, open questions.
   - Number sequentially — check the directory for the next available N.
3. **Implement incrementally.** Run `./gradlew compileKotlin` or `./gradlew test` after
   each step.
4. **Validate.** Each checkbox should flip only when there is a concrete green signal
   (compile, test pass, manual check).
5. **Self-review** against the checklist in `CLAUDE.md` (section "Iteration Loop" step 5).
   Write findings to `docs/diaries/temp/`; fix the obvious ones; delete the temp file
   when done.
6. **Update the diary** — mark steps done, record decisions, note surprises.
7. **Commit when asked** (see section 8).

Diaries are the paper trail for *why*, not a log of *what*. Future contributors (and
future-you) read them to understand decisions.

---

## 3. How to add an item

Pattern established in **diary 026**. Goal: behavior colocated per item, registries
decouple behavior from callers.

1. **Add the enum value.** Edit `model/Item.kt` — add the new value in alphabetical or
   grouped position. Enums are one of the known parallel-work chokepoints (diary 043);
   additive insertions merge cleanly.
2. **Create the effect file.** New file in `engine/item/<Name>Effect.kt`. Implement
   `ItemEffect`; override only the hooks your item actually uses. Each hook defaults to
   no-op — do not override hooks you don't need.
3. **Register it.** Add your singleton object to the `listOf(...)` in
   `engine/item/ItemRegistry.kt`. Position within the list is cosmetic.
4. **Optional: custom rendering.** If your item has custom text (triggered, consumed,
   healed), create `render/item/<Name>Text.kt` implementing `ItemText`, and register it
   in `render/item/ItemTextRegistry.kt`. Items with no custom text can skip this entirely
   (diary 038 — rendering separated from behavior).
5. **Test it.** New test file `engine/src/test/kotlin/.../<Name>Test.kt`. See section 7
   for conventions.

Do not edit `GenVDamageCalculator`, `MoveExecutionPhase`, `EndOfTurnPhase`, or
`TextRenderer` to special-case your item. If you need a new hook, add it as a defaulted
method on `ItemEffect` and wire it once at the relevant call site.

Existing examples: `LifeOrbEffect`, `LeftoversEffect`, `ChoiceItemEffects`,
`HeavyDutyBootsEffect`.

---

## 4. How to add an ability

Pattern established in **diary 027**. Mirrors items.

1. **Add the enum value** in `model/Ability.kt` if it's not already present.
2. **Create the effect file** in `engine/ability/<Name>Effect.kt` implementing
   `AbilityEffect`. Only override hooks you need.
3. **Register it** in `engine/ability/AbilityRegistry.kt`'s `listOf(...)`.
4. **Optional: custom rendering.** `render/ability/<Name>Text.kt` implementing
   `AbilityText`, registered in `render/ability/AbilityTextRegistry.kt`. The renderer
   falls back to a generic `"X's AbilityName!"` if no text entry exists.
5. **Test it** — abilities-specific test file, same conventions as items.

If your ability suppresses held items (like Klutz), see `KlutzEffect` and the
`suppressesHeldItem` hook — the item registry already respects this via
`effectForHolder`. Cross-registry interactions are addressed in diary 033.

Existing examples: `IntimidateEffect`, `LevitateEffect`, `SturdyEffect`,
`WeatherImmunityEffects` (shared delegation pattern for similar abilities).

---

## 5. How to add a move

Moves are defined as data rather than code wherever possible. The three places you may
need to touch:

1. **`data/MoveDex.kt`** — register the move with its type, power, accuracy, priority,
   target, category, and a list of `MoveEffect`s. Most moves need nothing beyond this.
2. **`model/MoveEffect.kt`** (sealed interface) — only if your move's secondary effect
   does not fit any existing variant (StatBoost, SetVolatile, SelfSwitch, SetTrickRoom,
   SetSideConditionOnUserSide, SetHazardOnOpposingSide, ClearHazardsOnUserSide,
   UserStatBoost, ...). Adding a variant is fine; prefer adding a field on `Move` if the
   mechanic is data-shaped rather than behavior-shaped (diary 047 observed agents
   self-routing around the sealed interface using this reasoning).
3. **`phase/MoveExecutionPhase.kt` — `resolveEffect`** — if you added a new `MoveEffect`
   variant, add a branch here that translates it into one or more `BattleEvent`s. The
   `when` is exhaustive on the sealed hierarchy, so the compiler will flag missing
   branches.

A MoveEffect registry is discussed in diary 029 but not yet extracted — for now the
`when` in `resolveEffect` is the dispatch site.

Existing examples: append-only additions in `MoveDex.kt` — look near `RAPID_SPIN`,
`U_TURN`, `PROTECT`, or `ROCK_BLAST` for reference shapes.

---

## 6. How to add an event

New events extend the sealed `BattleEvent` interface (see `engine/BattleEvent.kt` and
the grouped event files `StatEvents.kt`, `HazardEvents.kt`, `ItemEvents.kt`, etc.).

1. **Create the event class.** Place it in the topically appropriate events file (e.g.
   a new hazard event goes in `HazardEvents.kt`). Implement `BattleEvent` and define
   `apply(state): BattleState` as a pure function that produces the next state.
2. **Emit it from a phase.** Whichever phase discovers the trigger returns the event as
   part of its `List<BattleEvent>`.
3. **Render it.** Add a branch to the `when (event)` in `render/TextRenderer.kt`. The
   compiler's exhaustiveness check ensures you cannot forget this step. Keep the render
   lookup in a helper method named `render<EventName>`.

This dispatch `when` is a known chokepoint (diary 043) — a per-event-type render
registry is the planned escalation when the file gets painful. Until then, the `when`
is the source of truth.

---

## 7. Testing conventions

Tests are the primary validation mechanism because the engine is mostly pure functions.

- **Use `Pokedex.loadFromClasspath()` for mainline tests.** Real species data → real
  stats. Custom `Species` literals are fine for extensibility or corner-case unit tests
  where you want to isolate a single stat.
- **Organize tests into two sections** (per CLAUDE.md Testing Principle #5 and see
  `MultiHitMovesTest.kt`):
  - `// Mainline Pokemon mechanics — reachable in normal play`
  - `// Extensibility / corner cases`
- **`roll = { 100 }` means "roll the max damage roll" (100/100).** With a fixed roll,
  damage becomes deterministic, so you can assert exact values:
  `assertEquals(133, result.damage)`. Don't assert fuzzy ranges when the math is
  deterministic.
- **Don't do mental math.** Run the code with fixed inputs, read the output, assert on
  that value. The code is the authority.
- **Assert relationships, not assumed directions.** Prefer `assertNotEquals` when you
  haven't verified which side of the inequality the real answer falls on.
- **Use qualitative checks for type interactions:**
  `assertEquals(Effectiveness.SUPER_EFFECTIVE, result.effectiveness)` rather than
  multiplying multipliers yourself.

Existing examples: `CharizardVsVenusaurTest` (worked example), `MultiHitMovesTest`
(extensibility sections), `HeavyDutyBootsTest` (item integration).

---

## 8. Build, lint, and commit expectations

### Build and validate

```bash
./gradlew test           # runs tests, emits JaCoCo report
./gradlew ktlintCheck    # lint
./gradlew ktlintFormat   # auto-fix lint
./gradlew detekt         # static analysis (config: detekt.yml)
```

Run `./gradlew test ktlintCheck detekt` at least once before committing. The pre-commit
hook at `.githooks/pre-commit` runs ktlint + detekt automatically — a failing hook means
**the commit did not land**, so fix the issue, re-stage, and make a **new** commit
(do not `--amend`).

If the hook is not installed: `git config core.hooksPath .githooks`.

### Commit style

- **One commit per feature.** Don't squash unrelated changes together.
- **Subject line under ~70 chars**, imperative mood. Look at `git log --oneline`:
  `"Implement multi-hit moves: Rock Blast and Double Slap"`,
  `"Diary 047: parallel stress test findings..."`.
- **No `Co-Authored-By` trailer.** We omit it. See the user's memory notes.
- **Don't skip hooks** (`--no-verify`) unless explicitly asked. If a hook fails, fix the
  underlying issue.
- **Don't commit changes the user didn't ask to commit.** The iteration loop commits at
  step 9, only when the user says so.

### Merge discipline

- **Fast-forward only.** `.git/config` sets `merge.ff = only`. Rebase your branch onto
  the current main before merging rather than producing a merge commit.
- For parallel-agent work in worktrees: commit to your own branch, then fast-forward
  merge into main when the user asks. Diary 047 documents that our parallel work merges
  cleanly at 1-3 contributors; diary 043 maps the chokepoints if that ever stops being
  true.

---

## 9. When in doubt

- **The diary pattern is the friend of future-you.** If you're not sure whether to write
  one, write one. A two-paragraph diary is better than none.
- **Prefer adding to the existing shape over inventing a new one.** If you need a fourth
  way to declare a move behavior, check whether the three existing ones actually
  constrain you — often they don't.
- **`CLAUDE.md` and `docs/architecture.md` are the top of the documentation tree.** The
  numbered diaries are the history that gets you from the top-level picture to the
  specific mechanic.

Useful landmark diaries when joining this project:

- **Diary 026** — item registry pattern (the first extraction).
- **Diary 027** — ability registry (mirrored the item pattern).
- **Diary 038** — rendering separated from behavior (the text registries).
- **Diary 043** — parallel-work chokepoints and escalation paths.
- **Diary 047** — what actually happened running three parallel agents: clean merges,
  self-organization, and the observation that motivated this guide.

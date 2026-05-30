# Contributing

Audience: new human contributors and AI agents picking up work in this repo.
The goal of this document is to short-circuit the reverse-engineering step —
what diary 047 observed when three parallel subagents each re-derived the same
conventions from scratch.

This file covers the **workflow** (how we work). For **specific recipes** (how
to add an item, ability, move, event), see `docs/skills/`. For the **why**
(design principles, architecture), see `CLAUDE.md` and `docs/architecture.md`.
For the history behind decisions, see the numbered diaries under
`docs/diaries/`.

---

## 1. Project goals and scope

- **What it is:** a Kotlin, event-sourced turn-resolution pipeline for Pokemon
  singles battles. Pure engine, no I/O, no UI.
- **What it is not:** a full game client, a server, or a replica of every
  Pokemon generation's quirks. The current target is roughly Gen V mechanics
  with a shape that admits gen-specific variants later.
- **Stack:** Kotlin 2.2.10, JVM 17, Gradle 8.14, `kotlin-test` + JUnit
  Platform, ktlint, detekt, JaCoCo. Package root `com.pokemon.battle`.
- **Architecture in one sentence:** immutable `BattleState` flows through a
  sequence of `Phase` functions; each phase emits `BattleEvent`s; events are
  the sole means of mutation. See `docs/architecture.md` for the full
  treatment.

Key invariants: immutability, events as the sole means of mutation, pure
phases, sealed hierarchies for exhaustive `when`, and a zero-I/O engine
package. See *Design Principles* in `CLAUDE.md` for the canonical list —
don't break them without a diary entry.

---

## 2. The diary-driven iteration loop (essentials)

Every feature, refactor, or non-trivial change follows this loop. The full
version lives in `CLAUDE.md` — this is the short form.

1. **Ask questions.** Clarify scope and edge cases before writing code.
2. **Write a diary plan** at `docs/diaries/NNN-short-title.md` with:
   - Goal, questions + answers, plan with checkboxes, validation signals,
     open questions.
   - Number sequentially — check the directory for the next available N.
3. **Implement incrementally.** Run `./gradlew compileKotlin` or
   `./gradlew test` after each step.
4. **Validate.** Each checkbox should flip only when there is a concrete
   green signal (compile, test pass, manual check).
5. **Self-review** against the checklist in `CLAUDE.md` (section "Iteration
   Loop" step 5). Write findings as a `## Code review` section **in the diary
   itself** — fix the obvious ones in the same commit, flag architectural ones
   for a follow-up diary. A "Complete" diary with no `## Code review` section is
   a bug; `grep -L "## Code review" docs/diaries/*.md` lists offenders.
6. **Update the diary** — mark steps done, record decisions, note surprises.
7. **Commit when asked** (see section 5).

Diaries are the paper trail for *why*, not a log of *what*. Future
contributors (and future-you) read them to understand decisions.

---

## 3. Skills — how to do common tasks

Common additive tasks have their own use-case docs under `docs/skills/`:

- [Add an item](docs/skills/add-item.md)
- [Add an ability](docs/skills/add-ability.md)
- [Add a move](docs/skills/add-move.md)
- [Add a battle event](docs/skills/add-event.md)

Each skill doc names its scope, preconditions, and the main success scenario
step by step. Follow the skill rather than reasoning about the module layout
from scratch — the extraction is a maintained artifact.

Not seeing your task? Check the "What's not here (yet)" list in
`docs/skills/README.md`; if it's genuinely a new shape of work, file a diary.

---

## 4. Testing conventions

Tests are the primary validation mechanism because the engine is mostly pure
functions.

- **Use `Pokedex.loadFromClasspath()` for mainline tests.** Real species data
  → real stats. Custom `Species` literals are fine for extensibility or
  corner-case unit tests where you want to isolate a single stat.
- **Organize tests into two sections** (per CLAUDE.md Testing Principle #5
  and see `MultiHitMovesTest.kt`):
  - `// Mainline Pokemon mechanics — reachable in normal play`
  - `// Extensibility / corner cases`
- **`roll = { 100 }` means "roll the max damage roll" (100/100).** With a
  fixed roll, damage becomes deterministic, so you can assert exact values:
  `assertEquals(133, result.damage)`. Don't assert fuzzy ranges when the math
  is deterministic.
- **Don't do mental math.** Run the code with fixed inputs, read the output,
  assert on that value. The code is the authority.
- **Assert relationships, not assumed directions.** Prefer `assertNotEquals`
  when you haven't verified which side of the inequality the real answer
  falls on.
- **Use qualitative checks for type interactions:**
  `assertEquals(Effectiveness.SUPER_EFFECTIVE, result.effectiveness)` rather
  than multiplying multipliers yourself.

Existing examples: `CharizardVsVenusaurTest` (worked example),
`MultiHitMovesTest` (extensibility sections), `HeavyDutyBootsTest` (item
integration).

---

## 5. Build, lint, and commit expectations

### Build and validate

```bash
./gradlew test           # runs tests, emits JaCoCo report
./gradlew ktlintCheck    # lint
./gradlew ktlintFormat   # auto-fix lint
./gradlew detekt         # static analysis (config: detekt.yml)
```

Run `./gradlew test ktlintCheck detekt` at least once before committing. The
pre-commit hook at `.githooks/pre-commit` runs ktlint + detekt automatically
— a failing hook means **the commit did not land**, so fix the issue,
re-stage, and make a **new** commit (do not `--amend`).

If the hook is not installed: `git config core.hooksPath .githooks`.

### Commit style

- **One commit per feature.** Don't squash unrelated changes together.
- **Subject line under ~70 chars**, imperative mood. Look at `git log --oneline`:
  `"Implement multi-hit moves: Rock Blast and Double Slap"`,
  `"Diary 047: parallel stress test findings..."`.
- **No `Co-Authored-By` trailer.** We omit it. See the user's memory notes.
- **Don't skip hooks** (`--no-verify`) unless explicitly asked. If a hook
  fails, fix the underlying issue.
- **Don't commit changes the user didn't ask to commit.** The iteration loop
  commits at step 7, only when the user says so.

### Merge discipline

- **Fast-forward only.** `.git/config` sets `merge.ff = only`. Rebase your
  branch onto the current main before merging rather than producing a merge
  commit.
- For parallel-agent work in worktrees: commit to your own branch, then
  fast-forward merge into main when the user asks. Diary 047 documents that
  our parallel work merges cleanly at 1-3 contributors; diary 043 maps the
  chokepoints if that ever stops being true.

---

## 6. When in doubt

- **The diary pattern is the friend of future-you.** If you're not sure
  whether to write one, write one. A two-paragraph diary is better than none.
- **Prefer adding to the existing shape over inventing a new one.** If you
  need a fourth way to declare a move behavior, check whether the three
  existing ones actually constrain you — often they don't.
- **`CLAUDE.md` and `docs/architecture.md` are the top of the documentation
  tree.** The numbered diaries are the history that gets you from the
  top-level picture to the specific mechanic; the skill docs are the
  task-level how-tos.

Useful landmark diaries when joining this project:

- **Diary 026** — item registry pattern (the first extraction).
- **Diary 027** — ability registry (mirrored the item pattern).
- **Diary 038** — rendering separated from behavior (the text registries).
- **Diary 043** — parallel-work chokepoints and escalation paths.
- **Diary 047** — what actually happened running three parallel agents:
  clean merges, self-organization, and the observation that motivated this
  guide.
- **Diary 065 / 066 / 068 / 071** — the recent `:data` / registry-DI wave.
  The skill docs reference these for specific gotchas.
- **Diary 069** — the external-language client (`:server` module) litmus
  test.

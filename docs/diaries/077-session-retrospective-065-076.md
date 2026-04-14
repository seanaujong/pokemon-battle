# Diary 077: Session retrospective — diaries 065 through 076

**Date:** 2026-04-14
**Status:** Reference / retrospective. No code changes.

## What this diary is

A step back after 12 diaries (065–076) and roughly 40 commits of
structural refactoring, new features, and process exercises. The goal
is to capture what *worked* about how we shipped this wave — so future-
us can repeat the pattern — and what *didn't*, so we can adjust.

Not a list of what we built. `git log --oneline` does that. This is a
list of *how we decided what to build and in what order*, and what the
recurring surprises were.

## The arc of the wave

Roughly sequential phases, though they overlapped at the edges:

1. **Data extraction (065).** `:data` module; species + moves move out
   of `:engine`.
2. **Audit (066–068).** What else doesn't belong in `:engine`?
   `internal` visibility pass; `api` vs `implementation` pass;
   `:render` and `:ai` extracted as peer modules.
3. **External litmus test (069).** `:server` + Python smoke test.
   Proves the module boundaries work across a runtime boundary.
4. **Concurrency audit (070) + immutability invariant test.** Static
   check that the engine stays free of shared mutable state.
5. **Registry DI (071).** Items and abilities move to `:data`; phases
   take `Registries` as a parameter instead of calling global
   singletons.
6. **Skills split (fa2c7b6 + 072).** Contributor how-tos extracted to
   Cockburn-style use-case docs; move-registry planning diary.
7. **Dogfood (fa2c7b6 → 076).** Added Rocky Helmet, Drought, Quiver
   Dance, Weakness Policy, Natural Cure as skill-doc exercises.
   Introduced and then cleanly removed `DamageDealt.critical`.

Each phase's work made the next phase's work cheaper. That compounding
is the headline signal.

## What worked

### Diary-before-code, always

Every non-trivial change in this wave got a plan diary first. Three
design bugs were caught at the planning stage, not in code:

- **Diary 071's interface-location bug.** Original plan had
  `ItemEffect` / `AbilityEffect` moving to `:data` with the registries.
  The first compile revealed the circular dependency (`:data` already
  depends on `:engine`). Fix: interfaces stay in `:engine` as the
  plugin contract; only concrete effects move. Caught at compile, not
  in PR review or production.
- **Diary 069's server scope boundary.** The plan deferred multiplexing
  explicitly before building. This kept the v1 shape tight — one
  session per process — and surfaced diary 070's concurrency question
  as a separate piece of work.
- **Diary 072's "don't ship the move registry yet" decision.** The
  plan documented the threshold and the two design options (A vs B)
  without committing. We'd have spent weeks on a registry we didn't
  need.

### Extract-when-forced, not extract-when-foreseen

Diary 065 was motivated by "the engine layer knowing about specific
Pokemon is wrong" — a *felt* smell, not a *predicted* need. Same for
diary 071 (items/abilities smelled coupled once the `:data` boundary
existed). Same for diary 076 (`DamageDealt.critical` felt redundant
once `CriticalHit` had analytics consumers).

Contrast with diary 067's catalog of "seams Showdown has that we
don't" — most remain open because no forcing function arrived. Team
validator, translations, replay versioning, multi-gen mods are all
deferred correctly. The discipline of *waiting for the smell* kept
us from pre-building.

### Dogfood immediately after writing docs

Rocky Helmet (exercising `add-item.md`) found the "6b: splitting a
field into an event" extension was missing. Drought (exercising
`add-ability.md`) found the stale hook list and the `AbilityTest.kt`
grouping convention was missing. Quiver Dance (exercising
`add-move.md`) found `StatBoost` vs `UserStatBoost` wasn't explained.
CriticalHit (exercising `add-event.md`) found "core events live in
`BattleEvent.kt`" wasn't said. Weakness Policy found Extension 5a
conflated "new hook" vs "extended signature." Natural Cure found
Extension 1a's "one call site" framing missed the self-switch site.

**Every skill doc had at least one friction caught within 15 minutes
of being written.** The docs are stronger now than they'd have been
from any amount of armchair review.

### Parallel agents worked at this scale

The Weakness Policy / Natural Cure launch was our second parallel run
after diary 052. 3 agents worked there; 2 worked here. Both runs
merged without conflict despite touching overlapping files (registries,
phases). The consistent pattern: **the refactors paid for themselves
in parallel capacity**. Registry-DI made items and abilities
genuinely independent; `internal` visibility gave each agent a clear
scope boundary.

One operational issue recurred: absolute paths in agent prompts
referring to the main repo caused one agent to briefly land on `main`
before catching and recovering. Low-severity but worth naming — the
fix is to rely on git's HEAD (relative paths) inside the worktree and
avoid absolute paths that can escape the isolation.

### `@Suppress` with rationale beat threshold tweaks

Four times across this wave an agent or I hit a detekt threshold
(`TooManyFunctions`, `LongParameterList`, `LongMethod`,
`NestedBlockDepth`). Every time the fix was an inline `@Suppress` with
a one-line "why" comment, not a `detekt.yml` change. This is the
CLAUDE.md tooling principle paying off — global thresholds stay
strict; individual exceptions are documented where they live.

### "Both sides in the same repo" justified protocolVersion enforcement

Diary 069's protocolVersion discussion concluded: we're not
committing to BC, but the field is useful as a mismatch detector.
That reasoning held up — I've changed the protocol twice since
(`CriticalHit` DTO added, `DamageDealt.critical` removed) and both
times updated the Python client atomically. The field hasn't fired
yet, but it's cheap insurance.

## What didn't work

### Regex/sed on test files was risky

The 071 refactor involved bulk-updating test files to pass
`GenVRegistries` into phase constructors. One `awk` dedup accidentally
removed repeated expected-text lines inside test assertions. Caught
by compile errors, recovered via `git checkout`. Lesson: for
non-trivial bulk edits on test code, prefer targeted `Edit` operations
or a pre-commit diff review.

### Skill-doc *drift* showed up quickly

Hook inventories and file-path references in skill docs start drifting
within one refactor. `add-ability.md` listed 9 hooks when the interface
had 10; after Natural Cure it has 11. The durable fix is probably
pointing at the source file rather than enumerating — done for
`add-ability.md`, could be applied to other skill docs. Cockburn-style
structure holds up; specific API counts don't.

### Silent-default traps were real but not catastrophic

Two places in the 071 refactor have `Registries.empty` as a default
(phases and `BattleLoop`). If a test forgets to pass `GenVRegistries`
and *expects* items/abilities to fire, it silently fails the
assertion rather than failing loudly at construction time. Flagged in
diary 071 as a tradeoff we took for lower churn. No actual bug has
come from this — all tests that needed registries got them via the
bulk sed + detekt/test feedback — but the risk remains. A follow-up
could remove the default and let every call site pass explicitly; not
urgent.

### I keep trying to optimize session pacing and getting it wrong

Looking back at this session, I paused for the user to direct me
several times when I should have kept shipping; and twice I kept
shipping when I should have paused. The user's explicit "keep
shipping!" signals were the right forcing function both times — my
instinct toward "is this the right moment to pause?" was noisier than
I thought. Lesson for future sessions: when the user signals momentum,
trust it, and keep the friction summaries at the end rather than
mid-stream.

## Specific patterns to keep

1. **Three-tier extraction rhythm.** Plan diary → implement → back-edit
   docs and related diaries. The back-edit step is where 066 / 068 got
   their "item/ability now moved per 071" notes added, where
   architecture.md stayed in sync, and where CONTRIBUTING.md became
   the skills split. Skipping it lets drift accumulate silently.

2. **Status-line per diary.** Every diary has a `**Status:**` line at
   the top. `grep -nE "^\*\*Status:\*\*"` gives a one-screen backlog
   view in ~1 second. Cheap index. Worth keeping.

3. **Worked examples in skill docs.** Every skill doc points at a
   specific recent example (Rocky Helmet for add-item, Drought for
   add-ability, etc.). Future contributors copy the example; the
   abstract recipe is the scaffolding.

4. **Forcing-function catalog (067).** The "we could do this when X
   happens" format kept speculative work out of the shipped path
   while preserving the design thinking. Three 067 rows shipped
   organically as their forcing functions arrived (aliases, server,
   partial Dex split).

5. **Test-first for registry coverage.** `RegistryCoverageTest`
   (c6152c1) turned "forgot to register" from a silent-wrong-result
   bug into a loud build failure. Similar static-invariant tests
   (`EngineImmutabilityInvariantTest`, 25dd9f6) caught two classes of
   mistake that no dynamic test would.

## Specific patterns to adjust

1. **Pick hook signatures with "next extension" in mind.** Both
   extension-1a (new hook) and extension-5a(ii) (extend existing
   hook) happened in this wave. The 5a(ii) case hit detekt's
   `LongParameterList` because the hook grew past 5 params. Adding a
   `context: HookContext` bundle up front would have saved that churn
   — but we don't yet have enough data to know *which* fields belong
   in the bundle. File this as "watch for when 3+ hooks have 5+
   params each."

2. **Keep the skill-doc hook inventory pointing at source, not
   enumerated.** Enumerating drifts. We fixed `add-ability.md` after
   friction #1; doing it proactively on the others would save one
   friction per future wave.

3. **When running parallel agents, use relative git commands and
   never absolute paths to the main repo inside agent prompts.** The
   one operational issue this session would've been avoided by that
   discipline.

## Forward picture (not plan — orientation)

The project is at a strong checkpoint. `:engine` is data-free; the
registry-DI pattern works for items and abilities and has a plan for
moves (072); the wire protocol is documented and exercised; skill docs
are written and dogfooded. Modules are small enough to load an
entire one at once and fit in context.

Natural next-work candidates, roughly ordered:

- **Minor:** audit remaining `*.md` files for drift. Architecture.md
  was refreshed mid-wave; CONTRIBUTING.md was reshaped. Others might
  be stale.
- **Medium:** when a third registered item or ability needs extra
  hook context, consider a `HookContext` bundle (see adjustment #1
  above).
- **Medium:** when a second item or ability needs `onSwitchOut`, lift
  the switch-out dispatch into a shared resolver (per diary 075's
  architectural note).
- **Large, waiting on forcing function:** team validator (067), KMP
  refactor for non-JVM consumers (073), move registry (072).

Nothing is urgent. The system is productive in its current shape.

## Related diaries

- **Diaries 042 / 043 / 047** — the parallel-work principles this
  session applied (and validated).
- **Diaries 065–076** — the wave this retro covers.
- **Diary 067** — the forcing-function catalog that keeps coming up.

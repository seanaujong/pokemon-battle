# Diary 056: Interactive CLI v1 (pragmatic path)

**Date:** 2026-04-14
**Status:** Plan — ready to implement

## Goal

Play the battle engine from a terminal. Human on one side, existing
`TypeAI` on the other. No engine architecture changes. Use today's
`ChoiceProvider` / `FaintReplacementProvider` as-is.

## Why this, not diary 055

Diary 055 commits to a state-owned prompts architecture (pausable
state machine, mid-turn input). That work is real but deferred — first
we should **play the game** and discover what actually feels wrong
before writing 300+ lines of pipeline refactor. Diary 055's value
hinges on "U-turn's pre-select UX is intolerable" being true; today
it's conjecture.

This diary is the cheap valuable thing. One file of input-handling
code, one entry point. If it feels good enough, 055's work is
lower-priority; if specific moments feel wrong, 055 comes back with
concrete drivers instead of aesthetic ones.

## Scope

**In:**
- **New `:cli` module** — mirrors the `:data-ingestion` pattern from
  diary 041. Depends on `:engine`; engine has no reverse dependency.
  This is what "keep the boundary clean" actually looks like, and
  creating the module now (instead of retrofitting later) is cheap:
  one `build.gradle.kts`, one `include(":cli")` line.
- `HumanChoiceProvider` (in `:cli`) — reads stdin, presents a menu of
  moves + bench switches, returns a `TurnChoice`.
- For `UseMove` with a self-switch effect (U-turn, Volt Switch), the
  provider pre-asks for the switch target — the awkwardness driver
  we're testing.
- `HumanReplacementProvider` (in `:cli`) — post-KO bench picker.
- `PlayMain` entry point (in `:cli`). Existing engine `Main.kt`
  (AI-vs-AI demo) stays untouched for now; a later diary can move
  it out of engine if we want.
- A small play-loop that renders events between turns instead of at
  the end. Mirrors `BattleLoop.run`'s structure inline rather than
  modifying `BattleLoop`.
- Initial setup: player picks side, fixed teams (same as current demo),
  fixed roll=100 for deterministic output. Good enough for v1.

**Out:**
- New engine capabilities (no changes to `BattleState`, `TurnPipeline`,
  or phases).
- Team-building / party-selection UX.
- Save/load, undo.
- Anything that requires 055.
- Color / ANSI prettification. Plain text.
- Moving the existing AI-vs-AI `Main.kt` out of engine. That cleanup
  can happen later; not part of this diary.

## Plan

1. **Scaffold `:cli` module.** Mirror `:data-ingestion/build.gradle.kts`
   (kotlin jvm, application, ktlint, detekt, jacoco). Depends on
   `project(":engine")`. Add `include(":cli")` to `settings.gradle.kts`.
   Confirm `./gradlew :cli:compileKotlin` succeeds (empty).

2. **`HumanChoiceProvider`** in `cli/src/main/kotlin/com/pokemon/battle/cli/`.
   - Constructor takes the side it represents.
   - `getChoices(state)` prints active Pokemon status, menu of moves
     (numbered 1-N), and bench options (numbered N+1-...), reads a
     line, returns the `TurnChoice`.
   - For moves whose `effects` contain `SelfSwitch`, pre-ask for the
     switch target with a second prompt. Record that awkwardness in
     play observations.
   - `getReplacement(state, slot)` prints bench and reads index.

3. **`PlayMain`** in `:cli` — orchestrates one battle.
   - Fixed teams (start with the current engine `Main.kt`'s Charizard/
     Garchomp/Lucario vs Venusaur/Blastoise/Togekiss).
   - Human on side 1, `TypeAI` on side 2.
   - Loop: render current state, get choices, resolve turn, render
     events, handle replacements, render again, check win.
   - `application` plugin with `mainClass = "...PlayMainKt"`; invoked
     via `./gradlew :cli:run`.

4. **Play 3-5 games** and capture impressions in this diary:
   - Does the pre-select U-turn actually feel wrong, or just slightly
     weird? Be honest.
   - What else is awkward? (Likely: move menu at every turn when
     there are no switches; too much text between turns; etc.)
   - What would actually be fun to add next? (Animation-like pacing?
     Stat inspection? Pokemon info?)

## Green signals

- `./gradlew :engine:play` launches a game.
- A full battle finishes with a visible winner.
- `./gradlew test ktlintCheck detekt` still green.

## What this unblocks

- **Diary 055 with a real driver.** If pre-select U-turn feels wrong
  after 5 games, 055 gets concrete evidence and goes from "Option A
  sounds right" to "these specific moments demand it."
- **Smaller follow-ups.** Every bit of friction becomes a potential
  diary (better state rendering, damage previews, party view, etc.).

## What to watch for

- **Scope creep.** Every play session will surface "wouldn't it be
  nice if..." — resist. v1 is playable, not polished.
- **Discovering engine bugs.** A human playing randomly is a fuzz test;
  if something crashes or computes wrong, that's a real engine issue,
  not a CLI issue. Flag and fix outside this diary.

## Design note: humans and agents as incidental co-users

Three distinct input use cases, even though only (1) is in scope here:

1. **Human plays via CLI** — stdin/stdout, optimized for reading and typing.
2. **AI plays** — direct in-process `ChoiceProvider` implementation. Already
   works today (`TypeAI`); no CLI involvement.
3. **Agent plays via CLI** — wraps (1); drives stdin, parses stdout.

The tempting design mistake is to *explicitly* engineer the CLI to serve both
humans and agents — which typically compromises human ergonomics to earn
agent-friendliness that a direct-API path would serve better anyway. The
better framing: **a CLI that is maximally simple and predictable for humans
is incidentally trivial for agents to drive.** Numbered menus, plain prompts,
one decision per line, no flourishes — humans parse these fastest, and agents
parse them trivially.

If serious agent-evaluation (self-play, regression, training) ever becomes a
first-class need, the right answer is a separate `AgentHarness` that
implements `ChoiceProvider` directly (reading scripts, receiving decisions
over a socket, etc.) — not a dual-purpose CLI. That's a future diary,
tracked here only so the thought isn't lost.

**Principle to carry forward:** designs that optimize for one clear audience
often serve adjacent audiences for free. The cost of trying to serve two
audiences *explicitly* is almost always worse than either single-audience
design.

## Related

- **Diary 055** — the architectural alternative; this diary's output
  informs whether 055 is worth doing.
- **Diary 054** — the original reframing that led here.

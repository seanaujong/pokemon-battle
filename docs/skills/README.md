# Skills

Task-level use cases — one per discrete contributor skill the engine
supports. Each follows a Cockburn-style structure (scope, actor,
preconditions, main success scenario, extensions) so it can be
exercised as a small, named activity rather than reverse-engineered.

Use this directory when:

- You're about to add a new item / ability / move / event and want the
  current recipe.
- You're evaluating how the engine *feels* from a contributor's
  perspective — exercise one of these end to end and note what was
  awkward. Friction points are the signal for the next diary.
- You're onboarding and want to understand what shapes of work this
  codebase is optimized for.

## Current skills

- **[Add an item](add-item.md)** — new held-item behavior. Worked
  example: Rocky Helmet.
- **[Add an ability](add-ability.md)** — new ability behavior.
- **[Add a move](add-move.md)** — new move; pure data when possible.
- **[Add a battle event](add-event.md)** — new observable moment in
  a turn.

## What's not here (yet)

The following are shaped like skills but don't have documented
workflows. Add one when pressure demands:

- **Add a new species** — today it's done via `:data-ingestion` (fetch
  from PokeAPI, commit the projected JSON). Skill doc would belong
  under `:data-ingestion`'s own README if that module ever gets one.
- **Add a gen variant** — shaped by diary 067's `data/mods/` row; not
  yet triggered.
- **Add a new consumer of the event stream** — a new peer module
  (analytics, alternate renderer, web UI). Diary 069 / 070 sketch the
  architecture; a skill doc would land when the second such consumer
  appears.
- **Add a new choice type** — e.g., Mega Evolution, Z-moves, Tera. Would
  touch `TurnChoice`, the wire protocol, phases. Significant enough that
  each would be its own diary.

## Background

- **`CONTRIBUTING.md`** (repo root) — workflow, testing conventions, build
  commands, commit style. The *how we work*; skills are the *what to do*.
- **`CLAUDE.md`** — design principles and the iteration loop.
- **`docs/architecture.md`** — the *why*.
- **`docs/diaries/`** — the history. Diaries are referenced from specific
  skill sections where the background matters.

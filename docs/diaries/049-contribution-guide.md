# Diary 049: Contribution Guide

**Date:** 2026-04-13
**Status:** Complete

## Goal

Write an explicit contribution guide so new contributors — human or AI — don't have to
reverse-engineer repo conventions on every touch. The trigger is diary 047's observation
that three parallel subagents each re-derived the same conventions (registry pattern,
diary-driven loop, pre-commit discipline, naming). That rediscovery is pure tax, and a
short guide pays for itself on the next parallel spawn.

## What I wrote

- **`CONTRIBUTING.md`** at the repo root (~200 lines). Sections:
  1. Project goals and scope (with invariants)
  2. The diary-driven iteration loop (short form; points to `CLAUDE.md` for the full)
  3. How to add an item — enum, `ItemEffect` file, `ItemRegistry` entry, optional
     `ItemText`
  4. How to add an ability — mirror of items
  5. How to add a move — `MoveDex` data + optional `MoveEffect` variant + branch in
     `resolveEffect`
  6. How to add an event — sealed subclass + phase emission + `TextRenderer` branch
  7. Testing conventions — mainline/extensibility sections, `roll = { 100 }`,
     pokedex vs custom species, "don't calculate, let the code tell you"
  8. Build/lint/commit/merge — gradle commands, pre-commit hook, one-commit-per-feature,
     no `Co-Authored-By`, fast-forward-only
  9. When in doubt + landmark diaries

- **CLAUDE.md** gained a short "Agent / contributor onboarding" pointer near the top
  that forwards to `CONTRIBUTING.md` for how-to detail. Kept it short to avoid
  duplication.

## What I drew from

- **Diary 026** — item registry pattern; source for the "adding an item" recipe.
- **Diary 027** — ability registry pattern; mirrored in the "adding an ability" recipe.
- **Diary 038** — rendering/behavior separation; explains the optional `ItemText` /
  `AbilityText` step.
- **Diary 043** — parallel-work chokepoints; grounds the merge-discipline section and
  the warnings in the "adding a move" / "adding an event" sections.
- **Diary 047** — parallel stress test; the motivating observation for this guide.
- **CLAUDE.md** — iteration loop and testing principles.
- **`.githooks/pre-commit`** and `.git/config` — build/commit/merge details.
- **Source itself** — sanity-checked file/package layout (`engine/item/`,
  `engine/ability/`, `render/item/`, `render/ability/`) and registry shapes before
  describing them.

## Audience

Two readers in mind simultaneously:

- **New human contributor** landing on `main` for the first time — "what do I edit to
  add X."
- **AI agent** spawned into a worktree with no prior context — same question, but also
  needs the commit/merge conventions made explicit rather than inferred from `git log`.

The guide is written assuming neither has read the diaries yet, but points into them
for depth.

## Scope discipline

Documentation only. No production code, no `build.gradle.kts`, no registries, no event
classes, no test files touched. A sanity pass of
`./gradlew test ktlintCheck detekt` is still worth running to confirm nothing about the
docs change (e.g. a stray file at the root) breaks anything.

## What this unlocks

- Future parallel-agent spawns start from a shared baseline instead of re-deriving
  conventions.
- The CLAUDE.md / CONTRIBUTING.md split gives us two clean slots: workflow & rationale
  vs concrete how-to. Either can evolve without bloating the other.
- If we ever do the chokepoint escalations from diary 043 (per-event-type render
  registry, sealed-interface enums), the "How to add an event" / "How to add an item"
  sections become the natural place to document the new shape.

## Related

- **Diary 047** — the observation that motivated the guide.
- **Diary 043** — the chokepoint analysis referenced from the merge-discipline section.
- **Diary 026 / 027 / 038** — the patterns the how-to sections codify.

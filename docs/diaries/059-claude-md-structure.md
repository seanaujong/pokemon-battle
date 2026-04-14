# Diary 059: Ideal CLAUDE.md structure

**Date:** 2026-04-14
**Status:** Complete (2026-04-14). `CLAUDE.md` now groups Preflight, Iteration Loop, and Parallel variant under a single `## Workflow` heading.

## The observation

After adding the Preflight section in `b48583b`, CLAUDE.md has three
top-level headings that describe **the same workflow from different
angles**:

- `## Preflight` — entry decisions
- `## Iteration Loop` — the cycle (Code Review is *step 5*)
- `## Parallel Work (multi-agent orchestration)` — the iteration loop,
  sharded across subagents

A reader sees three sibling sections and has to infer that Parallel Work
is a *variant* of the loop, not a rival workflow, and that Code Review is
not its own gate. The structure hides the flow instead of surfacing it.

## What the ideal looks like

Group the three workflow sections under one top-level heading. Keep the
orientation sections (overview, architecture, principles) and the
reference sections (tooling, testing) flat. Result:

```
# CLAUDE.md

## Agent / contributor onboarding       (unchanged)
## Project Overview                     (unchanged)
## Architecture                         (unchanged)
## Design Documents                     (unchanged)
## Design Principles                    (unchanged)

## Workflow                             (new grouping heading)
  (one-line framing: preflight → loop → back to preflight for next task;
   parallel variant is the loop sharded across worktrees)

  ### Preflight (before starting)
    (the 3 items from the current ## Preflight section, unchanged)

  ### Iteration Loop
    (the 9 numbered steps, Code Review stays as step 5, unchanged)

  ### Parallel variant
    (one-sentence framing: "When Preflight #1 says shard, this is the
     iteration loop across worktrees.")
    (existing Parallel Work content, unchanged: conflict analysis,
     launch pattern, merge strategy, post-run retro, when not to)

## Tooling Principles                   (unchanged)
## Testing Principles                   (unchanged)
```

The ideal makes three things visible at a glance:

1. **Code Review is not a separate section.** It lives at step 5 of the
   iteration loop. The current flat structure made it look free-floating
   when a reader searched the TOC.
2. **Parallel work is a variant.** Not a competing methodology, not a
   separate track — same loop, sharded. The `### Parallel variant`
   subsection title encodes this at the heading level.
3. **The workflow is a loop.** Preflight → Iteration Loop → preflight.
   The one-line framing under `## Workflow` tells the reader that
   explicitly.

## What does NOT change

- Every line of content is preserved. This is a structural reorganization,
  not a rewrite.
- Section numbering of the 9-step iteration loop stays. Step 5 stays as
  the Code Review checklist.
- The orientation sections (Overview, Architecture, Principles) don't get
  consolidated — they're different in kind (*what this is*) from the
  workflow sections (*how we work*). Leave them flat.
- Tooling and Testing Principles stay flat for the same reason — they're
  reference lists, not workflow.

## What this buys

- New contributors (human or agent) see the workflow as one thing, not
  three scattered sections.
- The answer to "is code review a checkbox before commit?" is visibly
  "no, it's part of the loop."
- The answer to "do I follow parallel work OR iteration loop?" is visibly
  "same loop, different execution shape."
- Future additions (e.g., if we add a "Debugging flow" for deep bug
  hunts) slot naturally as siblings of `### Iteration Loop` under
  `## Workflow`.

## What this doesn't buy (honest)

- Nothing that solves a *current* problem. This is readability,
  not correctness. The current doc works; this just reads better.
- The consolidation doesn't make any individual step shorter or
  clearer. It reorganizes, nothing more.

## Migration

One edit to CLAUDE.md:

1. Replace the three top-level headings (`## Preflight`, `## Iteration
   Loop`, `## Parallel Work`) with one `## Workflow` heading + three
   `###` subheadings containing the same body text.
2. Add a 1–2 sentence framing paragraph right after `## Workflow` that
   names the flow.
3. Add the one-sentence "variant of the loop" framing under `### Parallel
   variant`.

Verify: every line of content from the original sections appears in the
new version. A `diff --word-diff` of old vs new should show only:
- Changed heading levels
- Added framing paragraphs
- Nothing else

## Why a diary for a doc restructure?

Because "does the doc read like a workflow?" is a judgment call, not a
mechanical change. The diary records *why* we grouped this way so a
future contributor doesn't re-debate it. Also catches the failure mode
where restructuring turns into rewriting — the "What does NOT change"
section is the anchor.

## Related

- **Diary 056** — where the preflight + iteration loop coupling first
  surfaced (mid-session feedback: "should we have a preflight checklist?")
- **Diary 058** — the naming/structure review that this extends. 058
  looked at *names*; 059 looks at *organization*.
- CLAUDE.md commit `b48583b` — added the Preflight; this diary
  consolidates.

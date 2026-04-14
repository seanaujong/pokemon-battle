# Diary 063: Doc overlap audit — architecture.md, CONTRIBUTING.md, CLAUDE.md

**Date:** 2026-04-14
**Status:** Plan + immediate action

## The question

Is there overlap between `docs/architecture.md` and `CONTRIBUTING.md` worth
consolidating? Could we delete `architecture.md` after merging its content
into `CONTRIBUTING.md`?

## Findings

### 1. Three docs, three audiences, two real overlaps

| Doc | Audience | Function | Convention |
|-----|----------|----------|------------|
| `CLAUDE.md` | AI agents picking up work | Workflow + design principles | Anthropic-specific |
| `CONTRIBUTING.md` | New contributors (human + AI) | How-tos: add an item, ability, move, event | GitHub convention |
| `docs/architecture.md` | Anyone learning the system | What it is, why it was built that way | Per-project |

The functions are genuinely distinct. **Merge-and-delete is wrong** —
`CONTRIBUTING.md` is a tooling convention (GitHub PR flows surface it),
and `architecture.md` doubles as design rationale + history.

### 2. Real overlaps, all at the "key invariants" level

These statements appear in **3+ files**:

- "All data classes use `val` only / immutability"
  - architecture.md (Design Rationale, BattleState, Lessons Learned)
  - CONTRIBUTING.md (Key invariants)
  - CLAUDE.md (Design Principles)
- "Phases are pure functions"
  - architecture.md (Design Rationale, Layers, Phase definition)
  - CONTRIBUTING.md (Key invariants)
  - CLAUDE.md (Design Principles)
- "Events are sole means of mutation"
  - architecture.md (Overview, Design Rationale, Lessons Learned, BattleState)
  - CONTRIBUTING.md (Key invariants)
  - CLAUDE.md (Design Principles)
- "Sealed hierarchies enable exhaustive when"
  - architecture.md (Design Rationale)
  - CONTRIBUTING.md (Key invariants)
  - CLAUDE.md (Design Principles)
- "The engine doesn't enforce legality / has zero I/O"
  - architecture.md (Design Rationale, Module-placement rule, Lessons Learned)
  - CONTRIBUTING.md (Key invariants)
  - (Not in CLAUDE.md.)

These are restated **5–6 times across the docs** in slightly different
words. Classic drift surface.

### 3. The much bigger problem: architecture.md has *already* drifted

While auditing, I found the file is silently out of date:

| Drift | What architecture.md says | Reality |
|-------|---------------------------|---------|
| `BattleEvent` shape | "Each subclass has `apply(state): BattleState`" (line 178-179) | Diary 061: `BattleEvent` is a marker; `GameEvent` has `apply(BattleState)`, `ControlEvent` has `apply(PipelineState)`. |
| `Phase` signature | `fun resolve(state: BattleState, choices: TurnChoices): List<BattleEvent>` (line 209) | Now returns `PhaseOutput` (Completed/Paused) and takes `PipelineState`. |
| `TurnPipeline.resolve` return | `Result` (line 213) | `TurnResolution` (sealed, Completed/NeedInput). |
| `BattleState` fields | Shows only `slots, bench, field, turn` (line 102-108) | Missing `sideConditions, sideHazards, gimmicksUsedBySide, ruleset`. |
| `BattleEvent.kt` event list | "MoveOrderDecided, MoveAttempted, MoveFailed, DamageDealt, PokemonFainted" + 6 file table (line 183-190) | Many more events; new files (TurnInputEvents, GimmickUsed, HazardEvents, SideConditionEvents). |
| `BattleLoop` ctor | Three params, no `inputResponder` (line 234-239) | Diary 055 Phase 2 added `inputResponder: InputResponder?`. |
| `MoveEffect` example | Only `StatBoost` shown (line 167-169) | Multiple variants today (SelfSwitch, SetVolatile, SetTrickRoom, etc.). |
| Pokedex source | "loads species from `data/species.csv`" (line 251) | JSON loader added (diary 041); CSV is legacy. |
| Out-of-scope list | "Entry hazards", "CLI / REPL" listed as not covered (line 446-451) | Both shipped (diaries 044, 056). |
| Future scenarios | "U-turn / Volt Switch" listed as future (line 437) | Shipped (diary 055). |

**The file behaves like a snapshot from ~April 12.** Anything time-stamped
"as currently implemented" has rotted because we've kept moving but not
back-edited the doc.

## Decision

**Restructure architecture.md so the rotting parts can't rot.** Two moves:

1. **Strip the time-sensitive content.** Type signatures, constructor
   parameter lists, "implemented vs not yet" tables, "currently 14 moves"
   counts — anything that requires editing the doc when code changes.
   Source code + diaries are the authority for the current state.

2. **Keep the timeless content** — the parts that don't drift:
   - The high-level architectural diagram (phases → events → state)
   - **Design Rationale** (why we made each choice; this is history)
   - **Lessons Learned** (principles discovered, not obvious at the start)
   - **Custom Format Compatibility** (qualitative, decay-resistant)
   - **Multi-Gen Support** at the conceptual level (registry pattern; what NOT to do)

3. **Where the doc references current implementation, replace with pointers**:
   - "See source for current event list" → `engine/src/.../engine/`
   - "Current pipeline phases" → `engine/src/.../phase/`
   - "Currently registered items" → `ItemRegistry`

4. **Move the duplicated key-invariants** to *one* place. `CLAUDE.md`'s
   Design Principles is the natural home (it's the workflow doc all
   contributors read). `CONTRIBUTING.md`'s "Key invariants" stays as a
   one-line reminder pointing into CLAUDE.md. `architecture.md`'s overlap
   with these can be trimmed since the principles are stated elsewhere.

### What we do NOT do

- Don't merge architecture.md into CONTRIBUTING.md. They serve
  different audiences; readability suffers if one file tries to be both
  "what is this codebase" and "how do I add an item."
- Don't delete architecture.md. The Design Rationale and Lessons Learned
  sections are genuinely irreplaceable history.
- Don't try to keep type-signature documentation in sync going forward —
  that's a losing battle. Code is the source of truth for shapes.

## Plan

1. Trim architecture.md (this commit).
2. Add a one-line note at the top of architecture.md saying "code is
   authoritative for current shapes; this doc captures rationale."
3. Verify CONTRIBUTING.md cross-references still work.
4. Mark this diary Complete.

## Why we're fixing instead of deferring

The drift is *already* happening — diary 061's split silently
contradicted architecture.md. Every future refactor adds more drift
unless we either commit to back-editing or strip the rot-prone parts.
Stripping is dramatically cheaper.

## Related

- **Diary 049** — added CONTRIBUTING.md. Established the "how-to"
  layer.
- **Diaries 055, 061** — refactors that silently aged architecture.md.
- **Diary 058** — naming review. Same impulse: audit before drift bites.

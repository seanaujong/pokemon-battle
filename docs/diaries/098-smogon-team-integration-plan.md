# Diary 098: Smogon team integration — Gen 5 OU matrix plan

**Date:** 2026-04-14
**Status:** Planning (execution in flight under 099).

## Why this diary exists

Diary 094 closed the Tera-in-HeuristicAI loop and flagged that our
hardcoded 6-mon matrix was saturated at 100-0 on most cells — the
teams weren't competitive enough to differentiate agents. The push
direction: replace the hardcoded pool with Smogon-informed Gen 5 OU
sets so every prior matrix measurement becomes something players
would actually argue about.

Scope picked (with user consent): **(B) — full Gen 5 OU matrix**.
The user's framing: "swing big and parallel."

## Target team pool

Six Gen 5 OU top-10 species, split into two teams of three:

- **Side A:** Ferrothorn, Starmie, Latios
- **Side B:** Tyranitar, Garchomp, Scizor

Picked for archetype diversity (defensive pivot, special spinner,
wallbreaker vs sand enabler, physical sweeper, priority pivot) and
because all six have species JSON in our Pokedex.

## Movesets (from Smogon top-sets)

| Pokemon | Ability | Item | Moves |
|---|---|---|---|
| Ferrothorn | Iron Barbs | Leftovers | Power Whip, Spikes, Stealth Rock, Protect |
| Starmie | Natural Cure | Life Orb | Hydro Pump, Ice Beam, Thunderbolt, Rapid Spin |
| Latios | Levitate | Choice Specs | Draco Meteor, Surf, Dragon Pulse, Psychic *(sub for Psyshock)* |
| Tyranitar | Sand Stream | Choice Scarf | Crunch, Stone Edge, Earthquake, Ice Beam |
| Garchomp | Rough Skin | Focus Sash | Earthquake, Dragon Claw, Swords Dance, Stealth Rock |
| Scizor | Technician | Choice Band | Bullet Punch, X-Scissor, Iron Head, U-turn |

Substitutions from canonical sets (avoid multi-session mechanics):
- **Latios's Psyshock** → Psychic (skip the "uses Def as defender" wrinkle).
- **Tyranitar's Superpower** → Earthquake (skip self Atk/Def debuff).
- **Garchomp's Outrage** → Dragon Claw (skip 2–3 turn lock + self-confuse).
- **Scizor's Superpower** → Iron Head (skip self debuff).
- **Ferrothorn's Leech Seed** → Protect (skip drain-volatile mechanic).

These are the deferred moves — each is its own diary-lift. Noted in
diary 099 for later expansion.

## Coverage delta to ship

**Items:** zero new. Leftovers, Life Orb, Choice Specs, Choice Scarf,
Choice Band, Focus Sash all implemented.

**Abilities (4 new):** Iron Barbs, Sand Stream, Rough Skin, Technician.

**Moves (11 new, all pure-damage or boost-style):**
Power Whip, Hydro Pump, Surf, Dragon Pulse, Draco Meteor (skip self
debuff), Psychic (with 10% SpD drop chance), Crunch (skip 20% Def
drop), Stone Edge (skip high-crit), Dragon Claw, Bullet Punch,
X-Scissor, Iron Head (skip flinch). Secondary effects marked
"skip" are explicitly deferred — tracked in 099's "findings to fix."

## Execution shard

Parallel worktrees:

- **Agent M (moves):** all 11 moves added to `MoveDex.kt`. No
  new `MoveEffect` subclasses needed — every move picked is
  either pure damage or reuses existing effect patterns. One shared
  file, one agent.
- **Agent A (abilities):** 4 new ability files in
  `data/src/main/kotlin/com/pokemon/battle/data/ability/` plus
  registration in `GenVRegistries`. Iron Barbs and Rough Skin share
  the "reflect contact damage" shape (copy from RockyHelmetEffect
  but on an ability); Sand Stream mirrors DrizzleEffect / DroughtEffect;
  Technician is a flat damage modifier for BP ≤ 60.

**Conflict analysis:** Agent M touches only `MoveDex.kt`. Agent A
touches new files + `Registries.kt` (the GenVRegistries abilities
list). Zero file overlap. Merge risk: nil.

**Sequential tail (me):**
1. Merge both worktrees.
2. Build `SmogonTeamBuilder` in `:data-ingestion` — reads a
   top-sets JSON and materializes `Pokemon` + `PokemonState` using
   our engine types. Falls back or errors on unsupported moves/
   abilities/items.
3. Wire `MatrixEvalMain` to accept an `ou` third arg (or similar)
   that uses the Smogon pool instead of the hardcoded pool.
4. Run matrix, diff win rates vs hardcoded pool.
5. Diary 099 with code review + industry comparison.

## What "end-to-end" means for this push

When diary 099 ships:
- `./gradlew :cli:matrixEval --args="20 genv smogon"` runs the 3×3
  AI matrix with Gen 5 OU Smogon-top-sets teams.
- Matrix outcomes differ meaningfully from the hardcoded-pool run.
- Each decision point in the team (move / ability / item) is
  sourced from `data/smogon/gen5ou-1760-top-sets.json` and is
  auditable.
- The skip-list (Leech Seed / Trick / Outrage / Roost / Pursuit /
  Superpower / Psyshock precise variant) is explicit and each
  deferred item is listed as a future diary candidate.

## Validation signal

- `./gradlew test ktlintCheck detekt` green.
- Matrix run with Smogon pool produces visibly different outcomes
  than the hardcoded pool on identical seeds.
- The skip list + substitutions are documented in 099 so a future
  diary can restore canonical sets.

## Related

- **Diary 094** — HeuristicAI Tera saturation. Motivates this
  replacement.
- **Diary 078** — matrix runner (the consumer).
- **Diary 072** — move behaviour registry. The move expansions
  we're adding all fit the existing registry shape.
- **Diary 041** — Pokeapi + Smogon stats. The ingestion work that
  made this diary possible. (`data/smogon/*.json` came from there.)

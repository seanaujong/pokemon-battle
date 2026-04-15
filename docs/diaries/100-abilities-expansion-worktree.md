# Diary 100: Gen 5 OU abilities expansion (worktree A)

**Status:** Complete.

## Goal

Add the 4 Gen 5 OU abilities the Smogon-pool matrix runner needs:
**Iron Barbs**, **Rough Skin**, **Sand Stream**, **Technician**. This
is the abilities shard of the paired worktree run — a sibling
worktree (diary 099) owned MoveDex.

The agent process for this shard died mid-write when the user's
machine restarted; the worktree's filesystem state survived. I
inspected the in-progress files, fixed one test bug (Blissey wasn't
in the legacy CSV the test loaded), then committed and merged. Diary
written here as the "what shipped" summary.

## What shipped

- **`data/src/main/kotlin/com/pokemon/battle/data/ability/ContactRecoilEffects.kt`**
  — `IronBarbsEffect` and `RoughSkinEffect` are both
  `AbilityEffect by ContactRecoil(Ability.X)` over a private
  `ContactRecoil` class. Mainline mechanic: 1/8 attacker max-HP
  recoil on contact. Fires only on contact (`resolveIsContact`
  honored), zero-damage hits skipped, attacker faint on lethal recoil
  emitted alongside.
- **`SandStreamEffect.kt`** — fires on switch-in, emits
  `WeatherSet(SANDSTORM, 5)`. Mirrors `DrizzleEffect`/`DroughtEffect`.
- **`TechnicianEffect.kt`** — `attackerDamageModifier` returns 1.5×
  when `move.power in 1..60`, else 1.0×.
- **`AbilityEffect.onHolderTookDamage`** hook + **`AbilityDamage`
  event** — new engine surface mirroring the existing
  `ItemEffect.onHolderTookDamage` / `ItemDamage` shape. Wired into
  `MoveExecutionPhase.applyDefenderItemHooks` (function name unchanged
  but now applies both item and ability post-damage hooks).
- **`Ability.kt`** enum gains `IRON_BARBS`, `ROUGH_SKIN`,
  `SAND_STREAM`, `TECHNICIAN`.
- **Registry placements:**
  - `GenVRegistries`: all 4 added.
  - `GenIVRegistries`: Rough Skin, Sand Stream, Technician (Gen 4+).
  - `GenIIIRegistries`: Rough Skin, Sand Stream (Gen 3+).
- **Serialization + render** — `AbilityDamageJson` round-trips,
  `TextRenderer.renderAbilityDamage` emits "X was hurt by iron barbs!"
  style messages.
- **6 tests in `SmogonAbilitiesTest`** cover contact gating, weather
  setting, and the BP-60 Technician threshold.

## Code review

### Diagnostics

- *Testable:* every effect has a unit test driving it through the
  full pipeline. The new `AbilityDamage` event has integration coverage
  via the contact-recoil tests.
- *Readable:* `ContactRecoilEffects.kt` extracting the shared
  `ContactRecoil` body and instantiating it twice via Kotlin
  delegation is a tighter shape than two near-duplicate classes.
- *Layer:* engine ↔ data split honored. New hook in `engine/`, four
  effect impls in `data/`. Render + serialization updated.
- *Auditable:* `AbilityDamage` events land in battle JSON with
  attacker, amount, and ability — fully reconstructible from the log.
- *Happy path:* attacker uses contact move → defender's ability
  resolves → recoil event fires → state updates HP. No phase logic
  beyond the existing on-hit hook.
- *Failure modes:* defender already fainted → hook returns empty;
  attacker already fainted (e.g. recoil from prior hit) → empty;
  non-contact → empty; zero-damage → empty. All four are silent.
- *Duplicated logic:* `IronBarbsEffect` and `RoughSkinEffect` share
  a single `ContactRecoil` private class via Kotlin delegation. No
  duplication.
- *Removal:* delete the four ability files + the new hook + event
  + serialization + render branches + enum entries + registry
  registrations. ~10 edits across 7 files.

### Industry comparison

Adding a new on-hit ability hook is small. The shape (interface
default `= emptyList()` overridden by concrete effects) matches every
other ability effect in our codebase. No new architecture, no new
seam, just a new hook on an existing seam. Industry analog:
Showdown's `onSourceDamagingHit` callback chain — same shape, same
purpose.

### Findings to fix

None.

## Test bug fixed during merge

The agent's Sand Stream test used `pokedex["Blissey"]!!` via
`Pokedex.loadFromClasspath()` — but that loader reads the legacy
21-mon CSV which doesn't include Blissey. (The 101-mon JSON loader
does.) I swapped Blissey for Blastoise (which is in both) and the
test passed. Logged as a CLAUDE.md addition candidate: subagent
prompts that touch tests should explicitly flag which Pokedex
loader is appropriate.

## Validation

`./gradlew test ktlintCheck detekt` green on the worktree before
merge; green again after rebase on main.

## Related

- **Diary 099** — the moves shard of the same worktree run.
- **Diary 101** — the integration that consumes both shards.
- **Diary 088** — `resolveIsContact` (which Iron Barbs / Rough Skin
  consult).
- **Diary 074** — Weakness Policy on the item side; precedent for
  the on-hit-hook pattern that the ability side now mirrors.

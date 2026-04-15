# Diary 095: Mega Evolution — the other real gimmick (planning)

**Date:** 2026-04-14
**Status:** Planning. Not started.

## Why this diary exists

Diary 092 shipped Tera as the first real gimmick. Tera was cheap
because it only needed a type override + a STAB rule tweak —
mechanics our `typeOverride` seam and damage calc already supported.

Mega Evolution is the *honest* next gimmick. It's not cheap, and
that's the point. Mega forces a new axis in the data model that
Tera elegantly avoided: **the same Pokemon can exist in multiple
forms, each with different stats, ability, and sometimes typing.**
Charizard has two Megas (Mega X is Fire/Dragon with Tough Claws;
Mega Y is Fire/Flying with Drought); they share a base species but
differ on almost every dimension the engine cares about.

If we can land Mega cleanly, the "new data-model dimension" claim
holds. If we can't, we learn which architectural commitment needs
revisiting.

## Why this is parked (not started)

Scope. Tera was ~60 engine lines because seams existed. Mega needs:

1. **Form concept on `Pokemon` / `Species`.** Either `Species.forms:
   List<Form>` with a `Form` dataclass carrying per-form stats /
   ability / typing, or a separate `MegaSpecies` indirection. Open
   question for the planning phase.
2. **Mid-battle transformation event** (`MegaEvolved`) that swaps
   the active Pokemon's effective species/stats/ability/type for the
   rest of the battle.
3. **Held-item gating** — Mega Evolution requires a specific Mega
   Stone (Charizardite X vs Charizardite Y for the two Megas). The
   `Item` enum + `ItemEffect` registry need Mega Stone entries with
   a "this stone enables this Mega form" relationship.
4. **Mega ability swap** — some Megas change ability to one that
   didn't exist in Gen III's base ability registry. Tera didn't
   touch the ability registry; Mega does.
5. **Mega data** — at least Charizard-X / Y, Lucario, Garchomp,
   Venusaur, Blastoise (the six mons in our matrix pool) to have
   enough coverage for a matrix comparison. Probably
   data-ingestion-sourced rather than hand-typed.
6. **Ruleset gating** — `NationalDexRuleset` allows Mega + Z-Move
   per side per battle; `PokemonChampionsRuleset` allows one gimmick
   of any kind. Both exist; both need to grant Mega without
   granting Tera. This probably works today (the ruleset shapes are
   already general), but worth verifying.

That's four-to-five diaries worth of work vs Tera's one. Hence
planning-only.

## Rough plan (when picked up)

- **Diary N+1: Species forms data model.** Add `Form` to `Species`
  (or parallel indirection). Refactor existing code to read stats
  through a "current form" lens. No behavioural changes — just
  shape. Could be a CategoryResolver-adjacent refactor.
- **Diary N+2: `MegaEvolved` event + `MegaStoneEffect` item.** Event
  swaps form; item effect holds the form-change metadata and gates
  which form the holder can take.
- **Diary N+3: One Mega end-to-end.** Pick Charizard-Y as the
  minimum viable — Fire/Flying + Drought ability. Wire through
  matrix runner with a `mega` third arg.
- **Diary N+4: Multi-Mega + retrospective.** Add the rest of our
  matrix pool's Megas. Run the matrix. Did a single gimmick budget
  produce more / less signal than Tera did?

## Open design questions (for the planning phase)

- **Form as Species-level or Pokemon-level concept?** Species has
  one canonical form; alternate forms are either `List<Form>` on
  Species or separate Species entries. Showdown treats them as
  separate species (Charizard-Mega-X is its own species). That's
  heavyweight but simple. Our current Pokedex has `Charizard` only;
  adding `Charizard-Mega-X` as a peer species duplicates base stats
  and movesets.
- **Form on `PokemonState` or on `Pokemon`?** Base Pokemon is a
  team-declared preset; active form is battle-state. Analogous to
  `teraType` on Pokemon + `terastallized` on PokemonState — Mega
  probably follows the same split.
- **When does the form revert?** Mainline: never during battle.
  On switch-out + switch-in, Mega stays Mega. So the form-change
  is battle-permanent like Tera. Good — no "revert on switch-out"
  mechanic needed.
- **Tera + Mega interaction?** Mainline Gen 9 doesn't allow Mega
  (no Mega Stones in Gen 9 normal formats). National Dex allows
  both but not on the same mon. Our `NationalDexRuleset` already
  says "one of each kind" — probably sufficient.

## Validation signal (when shipped)

- A battle in which a Pokemon with a Mega Stone emits `MegaEvolved`
  on turn 1, and subsequent damage events reflect the Mega form's
  stats (different atk/spa numbers, different typing under STAB,
  different ability modifiers).
- `NationalDexRuleset` allows Mega + Tera on opposite mons in the
  same battle.
- Matrix run with Mega enabled produces a measurable win-rate delta
  vs Mega-disabled on the same team pool.

## Related

- **Diary 036** — gimmick state stub. `GimmickKind.MEGA` has been
  sitting in the enum for ~60 diaries waiting for a consumer.
- **Diary 092** — Tera. The easy gimmick that paid off existing
  seams. Mega is the hard one that grows new seams.
- **Diary 091** — Tera planning. Similar shape to this diary (a
  placeholder that got picked up one diary later). This placeholder
  may sit longer; Mega is bigger.
- **Diary 094** — HeuristicAI Tera heuristic. A Mega-aware AI
  heuristic will be a separate diary from the Mega mechanic itself
  (same split as 092 / 094).

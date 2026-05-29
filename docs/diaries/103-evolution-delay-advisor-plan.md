# Diary 103: Evolution-delay advisor — ingestion + analysis plan

**Date:** 2026-05-28
**Status:** Complete (spike → ingestion → advisor → CLI → tests all shipped;
green on `./gradlew test ktlintCheck detekt`).

## Why this diary exists

User idea: *deterministically flag when it's worth delaying a Pokémon's
evolution, based on the pre-evolution's level-up learnset across
generations.* The classic motivating case — Kricketot learns Bug Bite
at L16 but evolves into Kricketune at L10, and Kricketune never learns
Bug Bite by level-up — so the only level-up route to Bug Bite is to
decline the L10 evolution and hold Kricketot to L16.

This is a data-ingestion + analysis feature, not an engine change. It
reads dex data (learnsets + evolution chains) and emits advice; it does
not touch the turn-resolution pipeline.

## Spike findings (before committing to schema)

A throwaway spike against PokéAPI (`/pokemon`, `/evolution-chain`,
`/version-group`) over six lines confirmed the signal is clean and
deterministic. Letting the data talk, per the repo's "let the code tell
you" rule — every number below is computed, not recalled:

- **Kricketot→Kricketune** (level-up L10): Bug Bite @L16 on Kricketot,
  Kricketune never learns it by level-up → must-delay.
- **Caterpie→Metapod→Butterfree** (level-up L7): Bug Bite @L9 (gen IV) /
  L15 (gen V+); Metapod never learns it — *but* Butterfree gets it via
  tutor (IV/VI/VII) or relearn@L1 (VIII). So delay is only *required* in
  **gen V**; elsewhere the final form covers it. **This is the key
  finding: per-edge analysis over-flags — correctness needs line-level
  roll-up.**
- **Croagunk→Toxicroak** (level-up L37): Nasty Plot @L38 — delay *one
  level* to grab it; Toxicroak learns it far later. The cheap, high-value
  case.
- **Hoothoot→Noctowl** (level-up L20): ~13 moves where Hoothoot's
  level-up beats Noctowl's, but Noctowl *always* eventually learns them
  (2–6 levels later). Weak delay case — nothing lost forever. Shows the
  advisor must rank by delay cost, not just emit a boolean.
- **Pansage→Simisage** (Leaf Stone, no level gate): 6 moves Simisage can
  *never* learn by any method (Vine Whip, Leech Seed, Bite, Recycle,
  Natural Gift, Crunch). Textbook stone-evo delay. The generic engine
  handled the item trigger with no special-casing.
- **Spheal→Sealeo→Walrein** (level-up L32/L44): Sheer Cold @L46–52 on
  Spheal, learned even later at each later stage.

### What the spike nailed down

1. **Roll up across the whole line, not adjacent edges.** Caterpie
   proves it — "Bug Bite NEVER on Metapod" is misleading because
   Butterfree gets it. The question is: *if I evolve now, does any
   reachable later form still acquire move M without delay?*
2. **Delay cost was the original framing — superseded.** The spike first
   ranked flags by `cost = pre_evo_learn_level − evo_level` (Croagunk +1
   cheap, Hoothoot +25 expensive). User refinement (see Advisor design)
   discards "saves levels" entirely: a move the evolved form *relearns by
   level-up* is never a loss, regardless of cost. `holdToLevel` survives
   only as display info, not a severity axis.
3. **Trigger-agnostic.** Level-up evolutions use the `min_level` gate;
   stone/trade evolutions have no gate, so *every* pre-evo level-up move
   is a candidate. Same comparison code, different gate.
4. **Everything is generation-specific** and PokéAPI carries it at
   version-group granularity. Cross-gen isn't a nice-to-have — the
   interesting flips (Bug Bite L9→L15, Caterpie's delay-required-only-in-
   gen-V) live there.

## Data sources & cache reuse (confirmed against the code)

`PokeApiClient.fetch(endpoint, slug)` is generic and endpoint-keyed,
caching full verbatim responses to `.cache/pokeapi/{endpoint}/{slug}.json`
(gitignored politeness cache) with a 100ms delay + custom UA. Three facts
that shape the design:

1. **Learnsets are already in the cache.** The full `/pokemon` responses
   in `.cache/pokeapi/pokemon/` carry the whole `moves[]` array
   (`alakazam.json` → 111 moves). Any species already pulled for
   stats/types yields its learnset with **zero refetch**.
2. **The committed `data/raw/pokeapi/pokemon/` is a filtered projection**
   — only `[name, stats, types]`; `moves[]` was stripped by
   `PokeApiProjection`. So learnsets aren't committed yet.
3. **New endpoints ride the same client.** `fetch("pokemon-species", x)`
   and `fetch("evolution-chain", id)` reuse the identical cache +
   politeness with no new infra.

Endpoints used:
- `/pokemon/{name}` → `moves[].version_group_details[]` with
  `level_learned_at`, `move_learn_method.name`, `version_group.name`.
  Capture **all** methods (level-up, machine, tutor, egg) — non-level-up
  access is what separates "must delay" from "delay optional."
- `/pokemon-species/{name}` → `evolution_chain.url`.
- `/evolution-chain/{id}` → triggers + `min_level`. A node's
  `evolution_details` describes how it evolves *from its parent*, so the
  trigger lives on the **child** node (Kricketot's own details are `[]`).

**Decision — do not widen the shared species projection.** Adding `moves`
to `PokeApiPokemon` would auto-widen all ~100 committed stat-raws with
movepools the stats pipeline doesn't read. Instead a dedicated learnset
DTO reads `moves` from the same cache, and learnsets are materialized only
for the target lines, bundled into the per-line artifact below.

**Decision — per-line bundle, not per-species files.** The advisor's unit
of analysis is the evolution *line*, so one self-contained artifact per
line keeps understanding colocated and tests trivial (load one file,
compute, assert). Mild divergence from the per-species `pokedex/species/`
convention — justified because edges + every stage's learnset are read
together. Committed under `data/src/main/resources/dex/evolution-lines/`
with an `index.txt` manifest (mirrors the Pokedex loader). The committed
bundle is the transformed source of truth; we skip a `data/raw`
intermediate for these endpoints to avoid committing a second full copy of
every movepool (the `.cache` keeps verbatim raws for regeneration).

`version-group → generation` is a small static table in `data` (stable
reference data; no need to fetch `/version-group`).

## Schema additions (`data` module)

On-disk (`@Serializable`, decoupled from domain, with `toDomain()` — the
`SpeciesJson` pattern):
- `EvolutionLineJson { base, edges[], learnsets{} }`.

Domain (all `val`-only):
- `EvolutionLine { base, edges, learnsets }`.
- `EvolutionEdge { from, to, trigger: EvolutionTrigger, minLevel: Int?, item: String? }`.
- `Learnset` = `Map<VersionGroup, List<MoveAcquisition>>`.
- `MoveAcquisition { move, method: LearnMethod, level: Int }`.
- enums `EvolutionTrigger` (LEVEL_UP, USE_ITEM, TRADE, OTHER) and
  `LearnMethod` (LEVEL_UP, MACHINE, TUTOR, EGG, OTHER).

## Advisor design (`data` module)

`EvolutionDelayAdvisor` — pure function over `EvolutionLine`, zero I/O.
Lives in `data` alongside the dex catalogs and `EvioliteEffect`'s
evolution-aware logic (not `analytics`, which is battle-corpus analysis;
this is dex analysis and would otherwise drag a `:data` dep into
`:analytics`).

```
adviseDelays(line, versionGroup) -> List<DelayFlag>
DelayFlag { move, edgeFrom, edgeTo, holdToLevel, evolveAtLevel, alternativeAccess }
```

**The flagging rule (refined per user, 2026-05-28): flag only a genuine
loss of the level-up opportunity.** For an edge parent→child in a version
group, a move `M` is delay-worthy iff:

1. the **pre-evo learns `M` by level-up** at level `Lp ≥ 2`, and
   (level-up evo: `Lp > minLevel`; stone/trade: always — base `Lp = 1`
   moves carry through evolution, so they're excluded), **and**
2. **no reachable later form** (the subtree rooted at `child`, rolled up)
   learns `M` by **reachable level-up (level ≥ 2)**.

A later form learning `M` by level-up at `Lc ≥ 2` — *even much later* —
means **not flagged** (Hoothoot→Noctowl: every move is retained, so the
line produces zero flags). A later form that only has `M` at **level 1
counts as a loss** (level-1 = relearn-only via the Move Reminder; you
can't level into it).

There are no severity tiers — "delay saves a few levels" is explicitly
*not* delay-worthy. The single qualifier is `alternativeAccess`, the best
non-level-up route on a reachable later form, used for display ordering
(NONE first = truly unobtainable without delaying):
`NONE | RELEARN_ONLY (L1) | MACHINE | TUTOR | EGG`.

`holdToLevel = Lp` (level to reach on the pre-evo before evolving);
`evolveAtLevel = minLevel` (null for stone/trade). Branching lines roll up
optimistically over the whole reachable subtree (retained if *any*
reachable form keeps the level-up) — a known simplification; the famous
cases are linear.

## Scope (famous lines)

`targets/evolution-lines.txt` — 18 base species; chains auto-expand to all
stages: the 6 spiked (kricketot, caterpie, hoothoot, croagunk, spheal,
pansage), stone/trade classics with gutted evolved learnsets (pichu,
growlithe, vulpix, oddish, poliwag, shellder, staryu, exeggcute,
nidoran-m), and three added on request (budew, swablu, skitty —
skitty→delcatty is another gutted moon-stone learnset). **Eevee
deliberately excluded** — it's a *which-evolution* decision, not a
*when-to-evolve* one, so it adds branching noise without delay signal.

## Plan

- [x] Learnset/evolution DTOs + pure `EvolutionLineTransform` (data-ingestion).
- [x] `EvolutionLineIngestMain` + `ingestEvolutionLines` Gradle task +
      `targets/evolution-lines.txt`; ran it — 18 bundles committed.
- [x] `EvolutionLineJson` + domain types + `EvolutionLineDex` loader +
      `EvolutionDelayAdvisor` (data).
- [x] `advise-delays <species> [--game <vg>]` CLI (cli) + `adviseDelays` Gradle task.
- [x] Golden tests on the spiked lines + synthetic rule tests; `## Code review` below.

## Validation

Green signal: `./gradlew test ktlintCheck detekt`. The six lines become
golden tests under the refined rule — assert:
- Kricketot→Kricketune flags Bug Bite (Kricketune has no reachable
  level-up route); `alternativeAccess` = NONE in Platinum/BW, TUTOR in HGSS.
- Caterpie line flags Bug Bite in every gen Caterpie learns it
  (Butterfree only relearns@L1 / tutors / lacks it — never level-up ≥2);
  `alternativeAccess` = NONE in gen V, RELEARN_ONLY in SwSh/BDSP.
- **Hoothoot→Noctowl produces zero flags** (Noctowl relearns every move by
  level-up ≥2) — the canonical negative case validating the refined rule.
- Pansage→Simisage flags the stone moves Simisage can't level-learn
  (Crunch = NONE).
- Croagunk and Spheal — verify from cached data; expected to produce no
  flags (Toxicroak / Sealeo+Walrein relearn the candidates by level-up).

Each assertion is read from the committed bundle (offline, deterministic),
per the repo's "let the code tell you" rule — not from recollection.

## Open design questions

- **Egg moves** are a separate acquisition story (breeding, not delay).
  Capture the method but exclude from delay flags? Leaning yes.
- **Move Reminder availability is itself gen-specific** — a level-1
  level-up move on the evolved form means "relearnable" only in gens with
  a reminder NPC. Do we model reminder availability per gen, or treat
  level-1-on-evolved-form as always-OPTIONAL? The spike treated it as
  OPTIONAL; revisit if a fixture contradicts.
- **Stone/trade timing** has no level gate, so "holdToLevel" is the
  pre-evo's own learn level. Confirm CLI wording doesn't imply a forced
  evolution level for these.

## Open-question resolutions

- **Egg moves** — captured as `LearnMethod.EGG` and counted as an
  `alternativeAccess` fallback, but never as a level-up loss. As planned.
- **Move Reminder / level-1** — implemented per the user's refinement: a
  level-1 entry on the evolved form is *not* a reachable level-up (you
  can't level into 1), so the move is still flagged, annotated
  `RELEARN_ONLY`. Reminder-availability is not modeled per-gen — a known
  simplification noted below.
- **Stone/trade timing** — `evolveAtLevel` is null for these and the CLI
  says "evolves with <stone>", not a level. `holdToLevel` is the pre-evo's
  own learn level. Confirmed in `AdviseDelaysMain.edgeLabel`.

## CLI presentation (refined post-build, on user feedback)

Players use one game at a time, so the no-arg view is organized **by
generation**, not by move: per evolution edge, list each generation's
delay-worthy moves. Because a generation is *not* one learnset (Caterpie's
Bug Bite is L15 on Platinum/HGSS but absent on Diamond-Pearl, and tutors
get added in third versions — HGSS/B2W2/USUM gain a Bug Bite tutor their
paired game lacks), the view **splits a generation by game when its version
groups disagree**, rather than collapsing to one line and silently picking
a winner (which an earlier draft did). Within a split, the must-delay
(NONE) game leads and "evolve freely" trails. `--game <version-group>`
remains the precise single-game answer; we did *not* add `--gen` — a
generation can't give one honest answer when its games differ.

## Code review

Walking the CLAUDE.md diagnostic checklist; noting which apply.

- **Testable in isolation?** Yes. `EvolutionDelayAdvisor` is a pure
  function over one `EvolutionLine`; `EvolutionLineTransform` is pure over
  raw JSON. Golden tests run offline from committed bundles; two synthetic
  hand-built lines exercise each rule branch with no ingested data.
- **Readable / intuitive?** The advisor reads as the rule states it:
  candidate → not-retained → flag. `holdToLevel` / `alternativeAccess`
  match how a player thinks ("hold to L16; otherwise it's TM-only").
- **Layering.** Ingestion (`data-ingestion`) → committed bundles → dex
  types + advisor (`data`) → CLI (`cli`). The engine is untouched; the
  advisor has zero I/O. `data-ingestion` already depended on `data`, so
  the transform reusing `EvolutionLineJson`/`GenerationMap` adds no new
  edge. One mild coupling: the transform's TM-trimming filter encodes "the
  advisor only needs non-level-up methods for level-up moves" — documented
  inline; lossless for the advisor.
- **Colocated understanding?** Yes — a line's edges + every stage's
  learnset live in one bundle; the advisor needs nothing else. This is the
  reason for diverging from per-species files.
- **Hard-to-reverse choices?** The bundle JSON shape is the one durable
  contract. It's a superset (stores all four learn methods as slugs), and
  regenerating is one Gradle task, so revising is cheap. Low risk.
- **Auditable?** Yes. Bundles are committed and human-readable; a
  surprising flag traces to exact `(species, version-group, move, method,
  level)` rows. The transform sorts output for stable diffs.
- **Happy path?** `advise-delays <species>` → load line → per edge, list
  level-up moves the evolved line can't relearn by level-up.
- **Failure modes visible?** Unknown species prints the available list;
  missing manifest/resource throws with the path. Fetch failures during
  ingestion skip the line with a `[skip]` line, not a silent drop.
- **Duplicated logic?** Chain-walking exists in both the transform
  (edges) and the entrypoint (species list) — small, different return
  shapes, not worth unifying. `reachableFrom` (advisor) is distinct
  (forward closure over the in-memory line).
- **Illegal states?** `MoveAcquisition.level` is 0 for non-level-up
  methods (PokeAPI's convention); the advisor only reads `level` on
  `LEVEL_UP` entries, so the 0 is never misread. Could be tightened with a
  sealed type later; not worth it now.
- **Invariants enforced?** The "level ≥ 2 = reachable" floor is the one
  load-bearing constant (`REACHABLE_LEVEL_FLOOR`), named and commented.
- **Mutation where purity expected?** None — function-local accumulators
  only, consistent with the engine's immutability posture (though this is
  `data`, not `engine`).
- **Names match the domain?** "delay", "hold to level", "evolved form",
  "relearn", "move tutor", "TM" are all player vocabulary. `alternativeAccess`
  is the one invented term; it earns its keep.
- **Known simplifications (flagged, not hidden):** (1) branching lines roll
  up optimistically over the whole reachable subtree; (2) Move-Reminder
  availability isn't modeled per-gen, so a `RELEARN_ONLY` in an early gen
  without a reminder NPC slightly understates urgency; (3) default forms
  only — Alolan/Galarian learnset splits aren't distinguished. All three
  are noise only for non-linear / regional edge cases; the famous lines are
  linear and default-form.

**Industry comparison (substantial change — new data format + workflow).**
Closest analogs: Showdown's `@pkmn/dex` (programmatic learnsets) and
community guides (Serebii / Bulbapedia learnset tables). We *agree* on the
source data (PokeAPI derives from the same datamines). We *differ* in that
those present raw tables and leave the delay inference to the reader, while
this computes and ranks the inference deterministically — `@pkmn/dex` has
no "evolution-delay" concept. The closest internal analog is diary 079's
matrix-eval runner: a derived `data`-layer view over catalog data, no new
serialization contract imposed on the engine. The per-line bundle is a new
*on-disk format*, but a self-contained transformed artifact (like
`pokedex/species/*.json`), not a wire/interchange contract — so it's
revisable without coordinating consumers.

**Findings fixed in this diary's commit:** removed an unused-symbol
placeholder hack in the CLI; switched the TM-trim to a documented
line-global filter after observing bundles were 4.6 MB (now 1.9 MB).
**No architectural findings deferred.**

# Diary 041: Scaling Against PokeAPI + Smogon Stats (Planning)

**Date:** 2026-04-14
**Status:** Phase 1 complete (2026-04-14). Phase 3 complete (2026-04-14) — Smogon chaos ingestion with three-tier ELT; first target ingested (gen9ou 2026-02 cutoff=1825): 10.8MB raw → 1.3MB projected → 16KB top-sets JSON. **Phase 2 subsumed by `:data-ingestion:auditModelGap`** (diary 064): that task covers item/ability coverage against PokeAPI structure, and species coverage is implicit (we ingest more species than we currently use). No standalone Phase 2 deliverable remains — diary is complete.

## The idea

Two publicly-available data sources would let us *stress-test* our architecture at a
scale we can't approach with hand-curated data:

1. **[PokeAPI](https://pokeapi.co/docs/v2)** — comprehensive REST API covering all
   species, moves, abilities, items, types, learnsets, and cross-references. ~1000+
   entries in every category.

2. **[Smogon monthly usage stats](https://www.smogon.com/stats/)** — Pokemon Showdown's
   monthly statistics: which Pokemon, moves, items, abilities, Tera types, and spreads
   actually appear in competitive play at each usage tier (OU, UU, UUBL, RU, etc.)
   and each format (Gen 9 OU, VGC 2024 Reg H, National Dex, etc.).

Each has a specific, distinct role:

| Source | Role | Why |
|--------|------|-----|
| PokeAPI | **Reference facts** — canonical data for every species/move/ability/item | Bulk-load our data layer. The reference that replaces our hand-curated CSV. |
| Smogon stats | **Priority signal** — what's *actually used* in competitive play | Tells us what to implement next. Counter is in every OU usage dump; obscure moves aren't. Build what's used. |

**The framing:** PokeAPI gives us the *universe*. Smogon stats tells us which *subset*
of that universe is worth caring about for the meta we want to simulate. Most moves
in PokeAPI are never used competitively — shipping engine support for them is busy work
unless usage data says otherwise.

The thesis the registry pattern has been selling — *"adding an item/ability/move is one
file + one registry line; no engine changes"* — is falsifiable by counting the Smogon
top-N that we *can't yet handle*. That's a real number, not a guess.

## What this diary would actually ship

### Phase 1: PokeAPI ingestion

Build this as a **separate Gradle module** (`data-ingestion/`), not as code inside the
engine.

**Why module split matters (and what multi-module actually means):**

Gradle supports repos with multiple compilation units ("modules"), each with its own
`build.gradle.kts` and dependency list. A dependency declared in one module is invisible
to the others unless explicitly imported.

Why we want this: the repo will hold multiple clients over time (CLI, data-ingestion,
maybe a web UI in Kotlin/JS or TypeScript/React, maybe a stats analyzer). None of them
should leak their dependencies into the engine's build graph. If we add a React client
later, running `./gradlew :engine:test` must not require Node, npm, or any React deps.

The principle: **dependency direction flows into the engine, never out.** Clients depend
on the engine; the engine depends on nothing client-specific.

Target repo shape:

```
pokemon-battle/
├── settings.gradle.kts         — declares all modules
├── engine/                     — zero HTTP / UI / I/O deps; just Kotlin stdlib + test
│   ├── build.gradle.kts
│   └── src/
├── data-ingestion/             — ktor-client + kotlinx-serialization for network/JSON
│   ├── build.gradle.kts        — depends on engine's data types
│   └── src/
├── cli/                        — future; I/O shell over the engine
└── web-ui/                     — future; Kotlin/JS or separate TS/React subtree
```

Running `./gradlew :engine:test` compiles and tests engine only. Adding a web client
later doesn't touch engine build time or dep graph.

Step 1 of the work: restructure the current single-module repo into `settings.gradle.kts`
with an `engine/` module (moving everything that's there today). No behavior change —
just layout. Then Phase 1 adds `data-ingestion/` as a second module.

**Minimum-viable ingestion module:**
```
data-ingestion/src/main/kotlin/
├── PokeApiClient.kt     — wraps HTTPS calls to pokeapi.co
├── SmogonStatsClient.kt — fetches chaos files from smogon.com/stats/
├── Transforms.kt        — PokeAPI JSON → engine's Species/Move/Ability/Item shapes
└── Main.kt              — runnable: `./gradlew :data-ingestion:run --args "update"`
```

Output: CSV/JSON written into the engine's `src/main/resources/data/`. Engine reads at
runtime, doesn't care where the files came from.

### Phase 2: Coverage audit

- For each item in the PokeAPI dump:
  - Is it in our `Item` enum? If not, is its behavior expressible with existing hooks?
  - Categorize: "trivial to add" / "needs new hook" / "data-only (Type-Boost X, no engine effect)"
- Same for abilities.
- Produce a **Coverage Report** markdown file counting:
  - % of items/abilities currently registered
  - Breakdown of what categories of mechanic are unsupported
- This becomes the priority list for future diaries.

### Phase 3: Smogon stats ingestion

- Pull a recent monthly chaos file (JSON format)
- Compute **"percent of meta covered"**: given our current registry, what fraction of
  the top-500 sets in OU / VGC / NatDex can we simulate without blocking on a missing
  item, ability, or move behavior?

A realistic initial number might be 40%. The goal isn't 100% — it's to have a
quantitative baseline and a decay-to-zero plot as we tick off diaries.

#### Phase 3 — concrete plan (2026-04-14)

**Forcing function:** the user wants to "grab common moveset stats — could help
exercise different gens and rulesets." Real Smogon data feeds target lists for
PokeAPI ingestion and gives us plausible realistic movesets for play.

**Source URL shape:** `https://www.smogon.com/stats/<YYYY-MM>/chaos/<format>-<rating>.json`
(e.g. `2026-02/chaos/gen9ou-1825.json`). The "chaos" files are the richest:
`{info: {...}, data: {<Pokemon name>: {Abilities, Items, Moves, Spreads, Teammates, Checks and Counters}}}`,
with each inner object mapping identifier→weighted-usage-score.

**Module placement:** sub-package under `:data-ingestion`:
`data-ingestion/src/main/kotlin/com/pokemon/battle/ingest/smogon/`. No new module.

**Cache model** (same as PokeAPI, per the projection design):
- `.cache/smogon/<month>/<format>-<rating>.json` — verbatim raw (gitignored).
- `data/raw/smogon/<month>/<format>-<rating>.json` — projected through DTOs,
  committed; acts as its own fixture set.

**Transform target:** a compact `SmogonFormatSets` JSON per format with top-N
Pokemon, each with top-K moves / items / abilities by weight. Committed under
`data/smogon/<format>-top-sets.json`. This is the useful shape: one small file
per format, trivially readable.

**Stretch:** auto-generate `targets/smogon-<format>.txt` (lowercased slug list
for PokeAPI ingestion). Piggybacks on Phase 1's PokeAPI workflow.

**Not in this phase:** coverage audit, fuzz-test replay (those are Phases 2 and 4).

### Phase 4: Integration tests at scale

- "Replay a random top-100 OU set vs another top-100 set and assert the battle
  terminates without error" — fuzz-style test
- Catches: data loader choking, registry returning null where caller assumes non-null,
  moves with unexpected effects crashing the pipeline

## Why this is high-architectural-value work

Today the registry claims *"9 items, 14 abilities, 15 moves; adding more is cheap"* but
we haven't tested that at scale. 900+ moves could surface:
- Move-behavior registry necessity (diary 029's threshold): probably crossed by
  Counter, Mirror Coat, Pursuit, Metronome, Baton Pass — all in the "used in OU" set
- Whether our `MoveEffect` catalog needs 10 more variants or 100
- Data-shape gaps: Gen 1-3 data has different move categories (diary 028's projection)
- Performance: does `ItemRegistry.effectForHolder` get called thousands of times per
  battle? Does the `AbilityRegistry.effectFor` path allocate?

Smogon stats adds the **realism filter**. Per PokeAPI, there are ~900 moves. Per Smogon
OU stats, ~200 are meaningfully used. Supporting 200 well > supporting 900 poorly.

## Why deferring

Three reasons:
1. **Current priority list is known** — entry hazards, Substitute, first real gimmick,
   Disable/Encore/Taunt are all ahead in user value without needing this analysis
2. **Infrastructure cost is real** — ingestion + coverage scripts + fuzz tests = week
   of work before a single new mechanic ships
3. **The claim is more believable after we've shipped a few more mechanics** —
   validating "cheap at 30 entities" is more convincing than "cheap at 22"

Natural trigger to build this: once we've added **~10 more mechanics** (doubling the
current registry counts), the scaling claim is strong enough to be worth testing against
PokeAPI. Or: when someone asks "can you actually simulate OU matches?" and the answer
needs to be a real number not a guess.

## Related

- **Diary 029** — move-behavior registry, whose trigger threshold will likely be met
  by PokeAPI ingestion
- **Diary 032** — empirically audited 9 gens of move changes, established the registry
  pattern scales. This diary would audit *within* a single gen at full breadth.
- **Diary 028** — data-shape divergence; Gen 1-3 PokeAPI data would exercise the
  projection strategy
- **Diary 053** — module split; `:data-ingestion` is the first module built on top

---

## Phase 1 — concrete implementation plan (2026-04-14)

The broader framing above still holds. This section locks in the *how* for Phase 1
after the design conversation that followed the module split.

### Decisions

- **Separation of sources.** PokeAPI fetcher is ignorant of Smogon; takes a target list
  and executes. Target lists are curated manually (human or agent reading Smogon tier
  pages) into `targets/*.txt` files. Automated Smogon scraping is explicitly out of
  scope for Phase 1 — different project, different rate-limit concerns, format drift.
- **Layering.** `:data-ingestion` depends on `:engine` (for `Species`/`Move`/`Item`/
  `Ability` data shapes). `:engine` has no reverse dependency. Engine reads JSON from
  its own `resources/`; the fact that those files came from ingestion is not encoded
  anywhere in engine code.
- **ELT model.** Three tiers, three directories:
  - `.cache/pokeapi/<endpoint>/<slug>.json` — **full verbatim API response. Gitignored.**
    This is the politeness cache PokeAPI asks us to keep. ~300KB/species; re-fetch
    is cheap since PokeAPI is stable.
  - `data/raw/pokeapi/<endpoint>/<slug>.json` — **projected subset, committed.** Produced
    by deserializing the full response into our DTOs and re-serializing. Definitionally
    contains only the fields downstream transforms read. ~1KB/species — small enough to
    commit at full PokeAPI scale. Doubles as documentation: a reviewer sees the exact
    coupling surface to PokeAPI at a glance.
  - `engine/src/main/resources/pokedex/...` — engine-shaped JSON produced by a pure
    transform from the projected raw. Committed.
  - Pipeline: `fetch() → .cache/` → `project() → data/raw/` → `transform() → engine/resources/`.
    Transforms read the projected JSON (same DTOs), so offline/no-network workflows
    work from a fresh clone — `.cache/` is optional.
- **HTTP client.** Kotlin stdlib `HttpURLConnection`. No Ktor, no OkHttp. Batch job
  against a stable API; a dep isn't warranted.
- **Cache as politeness + reproducibility, not performance.** PokeAPI asks us to cache;
  Smogon (future) is volunteer-run. Committing the cache also means anyone re-running
  ingestion produces identical outputs without re-hitting the API. Our cache hit rule
  is "file exists" — more aggressive than HTTP caching. No `Cache-Control`/`ETag`
  handling. Cache invalidation is manual (`rm`). **Failed requests are not cached** —
  don't poison the cache with a 500.
- **Politeness layer.** 100ms sleep between *uncached* requests (10 req/s, well under
  any plausible limit). `User-Agent: pokemon-battle-engine/1.0 (github.com/...)` header.
- **No ELT framework.** Considered dlt (Python) and JVM-side options; rejected. Our
  transform must be Kotlin to produce engine-shaped data correctly, so a framework would
  only own the ~50 lines of E+L we can write ourselves. Two-language cost isn't worth
  it. Revisit if: third source, incremental/streaming needs, or SQL-based transforms.

### Module shape

```
data-ingestion/
├── build.gradle.kts               — depends on :engine + kotlinx-serialization (already in)
└── src/
    ├── main/
    │   ├── kotlin/com/pokemon/battle/ingest/
    │   │   ├── fetch/PokeApiClient.kt          — HTTP + on-disk cache + rate limit
    │   │   ├── transform/SpeciesTransform.kt   — raw JSON → engine Species
    │   │   ├── transform/MoveTransform.kt      — (follow-up; Species proves the shape)
    │   │   ├── cli/IngestMain.kt               — entry point
    │   │   └── Targets.kt                      — reads targets/*.txt
    │   └── resources/                          — (none expected)
    └── test/kotlin/com/pokemon/battle/ingest/
        └── SpeciesTransformTest.kt             — committed raw fixtures, no network
```

Curated target files live at the repo root:
```
targets/
├── species.txt                    — one name per line: pikachu, charizard, ...
├── moves.txt
├── items.txt
└── abilities.txt
```

### Plan

Incremental; each step compiles green before the next starts.

1. **Create the module.**
   - [x] `data-ingestion/build.gradle.kts` (kotlin jvm, serialization plugin, ktlint,
         detekt, JaCoCo — mirror `engine/`). Depends on `project(":engine")`.
   - [x] `settings.gradle.kts` — `include(":data-ingestion")`.
   - [x] Cache path is `data/raw/pokeapi/` at repo root — committed, not ignored.
   - [x] Confirm `./gradlew :data-ingestion:compileKotlin` runs (empty module OK).

2. **`PokeApiClient` — fetch + cache + rate limit.**
   - [x] GET `https://pokeapi.co/api/v2/<endpoint>/<id-or-name>` via `HttpURLConnection`.
   - [x] `User-Agent` header.
   - [x] Cache path: `data/raw/pokeapi/<endpoint>/<slug>.json`. If exists, return bytes.
   - [x] On fetch: 100ms `Thread.sleep` before request (skipped on cache hit).
   - [x] On non-2xx: throw, do not write cache file.
   - [x] No tests yet — tests come with the transform, which is the pure layer.
         `sleep` and `httpGet` are constructor-injectable so a future client-specific
         test can exercise the cache-hit/miss paths without network.

3. **`SpeciesTransform` — PokeAPI JSON → engine `SpeciesJson`.** (see "Architecture
   revision" below — the transform outputs a DTO, not the domain type.)
   - [x] `kotlinx.serialization` DTOs matching the PokeAPI `/pokemon/{id}` response
         (only the fields we use: name, base stats, types).
   - [x] Pure function `fun transform(raw: String): SpeciesJson`.
   - [x] Transform tests use inline PokeAPI-shaped fixtures (4 tests, all green).
         The "tests read from the committed cache" approach was dropped — inline
         fixtures keep the test self-contained and don't bind test correctness to
         whichever species happens to be in the cache today.

4. **`IngestMain` — the CLI.**
   - [x] Reads `targets/species.txt` line by line.
   - [x] For each: fetch (cached), transform, write JSON into
         `engine/src/main/resources/pokedex/species/<slug>.json`.
   - [x] Prints one line per species: `[cache|fetch] <name> -> <output path>`.
   - [x] `application` plugin with `mainClass = "...IngestMainKt"`.
   - [x] Manual validation: `./gradlew :data-ingestion:run` with the 4-species target
         list fetched all 4 successfully; re-run reports all 4 as cache hits.

5. **Prove end-to-end with `Pokedex.loadFromJsonDirectory`.**
   - [x] Engine test `PokedexJsonLoaderTest` asserts ingested files load and match
         mainline ground truth (Pikachu 35/55/40/50/50/90, Charizard FIRE/FLYING).

6. **Diary update.**
   - [x] Status flipped, checkboxes flipped, surprises recorded below.

### Deferred to later phases

- **Moves / items / abilities.** Follow the same `Transform` pattern once Species proves
  the end-to-end works. Each is an incremental PR, not a rewrite.
- **Coverage audit (Phase 2).** Compare ingested data against current registries to
  count what we support. Pure analysis, no engine changes.
- **Smogon stats ingestion (Phase 3).** Separate source, separate client, separate
  cache directory. Different rate limits.
- **Fuzz-test replay (Phase 4).** Depends on Phases 1–3.

### Non-obvious things to watch

- **PokeAPI slugs use lowercase + hyphens** (`mr-mime`, `ho-oh`). Our engine enum
  uses `MR_MIME`, `HO_OH`. The transform needs a stable slug↔enum mapping; do not
  guess. Start with a small mapping function and grow as target lists grow.
- **PokeAPI returns stats in an array with a `stat.name` field** (not a keyed object).
  DTOs must handle that shape; trivial but a place to write a wrong transform.
- **Don't edit engine code from `:data-ingestion`.** The dependency is one-way. If the
  transform needs something the engine doesn't expose (e.g., an extra Species field),
  that's an engine PR first, then the transform consumes it.

### Architecture revision (applied during implementation)

The original plan had `SpeciesTransform` produce `Species` (the domain type) directly,
and the diary's Step 5 talked about "existing loader" (CSV). Two antipatterns were
caught mid-implementation:

1. **Annotating `Species` with `@Serializable` would couple engine API evolution to
   on-disk format.** A rename like `baseAttack` → `baseAtk` would silently invalidate
   every committed JSON file. The domain model should not be the on-disk contract.
2. **Collapsing the staging shape into the domain** — standard ELT has three shapes
   (fetch → storage → domain), and we were down to two. The storage shape was implicit,
   which meant "what the engine persists" and "what the engine models" were the same
   thing; a schema break could come from either direction.

Fix: added `SpeciesJson` (`@Serializable`, in engine's `data` package) as the explicit
storage shape with a `toDomain(): Species`. `:data-ingestion`'s transform produces
`SpeciesJson`; engine's new `Pokedex.loadFromJsonDirectory(...)` reads `SpeciesJson`
and converts. The existing CSV loader stays untouched. Three shapes, one direction:
`PokeApiPokemon` (fetch, lives in `:data-ingestion`) → `SpeciesJson` (storage, in
`:engine`) → `Species` (domain, in `:engine`). Each boundary is a conversion we can
change independently.

### Surprises and decisions during Phase 1

- **Gradle `application` plugin defaults `workingDir` to the module directory.** The
  CLI reads `targets/species.txt` from repo root, so `./gradlew :data-ingestion:run`
  failed on first attempt. Fixed with `tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }`.
  Worth being aware of for any future module that reads repo-root files.
- **Detekt's `ConstructorParameterNaming` rule rejected `base_stat`.** Resolved by
  using the ktor-serialization idiom: Kotlin field is `baseStat`, mapped to JSON key
  via `@SerialName("base_stat")`. Same pattern will be needed for other snake_case
  PokeAPI fields (`special_attack`, `is_default`, etc.) when Move/Item transforms are
  added.
- **Raw cache size was 316× bigger than useful** — addressed same day. 4 species =
  ~1.2MB of PokeAPI JSON (each response carries full learnsets, game-indices across
  all gens, sprite URLs). Extrapolation: ~300MB for all 1000+ species. Split the
  cache into **three tiers** (see below) and re-projected the 4 committed files to
  ~3.8KB total.
- **Transform tests are inline fixtures, not cache reads.** Flipped from the plan
  because cache-based tests would couple test correctness to arbitrary choices about
  which species are currently cached. Inline fixtures are ~30 lines per test and
  make each test self-explanatory.

### Next up

- **Moves transform** — next natural increment. Same three-shape pattern:
  `PokeApiMove` → `MoveJson` → `Move`. Will force decisions about how to express
  PokeAPI's `damage_class` (physical/special/status) and `effect_chance`, neither of
  which our current hand-curated Move carries in exactly the same way.
- **Cache-size decision** — before ingesting ~100+ species, decide whether to commit
  a filtered raw cache (fetcher-side field subset) or gitignore the raw dir.
- **Smogon target curation** — the first meaningful use of Phase 1 will be ingesting
  an OU-tier species list; that's the "does this pipeline actually scale" moment.

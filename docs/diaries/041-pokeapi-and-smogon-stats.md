# Diary 041: Scaling Against PokeAPI + Smogon Stats (Planning)

**Date:** 2026-04-14
**Status:** Phase 1 ready to implement (module split landed in diary 053)

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
- **ELT model.** Two stages, two directories, **both committed**:
  - `data/raw/pokeapi/<endpoint>/<slug>.json` — verbatim API response. Committed.
    Reasons: offline/no-network workflows work (fresh clones + CI need no internet),
    test fixtures and the cache are literally the same files (no fixture/cache split),
    upstream-change diffs are visible, transforms are bit-reproducible across machines.
    Repo-size cost (~10MB of compressible text) is a non-issue for git.
  - `engine/src/main/resources/pokedex/...` — engine-shaped JSON produced by a pure
    transform. Committed.
  - Transform is `(raw JSON) -> Species/Move/...`, pure, testable without network.
    Re-running transforms does not require re-fetching.
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
   - [ ] `data-ingestion/build.gradle.kts` (kotlin jvm, serialization plugin, ktlint,
         detekt, JaCoCo — mirror `engine/`). Depends on `project(":engine")`.
   - [ ] `settings.gradle.kts` — `include(":data-ingestion")`.
   - [ ] Cache path is `data/raw/pokeapi/` at repo root — committed, not ignored.
   - [ ] Confirm `./gradlew :data-ingestion:compileKotlin` runs (empty module OK).

2. **`PokeApiClient` — fetch + cache + rate limit.**
   - [ ] GET `https://pokeapi.co/api/v2/<endpoint>/<id-or-name>` via `HttpURLConnection`.
   - [ ] `User-Agent` header.
   - [ ] Cache path: `data/raw/pokeapi/<endpoint>/<slug>.json`. If exists, return bytes.
   - [ ] On fetch: 100ms `Thread.sleep` before request (skipped on cache hit).
   - [ ] On non-2xx: throw, do not write cache file.
   - [ ] No tests yet — tests come with the transform, which is the pure layer.

3. **`SpeciesTransform` — PokeAPI JSON → engine `Species`.**
   - [ ] `kotlinx.serialization` DTOs matching the PokeAPI `/pokemon/{id}` response
         (only the fields we use: name, base stats, types).
   - [ ] Pure function `fun transform(raw: String): Species`.
   - [ ] Transform tests read directly from the committed cache
         (`data/raw/pokeapi/pokemon/<slug>.json`) — no separate fixtures directory.
         The cache IS the fixture set. Tests assert the produced `Species` matches
         ground truth for 1–2 species.

4. **`IngestMain` — the CLI.**
   - [ ] Reads `targets/species.txt` line by line.
   - [ ] For each: fetch (cached), transform, write JSON into
         `engine/src/main/resources/pokedex/species/<slug>.json`.
   - [ ] Prints one line per species: `[cache|fetch] <name> -> <output path>`.
   - [ ] `application` plugin with `mainClass = "...IngestMainKt"`.
   - [ ] Manual validation: `./gradlew :data-ingestion:run` with a tiny `species.txt`
         (pikachu, charizard) produces expected files. Re-run hits cache.

5. **Prove end-to-end with `Pokedex.loadFromClasspath`.**
   - [ ] One engine test asserts that the ingested species file parses and loads via
         the existing loader, matching the shape of hand-curated species already there.
   - [ ] If the shape doesn't match, fix the transform (not the loader).

6. **Diary update.**
   - [ ] Flip checkboxes, note any schema surprises, list any cases where the PokeAPI
         shape forced a loader or model change (shouldn't happen for Phase 1; a signal
         if it does).
   - [ ] Flip Status to "Phase 1 complete".

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

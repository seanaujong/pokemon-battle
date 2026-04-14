# Diary 041: Scaling Against PokeAPI + Smogon Stats (Planning)

**Date:** 2026-04-14
**Status:** Planning — deferred

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

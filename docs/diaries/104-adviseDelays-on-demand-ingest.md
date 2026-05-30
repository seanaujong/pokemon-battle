# 104 — adviseDelays ingests on demand into a gitignored cache

**Status:** Complete

## Goal

Make `adviseDelays <species>` "just work" for any species, including lines that
were never batch-ingested. On a miss, the CLI fetches the line from PokeAPI and
writes the bundle into a **gitignored local cache** (`.cache/derived/...`),
reading it alongside the committed set. The user never manages ingestion and
nothing new gets tracked in git. Promoting a line to the shared, committed set
stays the deliberate batch path (add to `targets/evolution-lines.txt`, run
`:data-ingestion:ingestEvolutionLines`).

## Questions asked and answers received

- **Fetch automatically, or via a flag / separate command?** — Automatically.
  The user should not think about ingestion: "the cli can do its job and will
  do its best to ingest when necessary (even if it needs a commit later)."
- **Track the on-demand results, or cache them?** — Cache, gitignored. The raw
  PokeAPI cache is already gitignored (`.cache/`); on-demand *derived* bundles
  join it there rather than entering version control.

## The trilemma behind the design

You can hold at most two of: (A) gitignore the verbatim raw cache; (B) don't
track derived docs; (C) deterministic, offline, network-free tests/CI. The repo
already chose **A + C** — `.cache/` is gitignored, and the small derived docs
(`data/src/main/resources/{pokedex/species, dex/evolution-lines}`, plus the
projected `data/raw/pokeapi/`) are committed so tests never touch the network
(`EvolutionDelayAdvisorTest` and `PokedexJsonLoaderTest` load them).

Untracking *all* derived docs (full A + B) would sacrifice C. So this feature is
deliberately **scoped**: the committed curated set stays the deterministic
backbone, and only *on-demand* species — which no test depends on — land in the
gitignored cache. That gets B for the new data without giving up C.

## Smell report (requested by Sean)

1. **Scoped, not sweeping.** Gitignoring the existing committed bundles would
   break offline tests (the trilemma above). The cache is for on-demand species
   only; the curated/committed set is untouched.
2. **A query that fetches + writes is unusual — named on the record.** Auto-
   ingest makes a read-shaped command hit the network and write a (gitignored)
   file. Defensible because it is a dev tool run from the repo via Gradle, the
   side effect is loud (it prints the cache path + how to make it permanent),
   the write is to a gitignored cache (not tracked), and the pure advisor core
   is untouched. Implicitly dev-only: a shipped jar can't write into the repo
   tree — acceptable under the current framing.
3. **Curated targets stay pristine.** On-demand species never enter
   `targets/evolution-lines.txt` or the committed manifest, so the curated
   "famous cases" list keeps its meaning. (This dissolves the earlier
   auto-targets-file smell entirely.)
4. **Stale doc found.** `PokeApiClient`'s header claimed "Cache is committed to
   the repo ... fresh clones work offline." `.cache/` is gitignored (0 tracked
   files); offline-ness comes from the committed *derived* docs, not the cache.
   Corrected in this change.
5. **Offline / fetch failure degrades gracefully** — a 404 or no-network falls
   back to an `Unavailable` result with a human-facing reason, never a crash.
6. **Determinism of tests** — the fetch is injected (`buildBundle` lambda), so
   the CLI tests exercise miss -> ingest -> advise with no live network.

## Plan

- [x] **Extract `EvolutionLineIngestor.buildBundle(slug, client, reader)`** in
      `data-ingestion` (package `com.pokemon.battle.ingest`). Resolves any stage
      slug to the chain *root* and keys the bundle by it, so `"drizzile"` yields
      the `sobble` line. Both callers share it.
- [x] **Refactor `EvolutionLineIngestMain`** to fold `buildBundle` over the
      curated targets (orchestration only). Batch output is identical — a base
      target's chain root is itself.
- [x] **Add `OnDemandEvolutionLines`** in `cli`: `loadAll()` = committed lines +
      cached bundles (committed wins); `resolve(species)` returns `Resolved`
      (with a `cachePath` when freshly ingested) or `Unavailable`. Injectable
      `buildBundle`; gitignored `cacheDir` default.
- [x] **Wire into `AdviseDelaysMain`** — on a fresh ingest, print the cache path
      and the "add to targets + run ingestEvolutionLines" promotion hint.
- [x] **Fix the stale `PokeApiClient` doc comment.**
- [x] **Tests** (`OnDemandEvolutionLinesTest`, no network): committed line needs
      no fetch; miss writes a bundle to a temp cache; a cached line resolves
      without re-fetching; fetch failure -> `Unavailable`; a bundle missing the
      queried species is rejected, not cached.
- [x] **Validate** `./gradlew test ktlintCheck detekt` — green; no churn to
      committed resources.
- [x] **Docs** — updated `docs/data-ingestion.md`: query-time diagram now shows
      the on-demand miss -> cache loop, and a new "On-demand lines — the
      gitignored second destination" subsection records the trilemma. Fixed the
      stale `PokeApiClient` "cache is committed" comment.

## Validation

- `./gradlew test ktlintCheck detekt` green.
- `.cache/` already ignores `.cache/derived/` — no `.gitignore` change, and a
  fresh `adviseDelays drizzile` stages nothing in git.
- Manual (online): `./gradlew adviseDelays -Pargs="drizzile"` prints advice and
  writes `.cache/derived/evolution-lines/sobble.json`; re-running reads the cache
  with no second fetch. (Not run here — dev-only network step.)

## Code review

Walked the checklist. Findings are minor; noted below.

- **Testable in isolation?** Yes. `OnDemandEvolutionLines.resolve` takes an
  injected `buildBundle`, so the miss -> ingest -> resolve path is tested with
  no network (temp cache dirs, a fake bundle). The advisor stays a pure function
  with its own tests. `main` is a thin shell over `resolve` (printing only) — not
  unit-tested, which is fine because the logic lives in the unit it folds over.
- **Readable / API matches the spoken design?** `resolve(species) ->
  Resolved(line, cachePath?) | Unavailable(reason)` reads as "found it (here's
  where I cached it, if fresh) / couldn't get it (here's why)". `cachePath` being
  non-null *only* on a fresh ingest is the one bit of cleverness — documented on
  the field.
- **Right layer / depends on what it shouldn't?** Serialization stays in
  `data-ingestion` (`writeBundle`); the CLI gained no JSON dependency (it tried
  to and failed to compile — caught early). The `cli -> data-ingestion` edge
  pre-existed. The pure advisor core is untouched.
- **Understanding colocated?** The on-disk bundle format is read in `data`
  (`EvolutionLineDex.loadFromJsonDirectory`) and written in `data-ingestion`
  (`EvolutionLineIngestor.writeBundle`). Both go through the single
  `EvolutionLineJson` serializer in `data`, so there is one format source; the
  read/write split follows the read-layer / production-layer boundary. Acceptable,
  not scattered.
- **Hard to reverse?** No. The on-demand path is contained in one class; deleting
  it reverts `adviseDelays` to classpath-only. The cache is gitignored — no
  migration, no tracked data to unwind.
- **Auditable?** A fresh ingest prints the cache path and the promote hint; the
  bundle is on disk. The committed/cached origin of a line is observable.
- **Happy path?** Committed hit returns immediately with no fetch (guarded by a
  test that injects an erroring `buildBundle` for a committed species).
- **Failure modes visible?** `FetchException` and `IOException` (offline) both
  map to `Unavailable(reason)`, which `main` prints. A PokeAPI response for the
  wrong line is caught by the `species !in line.species` guard *before* writing,
  so a bad bundle is never cached.
- **Duplicated logic?** Reduced: `buildBundle` was extracted from the batch main
  (both callers share it), and `writeBundle` replaced the batch's inline
  serializer.
- **Illegal states?** `Resolution` is sealed; the wrong-line case is rejected to
  `Unavailable` rather than returned as a `Resolved` that lies.
- **Invariants?** Bundle `base` is derived from the chain root, not trusted from
  the input slug — so any stage resolves to the correct line key. Batch output is
  unchanged because a curated target's chain root is itself (verified: full suite
  green, zero diff under `data/src/main/resources`).
- **Mutation where purity is expected?** `resolve` writes a file — the intended,
  named side effect of an on-demand cache, not hidden state mutation.
- **Names match the domain?** `OnDemandEvolutionLines`, `Resolution`/`Resolved`/
  `Unavailable`, `buildBundle`/`writeBundle`, `loadAll`, `cachePath` — all read in
  domain terms.

**Minor finding (not fixed):** on the `Unavailable` path, `main` calls
`resolver.loadAll()` a second time to list known lines, re-reading the classpath.
It is the failure path of a single CLI invocation, not a loop — not worth caching
the map. Left as-is.

### Industry comparison (substantial change)

This adds a **cache-aside (lazy-load) read-through** layer on top of a committed
golden dataset. On a miss the app loads from the source of truth (PokeAPI) and
populates a cache (`.cache/derived`), exactly the cache-aside pattern. Two
deliberate divergences:

- **Hybrid source of truth.** Classic cache-aside has one backing store and a
  pure cache. Here the "cache" is split: curated lines are a *git-committed*
  golden set, while un-curated lines are a *lazy* cache — with an explicit
  promotion path (cache -> `targets` -> committed batch) between the two tiers.
  That dual nature is unusual and is the price of resolving the trilemma.
- **Vs. the "don't commit generated artifacts" norm** (dvc / git-lfs / build
  outputs in `.gitignore`): we agree for on-demand lines (gitignored) but
  deliberately *disagree* for the curated set — committing it is what keeps CI
  offline and deterministic. The batch side remains the medallion/staged pipeline
  the rest of `data-ingestion.md` already maps; this change only adds the
  cache-aside read path.

No architectural findings to defer.

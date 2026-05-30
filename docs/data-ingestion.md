# Architecture: Data Ingestion

## At a glance

Data ingestion turns external sources (PokéAPI, Smogon) into **committed,
human-readable artifacts** that the engine and analytics layers load at
runtime. Three pipelines — species/stats, Smogon top-sets, and
evolution-delay lines — all follow one shape:

> **fetch (I/O) → pure transform → committed artifact → pure analysis → I/O**

I/O is quarantined at the two ends. Everything between is a pure fold, and
the committed artifact in the middle is the **seam**: it decouples
build-time (hitting the network) from query-time (running offline against
files). This doc owns the *why* — the invariants each layer holds and the
assumptions it rests on. For shapes and signatures, read the code and the
tests named below; prose that restates API shapes rots, so this doc
deliberately doesn't.

The evolution-delay pipeline is the worked example throughout, because it
exercises every layer. The species and Smogon pipelines are older instances
of the same model, sketched under *The three pipelines*.

## The diagram (evolution-delay pipeline)

```
──────────── BUILD-TIME  (./gradlew ingestEvolutionLines) ────────────
┌────────────────────────────────────────────────────────────────────┐
│ external                                                  (network) │
│ PokeAPI  /pokemon · /pokemon-species · /evolution-chain             │
└────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────┐
│ raw cache                                              (politeness)  │
│ .cache/pokeapi/{endpoint}/{slug}.json   — verbatim, gitignored      │
└────────────────────────────────────────────────────────────────────┘
                                   │ reused by the species/stats pipeline too
                                   ▼
┌────────────────────────────────────────────────────────────────────┐
│ fetch                                                         (I/O)  │
│ PokeApiClient.fetch(endpoint, slug)   + evolution DTOs              │
│ generic, endpoint-keyed; never caches a failure                     │
└────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────┐
│ transform                                                    (PURE)  │
│ EvolutionLineTransform.transform(...)                               │
│ 3 filters: modelled-methods · gen-mappable vg · TM-trim             │
│ sorts output  →  deterministic bundles                              │
└────────────────────────────────────────────────────────────────────┘
                                   │ writes
                                   ▼
┌────────────────────────────────────────────────────────────────────┐
│ ARTIFACT — the durable contract                              (seam)  │
│ dex/evolution-lines/*.json  +  index.txt   (committed)              │
│ edges + every stage's learnset, one self-contained line             │
└────────────────────────────────────────────────────────────────────┘
──────────────── QUERY-TIME  (./gradlew adviseDelays) ────────────────
┌────────────────────────────────────────────────────────────────────┐
│ dex + domain                                               (load)  │
│ EvolutionLineDex.loadAll = committed (classpath) U cached (dir)    │
│ MISS -> EvolutionLineIngestor fetches + caches the line under      │
│ .cache/derived/evolution-lines/ (gitignored), then toDomain()      │
└────────────────────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────────────┐
│ analysis                                                     (PURE)  │
│ EvolutionDelayAdvisor.adviseDelays(line, versionGroup)              │
│ THE ONE LAW: flag iff pre-evo learns by L>=2 AND no                 │
│ reachable later form relearns by L>=2.  REACHABLE_FLOOR=2           │
└────────────────────────────────────────────────────────────────────┘
                                   │ List<DelayFlag>
                                   ▼
┌────────────────────────────────────────────────────────────────────┐
│ presentation                                                  (I/O)  │
│ advise-delays <species> [--game <vg>]                               │
│ default: by generation, split games that disagree                   │
└────────────────────────────────────────────────────────────────────┘
```

## Layers: invariant and assumption

Each layer holds one invariant and rests on one assumption. Naming both is
what tells you, when something breaks, *which* layer owns the bug.

### Raw cache — the fixture set

**Invariant:** a `(endpoint, slug)` is fetched at most once, then served
from disk; a failed response is never written. **Assumption:** PokéAPI
responses are stable enough to treat the cache as a committed fixture set,
so a fresh clone builds offline. The cache itself is gitignored
(politeness only); the *filtered* artifacts downstream are what's
committed. The cache is endpoint-agnostic, so the evolution pipeline's
`/pokemon` reads hit the **same** files the species/stats pipeline
populated — already-fetched species cost no network round-trip.

### Fetch — one door per source

**Invariant:** each source has exactly one client, and that client is the
sole way its bytes enter the system. All clients share a contract: cache
first, sleep only before an *uncached* request, never write a failed
response. **Assumption:** a source's URLs share one shape, so one client
covers it. `PokeApiClient.fetch(endpoint, slug)` is generic over endpoint
because PokéAPI is uniformly `{base}/{endpoint}/{slug}` — which is why
adding `/pokemon-species` and `/evolution-chain` for the evolution pipeline
touched zero existing fetch code, just new DTOs.

Smogon breaks that shape (`{base}/{month}/chaos/{format}-{rating}.json`),
so it gets its own `SmogonClient` rather than contorting `PokeApiClient` to
generalize. Two clients sharing a contract is the honest call here: forcing
one generic client across two URL grammars would buy nothing and blur the
politeness difference (PokéAPI 100ms vs Smogon's volunteer-run 500ms).

### Transform — pure, deterministic, lossless-by-intent

**Invariant:** transforms are pure functions of their input JSON, with
**sorted** output so identical input yields byte-identical artifacts
(stable diffs, stable golden tests). **Assumption:** the fields the DTOs
declare are the only fields any consumer needs — deserializing with
`ignoreUnknownKeys` *is* the projection. The evolution transform adds three
filters (drop unmodelled learn methods; drop version groups with no known
generation; the TM-trim — see *The one cross-layer assumption*).

### Artifact — the durable contract (the seam)

**Invariant:** each artifact is self-contained and a *superset* of what its
consumer reads. **Assumption:** the chosen unit is the right one — for the
advisor, a whole evolution *line* (all edges + every stage's learnset in
one file), because the analysis never reasons about one species in
isolation. This is the layer most expensive to change, but the cost is held
down two ways: it's a superset (stores all four learn methods, not just
level-up), and it regenerates with one Gradle task. It is a transformed
on-disk artifact, **not a wire/interchange contract** — revisable without
coordinating external consumers.

### On-demand lines — the gitignored second destination

The line artifact has **two write destinations, one writer**. Build-time
ingestion (`ingestEvolutionLines`) folds over the curated targets and writes
**committed** bundles. At query time, `adviseDelays <species>` for a line
*not* in the committed set ingests it on demand into a **gitignored** cache
(`.cache/derived/evolution-lines/`); `EvolutionLineDex.loadAll` reads
committed bundles ∪ that cache. Both destinations share one writer
(`EvolutionLineIngestor.writeBundle`) and one format, so a cached line is
byte-identical to what the batch would have committed.

The split is deliberate, and it resolves a trilemma: you can hold at most two
of *(a)* gitignore the verbatim raw cache, *(b)* don't track derived docs,
*(c)* deterministic offline tests. The repo keeps **a + c** — the committed
curated set is the deterministic backbone that `EvolutionDelayAdvisorTest`
loads with no network. On-demand species get **b**: no test depends on them,
so they stay out of git as a local, regenerable cache. Promotion is the
deliberate path — add the base to `targets/evolution-lines.txt` and run the
batch — which moves a line from the cache into the committed, shared set. A
read-shaped command that fetches and writes is unusual; it is acceptable here
only because the write is to a gitignored cache, the side effect is printed,
and the engine is a dev tool run from the repo.

### Dex + domain — the single JSON→domain crossing

**Invariant:** `*Json.toDomain()` is the only place on-disk shapes become
domain types, and the domain is `val`-only. **Assumption:** slugs map
cleanly to enums (`fromSlug` sends anything unknown to `OTHER`, so a new
PokéAPI trigger degrades gracefully instead of throwing). `GenerationMap`
is static reference data (a version group's generation never changes), so
it's a table, not a fetch.

### Analysis — the pure core where the rule lives

**Invariant:** the analysis is a pure function with zero I/O, and the rule
lives **here and nowhere else**. **Assumption:** `level ≥ 2` means
"reachable by leveling" — named once as `REACHABLE_LEVEL_FLOOR`, so the
"level-1 is relearn-only" subtlety can't drift between the flag decision and
the `alternativeAccess` label. `EvolutionDelayAdvisor` is the full rule
statement.

### Presentation — formatting, never re-deriving

**Invariant:** the CLI groups and sorts the analysis output; it never
re-computes the rule. **Assumption:** a version group ≈ "a game", and a
generation is a rollup whose version groups *can disagree* — so the default
view splits games within a generation when they differ rather than
silently collapsing to one (the Caterpie case: Platinum must-delay, HGSS
tutor, Diamond-Pearl free).

## The one cross-layer assumption — and its guard

Layering is mostly clean: fix a bug in the layer that owns the invariant.
The exception, flagged deliberately, is the **TM-trim**
(`EvolutionLineTransform.trimToAdvisorRelevant`). It drops, at *build time*,
acquisitions the advisor cannot currently read — non-level-up moves that no
stage level-learns (mostly TMs), cutting bundle size ~2.4×. That encodes a
*downstream* assumption upstream: "the advisor only reads non-level-up
methods for moves some stage level-learns." It is lossless **only while
that holds**.

This is the one place where an invariant would otherwise live only in a
comment. `EvolutionLineTransformTrimTest` promotes it to a falsifiable
guard: it asserts the advisor produces identical flags on full vs trimmed
learnsets, and that the trim genuinely drops data (so the check isn't
vacuous). If the advisor's rule ever changes to read what the trim
discards, that test fails loudly instead of the bundles being silently
incomplete.

## The three pipelines

All three are instances of the model above. They differ along three axes:
the source, the unit of the committed artifact, and how many tiers they
keep between cache and artifact.

| Pipeline | Source | Artifact (committed) | Tiers | Loaded by |
|---|---|---|---|---|
| Species / stats | PokéAPI `/pokemon` | `pokedex/species/*.json` (per species) | 3 | `Pokedex` (runtime) |
| Smogon top-sets | Smogon chaos | `data/smogon/*-top-sets.json` (per format) | 3 | bridge + team builder |
| Evolution lines | PokéAPI ×3 | `dex/evolution-lines/*.json` (per line) | 2 | `EvolutionLineDex` |

"Tiers" counts the committed stages between the gitignored `.cache` and the
final artifact. A *three-tier* pipeline keeps an intermediate
`data/raw/<source>/` holding the **projected** response (verbatim minus the
fields no DTO reads) — useful when the raw projection is itself worth
diffing in review. The evolution pipeline is *two-tier*: it skips
`data/raw` because the artifact already *is* a projection, and committing a
second full copy of every movepool would just bloat the repo.

### Species / stats

`IngestMain` reads `targets/species.txt`, fetches `/pokemon`, projects to
`data/raw/pokeapi/pokemon/*.json` (name, types, stats — via
`PokeApiProjection`), then `SpeciesTransform` emits the per-species
`pokedex/species/*.json` the engine loads through `Pokedex`. An `index.txt`
manifest lists the slugs that ingested cleanly; skipped targets aren't
listed.

### Smogon top-sets

A genuine three-tier ELT, and the pipeline with the most interesting shape
— its one artifact feeds *two* consumers, one of which loops back:

```
┌────────────────────────────────────────────────────────────────────────┐
│ external + cache                                                  (I/O)  │
│ Smogon monthly chaos stats   (SmogonClient, 500ms politeness)          │
└────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│ tier 2 — raw projection                                          (PURE)  │
│ .cache/smogon/  →  data/raw/smogon/   (projected, committed)           │
└────────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌────────────────────────────────────────────────────────────────────────┐
│ ARTIFACT — top-sets                                              (seam)  │
│ data/smogon/<format>-top-sets.json   (transformed, committed)          │
│ SmogonTransform: top-N species × top-K moves/items/abilities           │
└────────────────────────────────────────────────────────────────────────┘
                  ┌──────────────────┴──────────────────┐
                  │                                      │
                  ▼                                      ▼
┌─────────────────────────────────┐    ┌─────────────────────────────────┐
│ consumer: feedback             ↺│    │ consumer: forward              →│
│ SmogonToTargetsMain             │    │ SmogonTeamBuilder               │
│ unions species into             │    │ materializes engine             │
│ targets/species.txt             │    │ Pokemon for                     │
│ (+ data/aliases.json)           │    │ MatrixEvalMain                  │
└─────────────────────────────────┘    └─────────────────────────────────┘
```

`SmogonIngestMain` fetches per `targets/smogon.txt` (one
`<month> <format> <rating>` triple per line) and `SmogonTransform` ranks
species by usage, keeping each one's top moves/items/abilities. Then:

- **Feedback (`SmogonToTargetsMain`)** unions the ranked species back into
  `targets/species.txt`, so "what's competitively relevant" *drives* what
  the species pipeline pulls next — a deliberate loop, run between the two
  ingestion passes. Smogon display names → PokéAPI slugs go through
  `data/aliases.json`, a committed **data-not-code** seam: fixing a name
  mapping is a JSON edit, not a recompile.
- **Forward (`SmogonTeamBuilder`)** is a *query-time* consumer (it lives in
  ingestion but is called from `MatrixEvalMain`, not during ingestion):
  it materializes engine `Pokemon` from the artifact, picking only
  moves/abilities the engine actually implements.

This is the same thin-shell principle as the evolution CLI: the artifact is
the seam, and consumers fan out from it without re-deriving it.

### Evolution-delay lines

The worked example diagrammed above. Two-tier; one artifact per line;
loaded by `EvolutionLineDex`, analyzed by `EvolutionDelayAdvisor`.

### Not a pipeline: ModelGapAudit

`ModelGapAuditMain` reads PokéAPI item/ability records and prints a markdown
table of fields our enums don't model. It produces no committed artifact —
it's a build-time *diagnostic* (the `auditModelGap` task) for motivating an
enum→data-class refactor, not an ingestion stage.

## Known assumptions and limitations

These are honest simplifications, recorded so they're not mistaken for
completeness:

- **Optimistic subtree roll-up.** The advisor keeps a move if *any*
  reachable later form relearns it; correct for linear lines, loose for
  branches (a Wurmple-style fork).
- **Move-Reminder availability isn't modeled per generation.** A
  `RELEARN_ONLY` in an early gen without a reminder NPC slightly understates
  urgency.
- **Default forms only.** Regional learnset splits (Alolan / Galarian)
  aren't distinguished.
- **The cache is trusted as a fixture.** If PokéAPI changes a learnset, the
  committed artifact is stale until someone deletes the cache entry and
  re-ingests. There is no freshness check.

## Industry analog

The committed-artifact-as-seam shape is the **medallion / staged-pipeline**
pattern (raw → cleaned → curated) familiar from data engineering, with two
deliberate differences: we commit the curated artifacts to git (they're
small and double as test fixtures), and the "curated" stage is a pure
function with golden tests rather than a scheduled job. The closest in-repo
analog is the analytics corpus — a derived view over catalog data, no new
contract imposed on the engine.

## Executable spec

The behavior described here is pinned by tests, which are the authority for
current shape: `EvolutionDelayAdvisorTest` (the rule) and
`EvolutionLineTransformTrimTest` (the cross-layer trim guard).

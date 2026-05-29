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
tests named below; per `docs/architecture.md`'s docs principle, prose that
restates API shapes rots, so this doc deliberately doesn't.

The evolution-delay pipeline (diary 103) is the worked example throughout,
because it exercises every layer. The species and Smogon pipelines are
older instances of the same model, sketched under *The three pipelines*.

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
│ dex + domain                                                 (load)  │
│ EvolutionLineDex.load*  →  EvolutionLineJson.toDomain()             │
│ EvolutionLine / Edge / MoveAcquisition · GenerationMap              │
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

### Fetch — the only door for bytes

**Invariant:** `PokeApiClient.fetch(endpoint, slug)` is the sole way
external bytes enter the system, and it is generic over endpoint.
**Assumption:** every endpoint we need is shaped `{base}/{endpoint}/{slug}`.
A new endpoint is a new DTO file, never a client change — that's why adding
`/pokemon-species` and `/evolution-chain` for the evolution pipeline
touched zero existing fetch code.

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
the `alternativeAccess` label. (See `EvolutionDelayAdvisor` and diary 103
for the full rule statement.)

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

All three are instances of the model above; they differ in source, unit,
and whether they keep a `data/raw` projection.

- **Species / stats** (`IngestMain`, `SpeciesTransform`): PokéAPI
  `/pokemon` → committed `data/raw/pokeapi/pokemon/*.json` (filtered
  projection: name, types, stats) → `pokedex/species/*.json` loaded by
  `Pokedex`. Keeps a `data/raw` intermediate.
- **Smogon top-sets** (`SmogonIngestMain`, `SmogonTransform`): Smogon
  monthly chaos stats → `data/raw/smogon/` + `data/smogon/`. Diary 041.
  `SmogonToTargetsMain` bridges back, extending `targets/species.txt` from
  what Smogon says is worth pulling.
- **Evolution-delay lines** (`EvolutionLineIngestMain`,
  `EvolutionLineTransform`): the worked example above. **Skips** a
  `data/raw` intermediate — the committed bundle *is* the projection, so we
  don't commit a second full copy of every movepool. The verbatim raws stay
  in `.cache` for regeneration.

## Known assumptions and limitations

These are honest simplifications, recorded so they're not mistaken for
completeness (cf. `docs/architecture.md`'s *Known Limitations*):

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
analog is the analytics corpus (diaries 079/081) — a derived view over
catalog data, no new contract imposed on the engine.

## Cross-references

- `docs/architecture.md` — the engine this feeds; the docs principle this
  doc follows.
- `docs/diaries/103-evolution-delay-advisor-plan.md` — the worked example's
  full rationale, rule statement, and code review.
- `docs/diaries/041-pokeapi-and-smogon-stats.md` — the original PokéAPI +
  Smogon ingestion design.
- Tests as executable spec: `EvolutionDelayAdvisorTest` (the rule),
  `EvolutionLineTransformTrimTest` (the cross-layer guard).

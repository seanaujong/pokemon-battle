# Diary 067: Seams Pokemon Showdown has that we don't (and what would force them)

**Date:** 2026-04-14
**Status:** Reference — captured for future-us when forcing functions arrive.

## Why this diary exists

Researching Showdown's structure for diary 066 surfaced a deeper question
than just "where should `render/` live." Showdown has architectural seams
that have aged across a decade of Pokemon evolution; we have a
significantly tighter codebase, partly because we're young, partly because
we've only been pressured along one axis.

The user's question: *"I'm very impressed that Pokemon Showdown knew the
seams of this business logic before AI was a thing. I wonder what we
missed."*

The honest answer is that **Showdown didn't know the seams a priori —
multi-gen, multi-format, multi-language, and replay support each forced
a seam over time.** Their architecture is the residue of accumulated
pressure, not foresight. We have less pressure, so we have fewer seams.
That's not a mistake; it's calibration. But knowing what their seams look
like *before* the same pressure hits us is the value of cataloging this.

## The table

Each row: a Showdown seam we lack, what it'd take to need it, and the
honest cost when the time comes.

| Showdown has | We lack | Forcing function | Cost when forced |
|---|---|---|---|
| **`data/mods/`** structure for gen-specific overrides at the *data* layer | ~~`gen/simplified/` is a sub-package with subclasses~~ **Partially shipped 2026-04-14** — `GenIVRegistries` in `:data` proves the "swap the registry, everything else compiles" claim with a measured 15-point win-rate delta (diary 087). Full `data/mods/` parallel-tree structure still deferred until 3+ gens need parallel trees. | (partial) | (partial) |
| **`aliases.ts`** as a first-class data file (e.g. "Mega Mewtwo X" → "mewtwomegax") | ~~Slug aliases live as a Kotlin `Map` inside `SmogonToTargetsMain`~~ **Shipped 2026-04-14** — aliases now live in committed `data/aliases.json`; extending a mapping is a JSON edit. | (addressed) | (done) |
| **`team-validator.ts`** as a layer between team-building and the engine | PlayMain hand-builds teams; no validation gate | Letting users supply teams (web UI, MCP tool, file import) — anything where the engine receives input it didn't construct | Medium. Need to design the validator's contract (which checks at which lifecycle point). |
| **`format-data.ts`** with per-Pokemon tier / format metadata | `Ruleset` exists but is a stub | A second format with distinct legality (NU/RU/UU tiering, VGC regulation differences) | Medium. The data shape isn't hard; what is hard is deciding which checks the engine enforces vs which the team-builder layer does. |
| **`battle-stream.ts`** — events streamed via async queue | We materialize `List<BattleEvent>` per turn | A real-time UI that animates events as they happen (Showdown's UX is canonical) | Medium-large. We considered it explicitly in diary 054 and deferred until a UI consumer arrives. The JSON DTO layer (diary 060) is a prerequisite that's now in place. |
| **`scripts.ts`** for gen-specific method overrides via inheritance | We use Kotlin subclassing (`SimplifiedEndOfTurnPhase`) | Adding 5+ gens with overlapping but partial method overrides — flat subclassing scales poorly | Medium. Probably becomes the same DI / registry-by-gen pattern that items + abilities will need anyway. |
| **`data/text/` and `translations/`** with localization keys | English strings hardcoded in `TextRenderer` and the per-item / per-ability text classes | First request to localize for a second language (or even second locale of English) | Large. Every render call site currently emits a literal string; switching to keys is a cross-cutting change. |
| **`random-battles/`** programmatic team generation | We hand-write team setups in PlayMain / DemoMain | Stress-testing the engine via fuzz battles, or wanting a "play a random team" CLI option | Small. The Smogon top-sets we already ingest (diary 041 Phase 3) is the seed data; we just need a builder that picks N species + their top moves. |
| **Replay format with backwards-compat versioning** | We have `BattleEventJson` DTOs but no version field, no migration path | First time a user has a saved replay that needs to load on a newer engine version | Medium. The DTO layer (diary 060) is the foundation; versioning is one more field + a migration registry. |
| **`Dex` lookup contract in `sim/` separate from data in `data/`** | `Pokedex` loader and `PokedexCatalog` data both live in `:data` | A second data implementation needed (e.g., `:engine`-side mock for tests, or a network-fetched catalog for live data) | Small. Extract a `Pokedex` interface to `:engine`; `:data` provides the implementation. |
| **Frontend and server as separate repositories** (`pokemon-showdown` vs `pokemon-showdown-client`) | Everything lives in one Gradle build; module boundaries are the only isolation | A second consumer with an independent release cadence (web UI in TS/JS, MCP tool, networked play server) | Medium-large. Publish `:engine` + `:data` as Maven artifacts, commit to a public API surface, document a wire protocol. The cleanest test of whether our module boundaries are real — co-located modules can cheat via `internal` or test fixtures in ways a separate repo cannot. |

## The deepest meta-lesson

**Showdown didn't predict seams; they extracted them every time pressure hit
a wall.**

Their architecture works in 2026 because it absorbed:
- Multi-gen rollouts (Gen 5 → 9, plus retroactive Gen 1-4 support) →
  forced `data/mods/` and gen-aware dex
- Custom formats (Hackmons, Almost Any Ability, Mix and Mega, etc.) →
  forced format-data + ruleset typing + validator
- Multi-language UI (Japanese, French, Spanish, German, etc.) →
  forced text/dispatch separation
- Replay system going back years → forced versioned wire format
- Real-time animated UI → forced battle-stream
- Random battles as a feature → forced programmatic team generation

We have one consumer (CLI), one format (Gen V-ish singles), one language
(English), no persistent replays, no UI animations, no random teams. We
correctly haven't built these seams. **The diary is here so that when
pressure arrives along one of these axes, we recognize the shape it
should take instead of inventing it from scratch.**

## What this diary doesn't propose

We're *not* preemptively building any of these. Each row's "Cost when
forced" is the honest scope; doing the work now would be the speculation
trap that 060 / 042 / 064 already warned about.

The diary is a *map for the future*, not a *task list*.

## Audit: did we already extract any of these seams *without* needing them?

Yes, two:

- **JSON event log (diary 060)** — we built the DTO layer before any
  consumer demanded persistence. Justified retroactively because diary
  064's `ReplayExporter` came along soon after; useful even without
  versioning.
- **`:data` extraction (diary 065)** — done because the engine's data weight
  was growing, not because a second data backend was demanded. Justified
  by the layering principle alone.

Both were small, both proved their value within one or two sessions of
shipping. So *some* preemption is fine when the move is small and the
direction is clear. The rule of thumb that holds: **extract a seam when
pressure is one diary away, not five.**

## Related

- **Diary 054** — explicitly considered streaming events; deferred to
  this kind of forcing function.
- **Diary 060** — DTO split; one of the prerequisites for several of the
  above (replay versioning, networked play).
- **Diary 064** — analyzed a different "consumer types" question;
  surfaces the dynamic-client need that several seams above also serve.
- **Diary 065** — established the `:data` split. Necessary precursor to
  the `data/mods/` and `Dex` interface seams above.
- **Diary 066** — the audit that triggered this comparison.
- **Pokemon Showdown** — `github.com/smogon/pokemon-showdown`. Reference
  browsed 2026-04-14.

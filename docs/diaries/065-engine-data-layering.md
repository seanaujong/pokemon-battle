# Diary 065: Engine vs. data layering — a structural reframe

**Date:** 2026-04-14
**Status:** Plan + regret accounting; refactor not yet executed

## The reframe

The engine module currently knows about specific Pokemon. `PokedexCatalog`
ships 98 species symbols compiled into the engine jar; `MoveDex` ships 28
specific moves; `engine/src/main/resources/pokedex/species/*.json` ships
98 JSON resource files inside the engine. **The engine isn't data-free
even though we keep saying it is.**

The user's framing this session: *"It's also possible that the engine layer
knowing about individual classes per Pokemon is wrong. Instead, maybe this
all lives in some kind of team builder layer above."* Pokemon Showdown is
the canonical example — `sim/` (engine) and `data/` (catalogs) are
separate packages, and `sim/` never imports a specific Pokemon.

## Why this is real

### Our own architecture says it

`docs/architecture.md` Lessons Learned: *"Move pools belong to the choice
layer, not the model."* Generalizes directly: **species pools, move pools,
item catalogs, ability catalogs all belong above the engine, not inside
it.** The engine resolves whatever is fed in. Catalogs of "what's
available to feed in" are a layer up.

### The growth pressure is already here

- 14 hand-written `Move` definitions in `MoveDex` today, ~900 in PokeAPI.
- 98 ingested species today, ~1000+ across all Pokemon.
- 10 items, 14 abilities — both will grow as we implement more mechanics.
- Smogon ingestion brought us to 4 formats; full coverage is dozens.

The engine being the home for all of this means engine compile + test
times absorb data growth even though the data plays no part in resolving
turns. A `:engine:test` run shouldn't slow down because we ingested
another format.

### Showdown matches this exactly

Pokemon Showdown's `pokemon-showdown/sim/` package is the simulator. Pure
mechanics. The data lives in `pokemon-showdown/data/` — a separate
package with `pokedex.ts`, `learnsets.ts`, `items.ts`, `abilities.ts`,
`moves.ts`. Custom formats are a `data/` swap with no `sim/` change. That's
the same separation we should have.

## Proposed shape

```
:engine          Phase, BattleEvent, BattleState, Species, Move, Item,
                 Ability, ItemEffect interface, AbilityEffect interface,
                 TurnPipeline, BattleLoop. The mechanics + the domain
                 types. Zero specific species / moves / items / abilities.

:data            PokedexCatalog (generated), MoveDex (hand-written),
                 ItemRegistry, AbilityRegistry, the species/* JSON resources,
                 the manifest. Depends on :engine for the types. Owns the
                 "what specific things exist" question.

:cli, :web-ui,   Team builders. Depend on :engine and :data. Pick from
:mcp             catalogs (e.g. PokedexCatalog.CHARIZARD), construct
                 Pokemon, feed into BattleLoop.

:data-ingestion  Unchanged role: produces :data's contents from PokeAPI /
                 Smogon. Depends on :engine for types; writes into :data's
                 source tree.

:analytics       Unchanged. Reads BattleEvents (engine domain). Doesn't need
                 :data's catalogs.
```

The split is on the **identity vs catalog** axis:
- **Identity types** (`Item`, `Ability` enums) stay in `:engine` because
  the engine pattern-matches on them.
- **Behavior interfaces** (`ItemEffect`, `AbilityEffect`) stay in `:engine`
  because they hook into engine internals (damage calc, end-of-turn).
- **Catalogs of specific instances** (`ItemRegistry` mapping `Item` →
  `ItemEffect`, `PokedexCatalog`, `MoveDex`) move to `:data`.

This is subtle: `ItemEffect` interface is an engine concept; `LifeOrbEffect`
implementing it is a data concept. The split puts each in the right module.

## Regret accounting — which recent diaries baked this in wrong

Capturing honestly so we don't repeat the mistake:

### Diary 041 (PokeAPI ingestion)

**Choice that aged poorly:** Phase 1 explicitly targets
`engine/src/main/resources/pokedex/species/<slug>.json` as the output of
ingestion. The diary's own architecture diagram puts the engine as the
data home: *"engine reads JSON from its own `resources/`."*

**What we should have done:** put the resources in a separate `:data`
module from the start. Adding it now means moving every committed JSON
file (98 + the manifest), updating `Pokedex.loadJsonFromClasspath()`'s
classpath base, and migrating `PokedexJsonLoaderTest`.

**Lesson:** "the engine has zero I/O" was already the rule (CONTRIBUTING.md
key invariant), but we treated *resources* as not-I/O. Resources ARE data,
and data isn't engine.

### Diary 064 (data as code vs data as resource)

**Choice that aged poorly:** the entire diary discusses `PokedexCatalog`
living in `:engine`. Both implementations (JSON loader + generated catalog)
sit inside the engine module, sharing classpath access. The dynamic-client
update (added this session) doesn't address the location question — both
paths still live in `:engine`.

**What we should have done:** explicitly noted that *both* paths belong in
`:data`, with `:engine` exposing only the type contracts. The choice
between symbol-keyed and string-keyed lookups is orthogonal to which
module owns the data.

**Lesson:** the dimensions I weighed (compile-time safety, debuggability,
dev experience) didn't include "which module should own this." That's a
layering question, not a consumption-shape question — and skipping it
hid the real smell.

### Diary 049 (CONTRIBUTING.md) and architecture.md

**Choice that aged poorly:** Both docs describe `data/` as a sub-package
*inside* engine. The dependency diagram in architecture.md shows
`data → model` and `engine → model`, treating `data` as engine-internal.

**What we should have done:** acknowledged from day one that data
catalogs belong in their own module — same way `:cli` exists for CLI
concerns, not as a sub-package of engine.

**Lesson:** "package" and "module" are different decisions. Putting data
in a `data/` package inside engine got us "logically separated" but not
"buildably separated."

### Diary 053 (engine module split)

**Mixed:** the diary correctly extracted `:engine` as its own module,
which set up future module-splits to be cheap. **But it kept all the
data inside engine** because at that point we had ~20 hand-curated
species in CSV form and "data" felt small enough to live in engine.

**What we should have foreseen:** that data growth was inevitable, and
the right time to extract `:data` was during the original split. The
fact that we have 98 species in JSON now (5× growth) without re-asking
the question is the smell.

**Lesson:** when extracting one module, ask which others are implied by
the same logic. We split out `:engine` because mechanics are independent
of clients; data catalogs are independent of mechanics by the same
argument.

## Cost of the refactor

Mechanical:

- New `data/build.gradle.kts` (mirror `engine/`'s)
- `settings.gradle.kts`: `include(":data")`
- Move from `engine/` to `data/`:
  - `src/main/kotlin/com/pokemon/battle/data/PokedexCatalog.kt`
  - `src/main/kotlin/com/pokemon/battle/data/Pokedex.kt` (the loader, but
    keep `loadFromClasspath` for legacy CSV — or kill the CSV path)
  - `src/main/kotlin/com/pokemon/battle/data/MoveDex.kt`
  - `src/main/kotlin/com/pokemon/battle/data/SpeciesJson.kt`
  - `src/main/kotlin/com/pokemon/battle/engine/item/ItemRegistry.kt` and
    every per-item `*Effect.kt`
  - `src/main/kotlin/com/pokemon/battle/engine/ability/AbilityRegistry.kt`
    and every per-ability `*Effect.kt`
  - `src/main/resources/pokedex/species/*.json` (98 files)
  - `src/main/resources/pokedex/species/index.txt`
  - `src/main/resources/data/species.csv` (legacy)
- Update imports across engine, cli, analytics, data-ingestion (lots of
  places — IntelliJ refactor candidate per preflight #2)
- `:data` depends on `:engine` (for `Species`, `Item`, `Ability`, `Move`,
  `ItemEffect`, `AbilityEffect`, etc.)
- `:cli`, `:analytics`, `:data-ingestion` add `:data` to their dependencies

Estimate: 2-3 hours, mostly an IntelliJ "Move" refactor + import fixups.
Tests should not require logic changes. The codegen output target shifts
from `engine/.../PokedexCatalog.kt` to `data/.../PokedexCatalog.kt`.

## Why we're not doing it this session

Following our own preflight + iteration loop discipline:

1. **The smell is real but not blocking.** `./scripts/play` works.
   `:engine:test` is fast enough today. Multi-format work isn't queued.
2. **The refactor surface is large** (~150+ file moves). Wants its own
   focused session per CLAUDE.md preflight #1 ("must run alone" for
   skeleton-level work).
3. **The diary is the artifact that matters most right now.** It captures
   the insight + the regret accounting so we don't lose either when we
   pick this back up.

## Triggers to actually execute

- Building a non-CLI client (web UI, MCP, networked server). Each new
  client revisits "do I import :engine + :data, or just :engine?"; the
  answer must be both, and the split makes that clean.
- Move ingestion or full item/ability ingestion arriving (would multiply
  the engine's data weight).
- A custom-format diary (e.g. Hackmons, alt rulesets) — the natural shape
  is a different `:data` package, not engine surgery.
- Empirical: a `:engine:test` run noticeably slower than feels reasonable.

## What this diary doesn't change

- All the design rationale and lessons learned in `architecture.md`
  remain correct — they describe the *engine*, which doesn't need
  catalogs to behave correctly.
- Diaries 055/061/062 (mid-turn prompts, state split, resume detection)
  are unaffected. Their work is mechanics, not data.
- The DTO split (060) is unaffected. Events are engine concepts.
- The CLI's interactive play (056) and analytics module (042) are
  unaffected — their consumption pattern doesn't care which module
  exports the catalogs.

## Related

- **architecture.md** — Lessons Learned: *"Move pools belong to the
  choice layer, not the model."* This diary generalizes that lesson.
- **Diary 041** — original ingestion target was wrong; needs amending.
- **Diary 053** — engine module split; should have included :data at
  the same time.
- **Diary 064** — both species-data implementations live in :engine
  today; both should move.
- **Pokemon Showdown** — `sim/` vs `data/` separation is the canonical
  precedent.

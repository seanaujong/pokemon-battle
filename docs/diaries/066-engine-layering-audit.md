# Diary 066: Layering audit of `:engine` after the data extraction

**Date:** 2026-04-14
**Status:** Audit — findings recorded, prioritized; no code changes this diary.

## Why this diary exists

Diary 065 extracted `:data` from `:engine` after the user observed that
*"the engine layer knowing about individual classes per Pokemon is wrong."*
That instinct was right. The follow-up question is: **were there other
similar mistakes?** We never explicitly asked "what should be in the
engine?" when we created it; we just kept adding things. This diary asks
the question for every sub-package, with Pokemon Showdown as a reference.

## Method

1. Inventory every sub-package currently in `:engine`.
2. For each, ask: does it resolve mechanics, or is it a consumer of the
   engine's outputs / a producer of inputs to it?
3. Compare against Showdown's `sim/` vs `data/` vs `server/` vs (external)
   client split.
4. Classify: *Belongs in engine* / *Should move out (clean cut)* /
   *Should move out (needs DI work first)* / *Borderline*.
5. Prioritize for action.

## Showdown reference

Browsed `github.com/smogon/pokemon-showdown` 2026-04-14. Their layering:

| Their package | What's in it | Our analog |
|---|---|---|
| `sim/battle.ts`, `pokemon.ts`, `side.ts`, `field.ts`, `state.ts` | Orchestration + domain types | `:engine` core (`BattleLoop`, `Pokemon`, `Side`, `BattleState`) |
| `sim/dex-*.ts`, `dex.ts` | Dex *lookup mechanism* (the contract for "find a Species/Move/Item") | `:data/Pokedex.kt` (loader) + `:engine` types |
| `data/pokedex.ts`, `moves.ts`, `items.ts`, `abilities.ts` | Catalogs *plus* per-entity behavior closures (closures take battle as DI'd parameter) | `:data/PokedexCatalog.kt` (catalog only); behavior still in `:engine/item`, `:engine/ability` |
| `data/random-battles/`, `data/mods/` | Team generation, gen-specific overrides | We don't have a clean home for these yet |
| `data/text/`, `translations/` | Localization strings | We have `:engine/render/*Text.kt` mixing text + dispatch |
| `server/` | Networked battle server | We don't have one |
| (external repo: `pokemon-showdown-client`) | UI, rendering, animations | We have `:engine/render/` + `:cli` |

The big tells:
1. **Showdown does not render inside `sim/` at all.** `TextRenderer` and
   the text registries we have under `:engine/render/` would be in a
   separate package by Showdown's standard.
2. **Showdown does not put AI in `sim/`.** `RandomAI` / `TypeAI` /
   `SidedAI` are consumers of the engine, not part of it.
3. **Showdown puts items/abilities/moves behavior in `data/`** with battle
   passed to closures (DI pattern). Our coupling — phases call
   `ItemRegistry.effectFor(...)` directly — is structurally the smell 065
   deferred.
4. **Showdown puts the dex *lookup contract* in `sim/`** (the `Dex` class)
   while data lives in `data/`. We technically have this split — `Pokedex`
   loader in `:data`, but the loader doesn't expose itself to the engine
   via a contract. Acceptable for now; revisit if a registry-DI refactor
   reshapes the boundary.

## Inventory of `:engine`'s sub-packages

Walking every sub-package and classifying:

### Core mechanics (belongs in `:engine`)

| Package | What's there | Verdict |
|---|---|---|
| `engine/` | `BattleEvent`, `BattleState`, `PipelineState`, `Phase`, `PhaseOutput`, `TurnPipeline`, `TurnResolution`, `InputRequest/Response`, `DamageCalc`, `ChanceCheck`, `SpeedResolver`, `MoveOrderResult`, switch helpers | **Stays.** This *is* the engine — types, mechanics, plumbing. |
| `engine/serialization/` | `BattleEventJson` (DTO for events) | **Stays.** Events are engine concepts; their wire format is too. |
| `model/` | `Pokemon`, `Species`, `Type`, `Item`, `Ability`, `Move`, `MoveEffect`, `Volatile`, `Side`, `Slot`, etc. — the domain vocabulary | **Stays.** These are the contract `:data` and consumers depend on. |
| `phase/` | `MoveOrderPhase`, `MoveExecutionPhase`, `EndOfTurnPhase`, `SwitchPhase` | **Stays.** Pure mechanics. |
| `loop/` | `BattleLoop`, `ChoiceProvider`, `FaintReplacementProvider`, `InputResponder`, `TurnRecord`, `BattleResult` | **Stays.** Loop is engine orchestration. (Showdown's `battle-stream.ts` is in `sim/`.) |

### Should move out — clean cut, no engine internals call this code

| Package | Why it's a smell | Where it belongs | Estimated cost |
|---|---|---|---|
| `ai/` (`RandomAI`, `TypeAI`, `SidedAI`, plus `SideProviders`) | AI is a *consumer*. Engine never calls AI; AI calls engine APIs (creates `TurnChoice` for `BattleLoop`). Showdown puts AI in server-side. By 065's argument, *what specific AI strategies exist* is a catalog separable from the engine. | New `:ai` module (or fold into `:cli` if it stays the only consumer). | ~30 min. Mostly file moves + `implementation(project(":ai"))` in `:cli`, `:engine` testImplementation. |
| `render/` (`TextRenderer`, `BattleRenderer`, `render/item/*Text.kt`, `render/ability/*Text.kt`, `ItemTextRegistry`, `AbilityTextRegistry`) | Engine never calls render. CLI calls `TextRenderer.render(event, ...)` to translate game events into player-visible text. Showdown explicitly does *not* render in `sim/` — rendering is client code. | New `:render` module. | ~45 min. File moves + dependency wiring in `:cli`, `:analytics` (analytics doesn't render, doesn't need it), `:engine` testImplementation (renderer tests use it). |

### Should move out — needs DI work first

| Package | Why it's tangled | Required first | Estimated cost |
|---|---|---|---|
| `engine/item/` (`ItemEffect` interface, `ItemRegistry`, per-item `*Effect.kt`) | Phases call `ItemRegistry.effectForHolder(...)` *directly* across `MoveExecutionPhase`, `EndOfTurnPhase`, etc. Moving the registry to `:data` would invert the layering (`:engine` → `:data`). | Phases must accept the registry as a parameter (constructor injection on `MoveExecutionPhase` / `EndOfTurnPhase`). Once registries are injectable, the registry + per-item code can move to `:data`. | DI plumbing: ~1 hour. File moves: ~30 min. |
| `engine/ability/` (`AbilityEffect`, `AbilityRegistry`, per-ability `*Effect.kt`) | Same coupling as items — phases call `AbilityRegistry.effectFor(...)` directly. | Same DI inversion as items. | Same. |
| `MoveEffect` sealed dispatch in `MoveExecutionPhase.resolveEffect` | Adding a new `MoveEffect` variant requires editing engine code. Diary 029 already documents this; same shape as the registry coupling. | A move-behavior registry (diary 029's threshold work) — itself a DI pattern. | This is the diary 029 work; estimated weeks if done thoroughly, or smaller scoped if just the few existing variants. |

### Borderline / leave alone

| Package | Note |
|---|---|
| `gen/simplified/` | Three classes implementing alternate gen variants of phases. Could become `:data/mods/` if multi-gen ever scales (Showdown's pattern), but with only one variant today, the cost of moving exceeds the benefit. Revisit when gen variants multiply. |
| `engine/engine/` package nesting | Diary 058 audit accepted as-is. Cosmetic only. |

## Recommended action sequence

In priority order (highest value / lowest cost first):

1. **Update `architecture.md` and recent diary cross-references** to match
   the post-065 module reality. Small back-edit, keeps the docs honest.
   *(See companion edits this session.)*

2. **Extract `:render`** as its own module. Clean cut, no DI needed,
   highest-value structural fix after 065. Engine's `:engine:test` shouldn't
   compile English text strings; rendering is presentation. ~45 min.

3. **Extract `:ai`** as its own module (or fold into `:cli`). Clean cut.
   ~30 min. *Open question to revisit when starting this work*: is `:ai`
   a peer of `:cli`, or part of `:cli`? Showdown puts AI in server; we
   don't have a server. Maybe `:cli` is fine for now and `:ai` only when
   a second consumer arrives (web UI, MCP).

4. **Registry-DI refactor** for items + abilities. Bigger work because it
   touches every phase that calls a registry directly. Required to enable
   moving `engine/item/` and `engine/ability/` into `:data`. Probably a
   diary of its own — call it the "engine accepts its registries" refactor.

5. **`MoveEffect` registry** (diary 029). Lower priority; the threshold
   the diary specified (3+ shape-A/B/C moves queued) hasn't been crossed.

6. **Multi-gen `:data/mods/`** style. Future, when gen variants
   multiply.

## Why we're not acting on items 2-6 in this diary

The same discipline that made 065 worth doing as its own diary applies
here:

- Each move surfaces its own design choices ("does AI deserve its own
  module or join CLI?"). Discussing them in the same diary as the audit
  blurs concerns.
- The audit itself is the deliverable — it captures what's wrong without
  requiring we fix it all at once. Future diaries can cite this.
- Per the user's pattern in 065: write the diary first, then act with
  fresh focus.

## Calibration: did 065 layer things correctly?

Yes, with one note. The `:data` module currently holds:
- `Pokedex.kt` — both the *loader contract* (what callers see) and the
  *loader implementation* (how it reads the classpath).
- `PokedexCatalog.kt` — the actual data.
- `MoveDex.kt` — actual data.
- `SpeciesJson.kt` — DTO for the loader.

By Showdown's stricter split, the *contract* for "look up a species by
name" might belong in `:engine` (their `Dex` class is in `sim/`) while
only the data lives in `:data`. We didn't split that finely, and it
doesn't currently bite us. Worth noting for if/when the registry-DI
refactor (item 4 above) reshapes the boundary — at that point we may
also want a `Pokedex` interface in `:engine` with the `:data`
implementation slotted in via DI. Until then, the pragmatic
loader-and-data-together shape is fine.

The other 065 deliverable (the regret accounting) was complete and
correct. No additional diaries from that vintage are now wrong — those
that were affected (041, 049, 053, 064, architecture.md) were called out
and either back-edited or noted explicitly.

## Updates to make to architecture.md

Companion to this diary, separately:
- "Layers" section currently lists `data/` as a sub-package of engine.
  Update to show `:engine`, `:data`, `:cli`, `:data-ingestion`,
  `:analytics` as peer modules with explicit dependency arrows.
- Lessons Learned: add a sibling to *"Move pools belong to the choice
  layer, not the model"* — *"Species pools, item catalogs, and ability
  catalogs belong in `:data`, not `:engine`. The engine resolves
  whatever it's given; it does not own a catalog of what exists."* Cite
  diary 065.
- Future Scenarios / Application Layer: refresh to reference the actual
  modules now that they exist.

## Related

- **Diary 058** — first naming/organization review. This is the same
  exercise applied at the module-boundary level instead of the
  type-naming level.
- **Diary 065** — the data extraction. This diary's twin: 065 removed
  data; 066 catalogs what else might follow.
- **Pokemon Showdown** — `sim/` vs `data/` vs `server/` reference,
  browsed via the GitHub API 2026-04-14.
- **Diary 029** — move-behavior registry. The DI work for `MoveEffect`
  is the same pattern as item-registry DI.

# Diary 028: Handling Data-Shape Divergence Across Gens

**Date:** 2026-04-13
**Status:** Analysis / principles — no implementation

## Context

Registries (diary 026, 027) solve **catalog divergence**: different gens have different
items, abilities, moves, weathers. You ship an empty `ItemRegistry` for Gen 1 and
everything still compiles because callers ask the registry and get nothing back.

But registries don't solve **data-shape divergence**: places where the core model's
*type signature* differs between gens. The canonical examples:

1. **Gen 1 Special stat** — one combined stat. Gen 2+ split it into Special Attack and
   Special Defense. Our `StatType` enum assumes Gen 2+.
2. **Phys/Special split** — Gen 1-3 categorize a move as physical or special based on its
   *type* (all Fire moves are Special; all Normal moves are Physical). Gen 4+ categorize
   per move (Shadow Ball is Special, Shadow Punch is Physical, both Ghost).

When registries hit these, they don't help — the problem isn't "behavior varies," it's
"the type doesn't even exist the same way across gens."

This diary is the operating principle for handling that class of problem.

## The operating principle

**Gen-specific data *interpretation* belongs at the data-load boundary. The in-memory
model stays uniform.**

Phrased as a rule:
- The engine sees one shape for stats, one shape for moves, one shape for Pokemon
- The loader — the code that reads species/move/Pokemon CSV/JSON — knows the gen and
  produces data in the engine's uniform shape
- Divergence is *projected* onto the uniform model, not preserved at runtime

This is a conscious trade-off: a small fiction at the data layer ("Gen 1 Pokemon have
two Special stats that are always equal") buys you a clean engine that doesn't need to
know about the divergence.

## Concrete strategies

### Gen 1 Special stat — strategy: project to Gen 2+ shape

- Keep `StatType` as the Gen 2+ shape: ATK, DEF, SPECIAL_ATK, SPECIAL_DEF, SPEED, HP.
- Gen 1 species data has a single `special` field.
- Loader populates `specialAttack = specialDefense = special`.
- Gen 1 damage calc reads `specialAttack` (or `specialDefense`) — they're equal, so the
  formula computes the right number.
- Invariant: a Gen 1 Pokemon always has `specialAttack == specialDefense`. Guaranteed by
  the loader, not enforced at runtime.

**What this does NOT require:**
- No gen-parameterized getters (`PokemonState.specialAttack(gen)`)
- No separate `Gen1Stats` type
- No runtime branching in the damage calc based on gen
- No polluted `StatType` enum with a legacy SPECIAL value

**What it does cost:**
- A tiny lie: the data says Gen 1 Pokemon have two Special stats. They don't, but the
  two are constrained to be equal. In practice nobody reads them differently.
- Stat-boosting moves in Gen 1 that "raise Special" are implemented as "raise both
  SpAtk and SpDef together" — true to the real mechanic, not awkward.

### Phys/Special split — strategy: normalize at data load

- Keep `Move.category: MoveCategory` as-is.
- Gen 1-3 move data specifies type but not category (real data source won't have it).
- Loader derives category from type at load time:
  - Normal, Fighting, Poison, Ground, Flying, Bug, Rock, Ghost, Steel → PHYSICAL
  - Fire, Water, Grass, Electric, Psychic, Ice, Dragon, Dark → SPECIAL
- Gen 4+ data specifies category explicitly per-move.
- Damage calc reads `move.category` uniformly. Doesn't know about gens.

**What this does NOT require:**
- No gen context passed to damage calc
- No `move.categoryForGen(gen)` helper
- No runtime type-to-category lookup

**What it does cost:**
- The loader encodes Gen 1-3 rules. If we add Gen 2 data, the loader has a branch. If
  we add Gen 4+ data, it doesn't. This is fine — loaders are gen-aware by nature.

## When the "project to uniform shape" approach works

Two conditions:

1. **The projection preserves truth.** A Gen 1 Pokemon's `specialAttack` and
   `specialDefense` being equal is *actually true* — they're the same stat, it's not a
   hack. Similarly, a Gen 1 move's category is truly determined by its type.
2. **The engine never needs to distinguish.** Gen 1 damage calc doesn't need to know
   "these two stats are really one" — it just uses `specialAttack` and the math is
   identical to what the real Gen 1 formula would produce.

If either condition fails, projection is a lie and you need something more.

## When it DOESN'T work — escape hatches

### Extend, don't fork

For genuinely new concepts in a gen (Dynamax, Terastal, Z-moves), add *optional* fields
to the uniform model:

```kotlin
data class PokemonState(
    val pokemon: Pokemon,
    val currentHp: Int,
    // ...
    val teraType: Type? = null,          // Gen 9 only
    val dynamaxState: DynamaxState? = null, // Gen 8 only
    val zMoveUsed: Boolean = false,      // Gen 7 only
)
```

Pre-Gen-7 gens never read these. Gen 9 sets `teraType` on Terastallization. The model
grows additively but doesn't fork into `Gen7PokemonState` vs `Gen8PokemonState`.

This works because these are *temporary battle states*, not species-level divergences.

### Gen-aware schemas (for true shape divergence)

If a gen has a different *structural* shape — say, Gen 6 Mega Evolution changes base
stats mid-battle, and future Mega-heavy gens need to track pre-Mega vs post-Mega — the
right answer is to add the divergence to the model once and let all gens that don't
care ignore it. That's still "extend, don't fork."

Truly forking the data model is a last resort. In Pokemon, I don't think any real gen
requires it.

## Summary table

| Divergence type | Strategy | Example |
|----------------|----------|---------|
| **Catalog** (what exists) | Registry — empty for gens without it | Items, abilities, weathers |
| **Formula / rule** | Injectable `fun interface` | DamageCalculator, SpeedResolver, TypeChart |
| **Data shape — projectable** | Normalize at loader | Gen 1 Special → SpAtk=SpDef; Gen 1-3 category → derived from type |
| **Data shape — genuinely new** | Extend the model with optional fields | Tera, Dynamax, Z-moves |
| **Data shape — pervasively incompatible** | Not encountered in Pokemon; would be last-resort parallel types | (none) |

## Why this matters architecturally

The alternative to this approach is one of:
- Gen-parameterized accessors everywhere (`pokemon.specialAttack(Gen.GEN_1)`) — pollutes
  every call site
- Parallel data types (`Gen1PokemonState` vs `Gen2PokemonState`) — duplicates everything
  that touches state, including phases
- Runtime dispatch on "what gen is this" — scatters gen knowledge across the codebase

Pushing divergence to the loader keeps the engine gen-agnostic in shape. The engine only
sees gen-specific behavior through its seams (Calculator, Resolver, Registry) — not
through the data shapes themselves.

This is a common pattern outside Pokemon too: database migrations (old schema projected
to new in-memory shape at read time), API versioning (legacy payloads normalized at the
edge), compiler IRs (dialect differences resolved at parse/load, not at every analysis
pass).

## Principle

When a cross-gen feature difference comes up, ask in order:
1. Is it a different *set* of entities? → registry (empty for gens without it)
2. Is it a different *formula*? → injectable calculator
3. Is the data shape the same but *values* differ? → loader handles it
4. Does a gen add a new concept? → extend the model with optional fields
5. Does a gen genuinely have an incompatible shape for a core type? → *now* we need a
   harder conversation (and maybe a richer model)

Most Pokemon divergence stops at (1)-(4). Step (5) is rare and deserves diary-length
discussion when it surfaces.

## Related diaries

- **Diary 026** — Item registry (Step 1 of this taxonomy)
- **Diary 027** — Ability registry (Step 1)
- **Diary 029** — Move-behavior registry analysis (Step 1 + some Step 2 hybrid)

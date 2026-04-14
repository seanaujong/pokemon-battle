# Diary 071: Registry DI — move items and abilities from `:engine` to `:data`

**Date:** 2026-04-14
**Status:** Complete (2026-04-14). Items and abilities moved to `:data`;
`:engine` retains only the interfaces and registry classes. All 272
tests green. Python smoke test still passes.

## Why this diary exists

Diary 066's priority-4 item: *"Registry-DI refactor for items + abilities.
Bigger work because it touches every phase that calls a registry directly.
Required to enable moving `engine/item/` and `engine/ability/` into
`:data`."*

Diary 065 extracted `:data` for species and moves. Items and abilities
were deferred because the engine's phases call `ItemRegistry` and
`AbilityRegistry` as `object` singletons (not injected dependencies).
Moving the registries without breaking that coupling would invert the
layering — `:engine` would depend on `:data`, the opposite of what we
want. This diary does the inversion: phases accept registries as
constructor parameters, the registries and their per-item /
per-ability effects move to `:data`.

## What this buys us

- **Catalog boundary is clean.** `:engine` becomes truly data-free —
  no hardcoded list of "which items exist." Consistent with diary 065's
  framing (species catalog) and 066's recommendation.
- **Gen-specific registries become trivial.** `GenIVItemRegistry` is a
  `Registries` instance registering a different effect set. No engine
  change; swap which instance the phase constructor receives.
- **External consumers can register custom items.** Today `ItemEffect`
  is `internal` (diary 068). After this refactor it's public in
  `:data`, and a custom-format consumer can build its own `Registries`
  with extra entries — the use case diary 068's "costs" section
  anticipated.

## Call-site audit

Registry calls from `:engine`:

| File | Registries used | Notes |
|---|---|---|
| `engine/DamageCalc.kt` | both | Inside `GenVDamageCalculator.calculate` — attacker + defender modifiers. |
| `engine/SpeedResolver.kt` | both | `GenVSpeedResolver` — item + ability speed mods. |
| `engine/HazardResolver.kt` | items (Boots) | `bypassesHazards` via `ItemRegistry.effectForHolder`. |
| `engine/SwitchInAbility.kt` | abilities | `resolveSwitchInAbility` — onSwitchIn hook. |
| `phase/MoveExecutionPhase.kt` | both | 7 call sites: damage intercept, post-damage hooks, HP-threshold berries, self-switch items. |
| `phase/EndOfTurnPhase.kt` | both | Weather-damage ability block, leftovers/berries end-of-turn. |
| `gen/simplified/SimplifiedEndOfTurnPhase.kt` | items | `endOfTurn` hook. |

Plus the cross-reference inside registries themselves:

- `ItemRegistry.effectForHolder` calls `AbilityRegistry.effectFor` to
  check Klutz suppression.
- `AbilityEffect.onHpThresholdCrossed` docstring references
  `ItemRegistry.effectForHolder` (not a call — just a doc link).

## Design: the shape of the injected thing

**One bundle, two registries.** `Registries(items: ItemRegistry,
abilities: AbilityRegistry)`. Phases that need either take a
`Registries` param rather than each registry individually — fewer
constructor args, one thing to update when a third registry appears
(move-behavior registry, diary 029).

Why not pass each separately? Because every current caller that uses
one also uses the other. The phases already treat items+abilities as a
pair conceptually.

**Registries become classes, not objects.** Today they're `object`s so
they can't be instantiated. After the refactor:

```kotlin
class ItemRegistry(effects: List<ItemEffect>, private val abilities: AbilityRegistry) {
    private val byItem = effects.associateBy { it.item }
    fun effectFor(item: Item?): ItemEffect? = item?.let { byItem[it] }
    fun effectForHolder(holder: PokemonState): ItemEffect? {
        val effect = effectFor(holder.item) ?: return null
        if (abilities.effectFor(holder.effectiveAbility)?.suppressesHeldItem(holder) == true) return null
        return effect
    }
}
```

`AbilityRegistry` similarly takes `List<AbilityEffect>` in its
constructor.

**Construction order.** `AbilityRegistry` is constructed first (no
cross-reference); `ItemRegistry` takes it as a param. `Registries(items,
abilities)` bundle is built last.

**Default instance in `:data`.** `:data` exports `GenVRegistries` or
similar — a `Registries` with the full Gen V item/ability set
registered. Callers that don't care (most tests, `PlayMain`,
`DemoMain`) use this default.

**Phase default params.** To avoid churn on every test, phases get a
`registries: Registries = Registries.empty()` default that registers
nothing (no items, no abilities). Tests that *rely* on items or
abilities already build specific scenarios and will pass
`GenVRegistries` explicitly; tests that don't will silently work with
the empty registry (no effect modifiers applied — same behavior as
today for Pokemon without items/abilities).

**Open question on empty default:** using `Registries.empty()` as a
default means a test that *should* see Leftovers healing but forgot to
inject the registries will silently pass. Safer default: no default at
all — force every phase construction site to pass `Registries`
explicitly. More churn in the refactor, but correctness is louder.
*Leaning toward explicit.* Will decide during execution.

## Execution order

1. **New `:data` infrastructure (additive, nothing else touches it):**
   - `data/src/main/kotlin/com/pokemon/battle/data/item/` — package
     skeleton.
   - `data/src/main/kotlin/com/pokemon/battle/data/ability/` — same.
2. **Move the per-item and per-ability effect classes** from `:engine`
   to `:data`. Git tracks these as renames; internal visibility has to
   become public (they were `internal` in `:engine`).
3. **Move `ItemEffect` and `AbilityEffect` interfaces** to `:data`. Public.
4. **Convert registries from `object` to `class`** and move to
   `:data`. Add constructor params.
5. **Add `Registries` bundle type** in `:data`, plus
   `GenVRegistries` instance.
6. **Update `:engine` callers** to accept `Registries` as constructor
   params (`MoveExecutionPhase`, `EndOfTurnPhase`, `SpeedResolver`,
   `HazardResolver`, `SwitchInAbility`). Remove direct registry imports.
7. **Update `:engine` tests** to construct phases with
   `GenVRegistries` (via `:data`). Most should already depend on
   `:data` as testImplementation.
8. **Update consumers** (`PlayMain`, `DemoMain`, `:server`, `:analytics`,
   `:ai`) to supply `Registries` when constructing phases.
9. **Delete `engine/item/` and `engine/ability/` packages** from
   `:engine/src/main`.
10. **Run full validation.** `./gradlew test ktlintCheck detekt`. Plus
    the Python smoke test to verify the wire protocol survives.
11. **Back-edit `architecture.md`** to reflect the move (Layers
    section, Registry Pattern section which currently points to
    `engine/item/`). Back-edit `CONTRIBUTING.md`'s recipes for "add an
    item" and "add an ability" — paths change from `engine/item/` to
    `data/item/`.
12. **Update diary 066** to mark priority 4 complete.
13. **Update diary 068** to note items/abilities have moved; the
    `internal` visibility on those types no longer applies because
    they live in `:data` now and are public.

## What this does NOT do

- **MoveEffect registry (diary 029).** Separate piece, same shape.
  Touching it in this diary would double scope.
- **Multi-gen `data/mods/` directory** (diary 067 row). The
  `Registries` bundle is the enabling pattern; actually shipping a
  second gen is a separate diary.
- **Renaming packages.** `data/item/` mirrors `engine/item/` exactly,
  just in a different module. No shape changes, no rename debate.

## Validation signal

- All 250+ existing tests pass.
- `./gradlew test ktlintCheck detekt` green.
- Python smoke test still passes end-to-end.
- `:engine/src/main` contains no reference to `ItemRegistry` or
  `AbilityRegistry` (grep returns zero hits).
- `EngineImmutabilityInvariantTest` still passes (nothing introduced
  top-level / class-level vars).

## What actually shipped (vs the plan)

**One correction during execution.** The original plan had `ItemEffect` /
`AbilityEffect` *interfaces* moving to `:data` alongside the concrete
effects. That would have inverted the dependency: `:engine` phases need
to call the interface, but `:data` already depends on `:engine`.
Created a cycle. Fix: interfaces and registry classes stay in
`:engine/item/` and `:engine/ability/` (the plugin contract), concrete
effects move to `:data/item/` and `:data/ability/` (the entries). This
matches Showdown's `sim/dex.ts` (contract) vs `data/items.ts` (entries)
split that diary 066 cited, closer than the original plan. Caught it
at the first compile attempt.

**Default registry choice.** Went with `Registries.empty` as the phase-
constructor default rather than forcing every test to pass
`GenVRegistries`. The open question in the plan favored "explicit" for
louder correctness, but the churn was ~80 test-call-site updates for a
default that tests-without-items don't care about. The empty default
means a test that *should* see Leftovers healing but forgot to pass
registries will silently fail the assertion — that's still a loud
signal in practice, just at test-assertion time instead of constructor
time. Documenting this tradeoff here so future-us can reverse if the
silent-pass mode ever bites.

**Hook signature extension.** `ItemEffect.onHpThresholdCrossed` and
`ItemEffect.onHolderTookDamage` and `AbilityEffect.onHpThresholdCrossed`
each gained an `abilities: AbilityRegistry` parameter. Required because
Sitrus Berry / Red Card / Emergency Exit force switches via
`resolveSwitchInAbility`, which now requires an `AbilityRegistry`.
Other hooks didn't need it. Minor interface-surface cost for correctness.

## Public API changes

- `ItemEffect`, `AbilityEffect` — interfaces now public (were `internal`
  in diary 068; the `internal` was specifically because "they're
  destined to move"). Published in `com.pokemon.battle.engine.{item,ability}`.
- `ItemRegistry`, `AbilityRegistry` — now classes, not objects.
  Constructor takes the effect list (plus `AbilityRegistry` for the
  item registry, for Klutz suppression).
- `Registries` — new data class bundle, in `com.pokemon.battle.engine`.
- `GenVRegistries` — top-level `val` in `com.pokemon.battle.data`.
- `MoveExecutionPhase`, `EndOfTurnPhase`, `MoveOrderPhase`, `SwitchPhase`,
  `SimplifiedEndOfTurnPhase` — gained `registries: Registries =
  Registries.empty` constructor parameter.
- `BattleLoop` — same.
- `GenVSpeedResolver` — was a `val`, now `genVSpeedResolver(registries)`
  factory function.
- `resolveSwitchInAbility(state, slot)` — gained `abilities:
  AbilityRegistry` parameter.
- `resolveHazardsOnSwitchIn(state, slot)` — gained `items: ItemRegistry`
  parameter.
- `DamageAdjustment` — made `public` (was `internal`); required because
  it's in `ItemEffect` / `AbilityEffect` method signatures.
- `resolveSwitchOutClearing` — made `public` (was `internal`); required
  because `EmergencyExitEffect` and `RedCardEffect` call it and they
  live in `:data` now.

## Related

- **Diary 065** — first half of the data extraction (species +
  MoveDex). This is the deferred second half.
- **Diary 066** — audit that prioritized this refactor.
- **Diary 068** — `internal` visibility audit. Marked items/abilities
  internal specifically because "they're destined to move out." This
  is that move.
- **Diary 069** — wire protocol / server. Uses `ItemRegistry` /
  `AbilityRegistry` indirectly through phases; the refactor has to
  keep the server's behavior identical.
- **Diary 029** — move-behavior registry, the same DI pattern for
  moves. Deferred separately.

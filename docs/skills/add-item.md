# Skill: Add an item to the engine

**Scope:** `:engine` (enum + interface), `:data` (concrete effect + registry entry),
`:render` (optional flavor text), `engine/src/test` (behavior test).
**Level:** user goal.
**Primary actor:** contributor (human or AI agent) adding a new held-item behavior.

## Stakeholders and interests

- **Contributor** — wants a fast path from "I want X to work" to a green test,
  without reverse-engineering the module layout.
- **Future contributors** — want the new item to follow the established shape so
  it's recognisable on sight (no bespoke dispatch, no scattered conditionals).
- **Engine maintainers** — want `:engine` to stay data-free; the registry-DI
  split from diary 071 depends on every new item landing in `:data`.

## Preconditions

- The battle mechanic your item needs already has a hook on `ItemEffect` (Life
  Orb, Leftovers, Choice items, Focus Sash, Red Card — see existing effects for
  the hook inventory). **If it doesn't,** see the *Extensions* below before
  starting.
- You have a committed checkout and a green `./gradlew test`.

## Minimum guarantees

- If you stop partway, nothing existing regresses: `Item` enum additions with no
  registered behavior are caught by `RegistryCoverageTest` and fail the build
  explicitly (not silently).

## Success guarantees

- The item is usable in `PokemonState(pokemon, item = Item.YOUR_ITEM)`.
- At least one test asserts the intended behavior under a deterministic roll.
- `./gradlew test ktlintCheck detekt` stays green.
- `RegistryCoverageTest` stays green (either you registered an effect or you
  explicitly added the new value to `identityOnlyItems`).

## Trigger

You want Pokemon to be able to hold an item that does a thing they can't do
today.

## Main success scenario

1. **Decide whether this item has battle behavior.** If it's purely
   identity/flavor (a berry we track for team-building only, for example),
   jump to extension *1a*. Otherwise, continue.
2. **Add the enum value.** Edit
   `engine/src/main/kotlin/com/pokemon/battle/model/Item.kt` — append or group
   by category. Enum order is cosmetic; additive inserts merge cleanly
   (diary 043).
3. **Create the effect file** at
   `data/src/main/kotlin/com/pokemon/battle/data/item/<Name>Effect.kt`.
   Implement `com.pokemon.battle.engine.item.ItemEffect`. Override *only* the
   hooks your item actually uses — each hook defaults to a no-op. Name the
   singleton `<Name>Effect` and set `override val item = Item.<NAME>`.
4. **Register the effect.** Add `<Name>Effect` to the `listOf(...)` inside
   `GenVRegistries` in `data/src/main/kotlin/com/pokemon/battle/data/Registries.kt`.
   Position is cosmetic. The `RegistryCoverageTest` will fail the build if you
   skip this step — which is the intent.
5. **Emit an appropriate `BattleEvent` from the hook.** Reuse existing events
   where possible (`ItemDamage` for recoil, `ItemHealing` for restore,
   `ItemConsumed` for one-shot consumption). A new event type is rarely
   needed for a new item; see *Extensions* if it is.
6. **Add render text** (optional). If your item emits an `ItemDamage`,
   `ItemHealing`, or `ItemConsumed` event and you want custom flavor text,
   create `render/src/main/kotlin/com/pokemon/battle/render/item/<Name>Text.kt`
   implementing `ItemText`, then register it in
   `render/src/main/kotlin/com/pokemon/battle/render/item/ItemTextRegistry.kt`.
   Items without an entry render a built-in default for the event type; items
   that emit only generic damage/healing events often need no custom text.
7. **Write a behavior test** at
   `engine/src/test/kotlin/com/pokemon/battle/<Name>Test.kt`. Use
   `Pokedex.loadFromClasspath()` for real species data, pin damage rolls via
   `roll = { 100 }` and crits via `chanceCheck = { _, _ -> false }` so
   assertions are exact. Cover at least the happy path (item fires), a
   no-damage case (item doesn't fire), and a faint case (item's recoil can KO).
8. **Run validation:** `./gradlew test ktlintCheck detekt`. Then add and commit.

## Extensions

**1a. Identity-only item (no battle behavior yet).** Add the enum value in
   step 2, skip steps 3–6, and add the value to `identityOnlyItems` in
   `data/src/test/kotlin/com/pokemon/battle/data/RegistryCoverageTest.kt`.
   Include a one-line comment saying why it's identity-only (e.g. "Berry
   Juice: effect TBD, catalog only"). When you later implement the behavior,
   remove it from the identity-only set and follow steps 3–6.

**5a(i). Your item needs a hook that doesn't exist yet.** Example: a
   "taunt on switch-in" item would need a new `onSwitchIn` hook on
   `ItemEffect`. Add the defaulted method to
   `engine/src/main/kotlin/com/pokemon/battle/engine/item/ItemEffect.kt`
   with a clear doc string, then wire it in at *every* call site that
   triggers the mechanic (a single phase, or a resolver, or both — see
   `add-ability.md`'s 1a for the switch-out example where two sites
   fire). Do **not** add `when (item)` branches in the caller — the
   registry dispatch is the point.

**5a(ii). Your item needs an *existing* hook to carry more context**
   (example: Weakness Policy needed `onHolderTookDamage` to know the
   incoming move's effectiveness). Extend the hook's parameter list
   in `ItemEffect.kt`, update the one call site, and update every
   existing override to accept-and-ignore the new param. Expect
   detekt's `LongParameterList` threshold (6) to fire once a hook
   passes ~5 args — land an inline `@Suppress("LongParameterList")`
   with a one-line rationale (e.g. "on-hit items need full
   attacker/defender/damage/type-eff context"). Diary 074 is the
   worked precedent; Weakness Policy hit 7 params and the suppress is
   documented there.

**5b. Your item needs a new `BattleEvent` variant.** Example: a unique item
   that needs its own audit-log entry beyond generic damage/healing. Follow
   the "Add an event" skill (`add-event.md`), then emit it from your hook.

**7a. The test reveals the hook fires at the wrong pipeline position.**
   Hooks run exactly where `MoveExecutionPhase` / `EndOfTurnPhase` / etc. call
   them today. If the ordering doesn't match real-game sequencing, that's a
   phase-level bug, not an item-level one — file a diary and adjust the
   phase's hook order, don't special-case your item.

## Related information

- **Worked example (in repo):** `RockyHelmetEffect` (diary 071's dogfood
  exercise). Six-file diff: enum value, effect, registry entry, render text,
  text registry entry, test. About 20 minutes end to end.
- **Canonical design:** diary 026 (first item extraction), diary 038 (rendering
  separation), diary 071 (registry DI moving items to `:data`).
- **Gotchas:**
  - The contact-moves simplification: Rocky Helmet should only fire on contact
    moves, but `Move` has no `contact: Boolean` flag yet. Red Card has the
    same gap. If your item legitimately needs the contact distinction, that's
    a `Move`-shape change, not an item-effect change.
  - If the damage calculator or speed resolver needs to consult your item,
    that's already threaded: both take a `Registries` parameter as of diary 071.
    Don't reach for a global.

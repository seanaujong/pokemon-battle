# Diary 034: Rendering / Behavior Seam (Planning, Not Implemented)

**Date:** 2026-04-13
**Status:** Superseded by diary 038 (2026-04-14) which executed this plan

## Context

`ItemEffect` and `AbilityEffect` currently carry *both* behavior hooks (`attackerDamageModifier`,
`onSwitchIn`, `interceptIncomingDamage`, etc.) *and* render hooks (`renderHealing`,
`renderConsumed`, `renderTriggered`, `renderBlocked`).

Each effect object is the single file for everything about that entity. Intimidate's
switch-in logic lives next to Intimidate's trigger text in the same `object`.

This is **colocation** — the "Is understanding colocated?" principle from CLAUDE.md.
Changing Intimidate's behavior usually needs a text update; keeping them in one file
makes both changes obvious.

But rendering is a *presentation* concern, not a behavior concern. A multi-renderer
future (JSON events for a web UI, localized strings, HTML with color codes, structured
log output for analytics) would want the same effect to render differently per renderer.
Coupling means every renderer would have to either (a) subclass every effect or (b) swap
in a whole alternate effect registry.

## When to keep coupled (current scope)

All of the following conditions hold:

- **One renderer.** We have `TextRenderer` only.
- **No localization plans.** Text is English-only.
- **Short render strings.** One-liners, no formatting or styling.
- **Low effect count.** ~10 items/abilities with render text. Coupling's "double the
  files" cost is moot at this size.

Under these conditions, separating would trade simplicity for flexibility we don't use.
Colocation wins.

## When to split (trigger conditions)

Any of these, individually:

- **Adding a second renderer.** JSON event stream for a web UI, structured log for an
  analytics pipeline, HTML for a browser client.
- **Adding localization.** First non-English locale = split, because localized strings
  belong in resource bundles, not code.
- **Render logic gets complex.** If `renderTriggered` grows multi-paragraph, starts
  doing conditional formatting, or needs to pull state beyond `pokemonName`, the
  effect file becomes hard to read and the split pays off.
- **Effect count grows past ~30.** At scale, colocation starts to bloat each file
  (behavior + render string for every hook = wall of text).

## How the refactor goes

**Target shape:**
```
engine/item/
├── ItemEffect.kt          — behavior hooks only
├── ItemRegistry.kt        — unchanged
├── LifeOrbEffect.kt       — override attackerDamageModifier, afterUserMoveDamage
├── FocusSashEffect.kt     — override interceptIncomingDamage
└── ...

render/item/               — new package
├── ItemText.kt            — render interface, one method per event type
├── ItemTextRegistry.kt    — map from Item enum → ItemText (may differ per renderer)
├── LifeOrbText.kt         — render methods for LifeOrb's events
├── FocusSashText.kt       — render methods for FocusSash's events
└── ...

render/TextRenderer.kt     — consults render/item/ItemTextRegistry
render/JsonRenderer.kt     — future; its own render/item/ItemJsonRegistry
```

**Step-by-step:**

1. **Create `ItemText` interface** in `render/item/`:
   ```kotlin
   interface ItemText {
       val item: Item
       fun renderHealing(amount: Int, pokemonName: String): String = ""
       fun renderConsumed(pokemonName: String): String = ""
       fun renderDamage(amount: Int, pokemonName: String): String = ""
   }
   ```
2. **For each item, create a parallel `XText` singleton** that holds just the strings:
   ```kotlin
   object LifeOrbText : ItemText {
       override val item = Item.LIFE_ORB
       override fun renderDamage(amount: Int, pokemonName: String) =
           "$pokemonName was hurt by its Life Orb!"
   }
   ```
3. **Create `ItemTextRegistry`** mapping `Item` → `ItemText`, mirroring `ItemRegistry`.
4. **Update `TextRenderer`** to call `ItemTextRegistry.textFor(item)?.renderHealing(...)`
   instead of `ItemRegistry.effectFor(item)?.renderHealing(...)`.
5. **Delete render methods from `ItemEffect`** interface and from each effect object.
6. **Same for abilities** — `AbilityText` interface, per-ability text singletons,
   `AbilityTextRegistry`, `TextRenderer` updated.
7. **Verify all tests still pass** — renderer-level tests may need updates, but battle
   behavior tests should be untouched.

**Estimated effort at current scale (~10 items + ~11 abilities):** a half-day. The
behavior extraction is mechanical; the text extraction is copy-paste per entity.

## Why not do it now

- We have one renderer, one language, short strings, and ~10 entities per registry.
- Doubling the file count today gives flexibility we don't currently use.
- The pattern works fine as-is; colocation is an asset, not a debt.

## Why document it now

- The seam is obvious but easy to lose sight of as the registries grow.
- A future diary will reference this when the trigger fires, avoiding rehashing the
  decision.
- It's explicit — if we add a second renderer, we don't start by asking "why are render
  methods on the effect interface?" The answer is here.

## A softer middle ground (optional, if coupling starts hurting)

Instead of a full split, an `@JvmField` companion registry for just render text could
co-exist with behavior:

```kotlin
object LifeOrbEffect : ItemEffect {
    override val item = Item.LIFE_ORB
    override fun attackerDamageModifier(attacker, move) = 1.3
    // ... behavior only

    companion object {
        val text: ItemText = LifeOrbText  // colocation by import, separation by access
    }
}
```

Effectively the same as the full split, but keeps the files siblings. Probably not worth
the ceremony unless a specific reason arises.

## Principle

**Colocation is correct until you have a reason not to.** Behavior + presentation usually
change together, and two files for one concept fragments understanding. But presentation
is inherently renderer-specific, and multi-renderer systems benefit from the split. The
split is mechanical and can be done in a half-day when needed — planning for it early
lets us keep the current simpler shape without accidentally locking ourselves in.

## Related

- `architecture.md` — "Understanding colocated" as a principle
- **Diary 026** — Item registry (where render methods were introduced on the effect)
- **Diary 027** — Ability registry (same pattern applied)

# Diary 038: Separate Rendering from Behavior (Executing Diary 034's Plan)

**Date:** 2026-04-14
**Status:** Complete

## Context

Diary 034 planned this split but deferred "until a concrete trigger." The trigger I
named was external: second renderer, localization, HTML output.

Rereading during session reflection, I realized there's an *internal* trigger: every
new hook I add to `ItemEffect` or `AbilityEffect` pushes the interface closer to
unmanageable (`ItemEffect` has 10 methods; `AbilityEffect` has 12). 5 of ItemEffect's
methods and 2 of AbilityEffect's are pure rendering. Pulling them out:
- Drops ItemEffect back below the TooManyFunctions threshold
- Makes behavior-only effect files tighter
- Creates the seam for a future second renderer anyway

So the trigger is "diary 035's refactor momentum applied to the same complaint on a
sibling axis." Doing it now.

## Design

### New packages

```
render/item/
├── ItemText.kt            — interface, default no-op implementations
├── ItemTextRegistry.kt    — singleton map from Item → ItemText
├── LeftoversText.kt       — per-item text (one file per item that has custom strings)
├── FocusSashText.kt
├── LifeOrbText.kt
├── SitrusBerryText.kt
└── RedCardText.kt

render/ability/
├── AbilityText.kt         — interface with default "X's AbilityName!" fallback
├── AbilityTextRegistry.kt — singleton map
├── SturdyText.kt
└── EmergencyExitText.kt
```

Items without custom rendering (Choice Band/Specs/Scarf, Eviolite, Klutz) don't get
text files — the registry returns null and the renderer emits nothing. That's the
"avoid doubling file count for no reason" concession.

### Interfaces shrink

```kotlin
// Before: ItemEffect had renderHealing, renderConsumed, renderDamage
// After: no render methods on ItemEffect

interface ItemEffect {
    val item: Item
    // behavior hooks only
    fun attackerDamageModifier(...)
    fun defenderDamageModifier(...)
    fun interceptIncomingDamage(...)
    fun afterUserMoveDamage(...)
    fun endOfTurn(...)
    fun speedModifier(...)
    fun onHpThresholdCrossed(...)
    fun onHolderTookDamage(...)
}

// Rendering lives here:
interface ItemText {
    val item: Item
    fun renderHealing(amount: Int, pokemonName: String): String? = null
    fun renderConsumed(pokemonName: String): String? = null
    fun renderDamage(amount: Int, pokemonName: String): String? = null
}
```

Returning `String?` (null = no custom text) instead of empty string — clearer contract.

### TextRenderer consumes both registries

`ItemRegistry.effectFor(item)` for behavior lookups (damage calc, phase hooks).
`ItemTextRegistry.textFor(item)` for rendering. Same pattern for abilities.

TextRenderer falls back to the generic template for abilities without custom text
(`"X's AbilityName!"`, `"It doesn't affect X... (AbilityName)"`). For items, missing
text means empty output (matches current default).

### Gen-specific rendering seams

The split opens the door to gen-specific or locale-specific text registries without
touching behavior. `ItemTextRegistryEn`, `ItemTextRegistryJa`, `ItemTextRegistryPSS`
(Pokemon Showdown style)... future work, but the shape now allows it.

## Plan

### Step 1: ItemText interface + registry
- [x] `render/item/ItemText.kt`
- [x] `render/item/ItemTextRegistry.kt`

### Step 2: Per-item text files
- [x] LeftoversText, FocusSashText, LifeOrbText, SitrusBerryText, RedCardText
- [x] Register in ItemTextRegistry

### Step 3: AbilityText interface + registry
- [x] `render/ability/AbilityText.kt` (with generic fallback in interface default)
- [x] `render/ability/AbilityTextRegistry.kt`

### Step 4: Per-ability text files
- [x] SturdyText, EmergencyExitText (the two with custom strings)
- [x] Register in AbilityTextRegistry

### Step 5: Drop render methods from Effect interfaces
- [x] Remove `renderHealing/Consumed/Damage` from ItemEffect
- [x] Remove `renderTriggered/Blocked` from AbilityEffect
- [x] Remove per-effect render method overrides from all item/ability files
- [x] Drop suppressions made redundant by the shrink

### Step 6: Migrate TextRenderer
- [x] `renderItemHealing/Consumed/Damage` consult ItemTextRegistry
- [x] `renderAbilityTriggered/Blocked` consult AbilityTextRegistry with generic fallback
- [x] Drop the item/ability imports that are no longer used

### Step 7: Validate
- [x] All 211 tests pass unchanged
- [x] RendererTest still renders the same strings

## Tradeoffs

### Gained
- ItemEffect drops from 10 → 7 methods (below TooManyFunctions threshold, can drop suppression)
- AbilityEffect drops from 12 → 10 (still over but closer, might drop the suppression)
- Behavior files are tighter — no render strings buried in them
- Clean seam for future renderers (JSON, HTML, localized text)

### Cost
- 7 new files (one per item/ability with custom text)
- Two parallel registries to keep in sync
- Rendering-only change means a new team member has to look in two places for "what
  does Life Orb do"

On net worth it because: each file stays under ~30 lines, the split is mechanical, and
the renderer-independence benefit is real even without a second renderer (localization
is a near-future possibility as the project gets polish).

## Related

- **Diary 034** — the deferred plan this executes
- **Diary 035** — sibling refactor (signature cleanup); same spirit of "fix the rough
  edges the registry pattern introduced while they're still cheap"

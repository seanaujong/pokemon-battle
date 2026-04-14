# Diary 033: Cross-Registry Interactions — Klutz as the First Test Case

**Date:** 2026-04-13
**Status:** Complete

## Goal

Establish the pattern for **one registry querying another** to resolve cross-cutting
effects. Concrete example: **Klutz** — an ability that prevents the holder from using its
held item.

If a Charizard holds Life Orb and has Klutz:
- No 1.3× damage boost (the item is inert)
- No 10% recoil after damage (the item is inert)

This is the first place where the `AbilityRegistry` and `ItemRegistry` need to know
about each other. Getting the shape right here unlocks future patterns (Embargo — volatile
that suppresses items; Magic Room — field effect that suppresses all items; Mummy/Neutralizing Gas — abilities that suppress other abilities).

## Why this matters

Each registry in isolation is clean. But real Pokemon mechanics have many
"X suppresses Y" interactions:

| Mechanic | Kind | Effect |
|----------|------|--------|
| Klutz | ability | Holder's item is inert |
| Embargo | volatile (move-applied) | Target's item is inert for 5 turns |
| Magic Room | field effect | All items are inert for 5 turns |
| Unnerve | ability | Opposing team's berries can't trigger |
| Neutralizing Gas | ability | All other abilities are suppressed while on field |
| Mummy / Wandering Spirit | ability | Contact attacker's ability becomes Mummy |

If every registry naively consults only its own enum value, these interactions require
scattered branching inside every caller. The pattern we want: **the lookup itself is
context-aware — it knows about suppression and returns null when the entity is inert.**

## Design

### Current shape

```kotlin
// Callers today:
ItemRegistry.effectFor(attacker.item)?.attackerDamageModifier(attacker, move)
```

`effectFor(Item)` just does a map lookup. Doesn't know about abilities, volatiles, or
field effects.

### Proposed shape

```kotlin
// Callers after this diary:
ItemRegistry.effectForHolder(attacker)?.attackerDamageModifier(attacker, move)
```

`effectForHolder(PokemonState)` does:
1. Look up the item's effect (as before)
2. Check if the item is *suppressed* for this holder — ability suppression (Klutz), field
   suppression (Magic Room), volatile suppression (Embargo)
3. Return the effect only if it's active; null if suppressed

Low-level `effectFor(Item)` stays available for rendering (we still want "X's Life Orb was
consumed" to render even if the event fired before Klutz took hold — but in practice those
are mutually exclusive since Klutz prevents the event emission in the first place).

### `AbilityEffect.suppressesHeldItem`

```kotlin
interface AbilityEffect {
    // ... existing hooks
    fun suppressesHeldItem(holder: PokemonState): Boolean = false
}

object KlutzEffect : AbilityEffect {
    override val ability = Ability.KLUTZ
    override fun suppressesHeldItem(holder: PokemonState) = true
}
```

The hook is on `AbilityEffect` because *the ability knows why it suppresses items*. Klutz
suppresses unconditionally. A future ability might suppress only specific item kinds
(hypothetical); the signature allows that.

### `ItemRegistry.effectForHolder`

```kotlin
object ItemRegistry {
    fun effectFor(item: Item?): ItemEffect? = ...

    fun effectForHolder(holder: PokemonState): ItemEffect? {
        val effect = effectFor(holder.item) ?: return null
        if (isItemSuppressedFor(holder)) return null
        return effect
    }

    private fun isItemSuppressedFor(holder: PokemonState): Boolean {
        val abilityEffect = AbilityRegistry.effectFor(holder.ability)
        return abilityEffect?.suppressesHeldItem(holder) == true
    }
}
```

`ItemRegistry` now depends on `AbilityRegistry` — same package, one-directional coupling.
That's fine; the packages grew together for exactly this reason.

Future extension: when we add volatiles that suppress items (Embargo), `isItemSuppressedFor`
also checks `holder.volatiles`. When we add field effects (Magic Room), it takes a
`FieldState` parameter. The `effectForHolder` API absorbs the complexity; callers don't
change.

### Callers to update

Every site that currently calls `ItemRegistry.effectFor(pokemon.item)` with intent to
query *active* behavior switches to `effectForHolder(pokemon)`:

- `GenVDamageCalculator` — attacker + defender item modifiers
- `MoveExecutionPhase.resolveDamage` — Focus Sash intercept
- `MoveExecutionPhase.resolveAttackerItemEffects` — Life Orb recoil
- `EndOfTurnPhase.itemEffects` — Leftovers healing
- `SimplifiedEndOfTurnPhase.itemEffects` — Leftovers healing

Rendering sites (TextRenderer's renderItemHealing/Consumed/Damage) stay on `effectFor` —
render text is about the event that already fired, not whether the item would fire now.

## Plan

### Step 1: Add `Ability.KLUTZ`
- [x] Enum entry

### Step 2: Add `suppressesHeldItem` hook to `AbilityEffect`
- [x] Default returns false

### Step 3: `KlutzEffect`
- [x] New file; overrides `suppressesHeldItem` to return true
- [x] Register in `AbilityRegistry`

### Step 4: `ItemRegistry.effectForHolder`
- [x] New method; consults `AbilityRegistry` for suppression
- [x] Keep `effectFor(Item?)` for rendering/raw lookup

### Step 5: Update callers
- [x] `GenVDamageCalculator` — attacker and defender item modifier lookups
- [x] `MoveExecutionPhase.resolveDamage` — Focus Sash intercept
- [x] `MoveExecutionPhase.resolveAttackerItemEffects` — Life Orb recoil
- [x] `EndOfTurnPhase.itemEffects` — Leftovers healing
- [x] `SimplifiedEndOfTurnPhase.itemEffects`

### Step 6: Tests
- [x] Life Orb + Klutz: no damage boost
- [x] Life Orb + Klutz: no recoil
- [x] Focus Sash + Klutz: doesn't survive a KO hit
- [x] Leftovers + Klutz: no end-of-turn healing
- [x] Sanity: Life Orb without Klutz still works (regression check)

## Success criteria

- Klutz holders can hold items but the items don't trigger
- No scattered "if ability == KLUTZ" branches in callers — the `effectForHolder` API
  absorbs the concern
- All 163 existing tests still pass (behavior unchanged for non-Klutz holders)
- ~5 new tests covering Klutz interactions

## What this unlocks

- **Embargo move** — would add volatile suppression to `isItemSuppressedFor`
- **Magic Room move** — would add field-effect suppression (requires `FieldState` access;
  hook signature grows to `isItemSuppressedFor(holder, field)`)
- **Neutralizing Gas ability** — needs its own similar "ability-suppresses-ability" pattern;
  `AbilityRegistry.effectForHolder` mirror of this design
- **Cross-registry in general** — any future "X suppresses Y" gets the same shape

## Design principle to capture

**Registries should be context-aware at the query level.** The lookup itself answers "is
this effect active right now?" rather than making every caller re-derive context. When
the context grows (field effects, volatiles, cross-ability suppression), only the lookup
changes — not the callers.

This will go into `architecture.md` Lessons Learned after implementation.

## Related diaries

- **Diary 026** — Item registry (the registry we're extending)
- **Diary 027** — Ability registry (the registry we're extending)
- **Diary 030** — Architectural twists (cross-registry flagged implicitly in multiple
  twists)

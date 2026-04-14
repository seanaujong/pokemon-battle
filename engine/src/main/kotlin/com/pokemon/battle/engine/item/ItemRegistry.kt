package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.PokemonState

/**
 * Maps each [Item] to its [ItemEffect]. Phases consult this registry instead of
 * switching on the [Item] enum directly. Diary 071 converted this from a global
 * `object` singleton to an injectable class; the constructor takes an
 * [AbilityRegistry] because Klutz / Embargo / Unnerve suppression is checked at
 * lookup time.
 *
 * Gen-specific registries are `ItemRegistry(listOf(...), abilityRegistry)` with a
 * different effect set. See [com.pokemon.battle.data.GenVRegistries] for the
 * canonical Gen V bundle.
 */
class ItemRegistry(
    effects: List<ItemEffect>,
    private val abilities: AbilityRegistry,
) {
    private val byItem: Map<Item, ItemEffect> = effects.associateBy { it.item }

    /** Raw lookup — returns the effect regardless of suppression context. Use for rendering. */
    fun effectFor(item: Item?): ItemEffect? = item?.let { byItem[it] }

    /**
     * Context-aware lookup: returns the item's effect only if it's active for this holder.
     * Returns null if the holder's ability suppresses the item (Klutz today; Embargo /
     * Magic Room / Unnerve in future).
     *
     * Prefer this over [effectFor] for active-behavior queries (damage calc, post-damage
     * hooks, end-of-turn). Use [effectFor] for rendering an event that already fired.
     */
    fun effectForHolder(holder: PokemonState): ItemEffect? {
        val effect = effectFor(holder.item) ?: return null
        if (isItemSuppressedFor(holder)) return null
        return effect
    }

    private fun isItemSuppressedFor(holder: PokemonState): Boolean =
        abilities.effectFor(holder.effectiveAbility)?.suppressesHeldItem(holder) == true
}

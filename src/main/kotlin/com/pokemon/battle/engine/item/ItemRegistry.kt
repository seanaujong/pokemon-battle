package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.PokemonState

/**
 * Maps each [Item] to its [ItemEffect]. The calc, phases, and renderer consult this
 * registry instead of switching on the [Item] enum directly.
 *
 * A gen-specific variant (GenVItemRegistry, GenIVItemRegistry) would register a subset
 * of items — e.g. Gen 3 wouldn't include [Item.LIFE_ORB]. For now we ship one registry
 * since all our items are Gen 4+.
 */
object ItemRegistry {
    private val effects: Map<Item, ItemEffect> =
        listOf(
            LeftoversEffect,
            FocusSashEffect,
            LifeOrbEffect,
            ChoiceBandEffect,
            ChoiceSpecsEffect,
            ChoiceScarfEffect,
            EvioliteEffect,
            SitrusBerryEffect,
            RedCardEffect,
        ).associateBy { it.item }

    /** Raw lookup — returns the effect regardless of suppression context. Use for rendering. */
    fun effectFor(item: Item?): ItemEffect? = item?.let { effects[it] }

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
        AbilityRegistry.effectFor(holder.ability)?.suppressesHeldItem(holder) == true
}

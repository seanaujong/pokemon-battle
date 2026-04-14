package com.pokemon.battle.engine.item

import com.pokemon.battle.model.Item

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
        ).associateBy { it.item }

    fun effectFor(item: Item?): ItemEffect? = item?.let { effects[it] }
}

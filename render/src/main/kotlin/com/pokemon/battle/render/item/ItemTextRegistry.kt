package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

/**
 * Rendering-only parallel of [com.pokemon.battle.engine.item.ItemRegistry]. Swapping
 * this registry (e.g. for localization or a JSON renderer) doesn't touch behavior.
 *
 * Items without an entry here render nothing for the corresponding event types — which
 * is fine for items like Choice Scarf that never emit user-visible item events
 * (their boost/speed effect shows up as damage/order, not via item events).
 */
object ItemTextRegistry {
    private val texts: Map<Item, ItemText> =
        listOf(
            LeftoversText,
            FocusSashText,
            LifeOrbText,
            SitrusBerryText,
            RedCardText,
            RockyHelmetText,
        ).associateBy { it.item }

    fun textFor(item: Item?): ItemText? = item?.let { texts[it] }
}

package com.pokemon.battle.engine

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemRegistry

/**
 * Bundle of the two registries phases consult at runtime. Passing one parameter
 * instead of two keeps phase constructors readable and makes it trivial to add a
 * third registry (move-behavior, diary 029) when that work lands.
 *
 * Diary 071 introduced this type. The concrete per-item / per-ability effect
 * classes live in `:data`, as does the canonical [com.pokemon.battle.data.GenVRegistries]
 * bundle. This type and its [empty] default live in `:engine` because phases and
 * resolvers depend on them — putting them in `:data` would invert the module
 * dependency.
 */
data class Registries(
    val items: ItemRegistry,
    val abilities: AbilityRegistry,
) {
    companion object {
        /**
         * An empty registry pair — no items or abilities registered. Useful for tests
         * of mechanics that don't involve items or abilities, where constructing the
         * Gen V bundle would be noise. Pokemon without items or abilities still work
         * against this.
         */
        val empty: Registries =
            run {
                val abilities = AbilityRegistry(emptyList())
                Registries(items = ItemRegistry(emptyList(), abilities), abilities = abilities)
            }
    }
}

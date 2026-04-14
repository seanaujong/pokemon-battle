package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

object LeftoversText : ItemText {
    override val item = Item.LEFTOVERS

    override fun renderHealing(
        amount: Int,
        pokemonName: String,
    ): String = "$pokemonName restored a little HP using its Leftovers!"
}

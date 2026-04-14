package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

object RedCardText : ItemText {
    override val item = Item.RED_CARD

    override fun renderConsumed(pokemonName: String): String = "$pokemonName held up a Red Card!"
}

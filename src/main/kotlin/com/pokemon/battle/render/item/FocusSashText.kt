package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

object FocusSashText : ItemText {
    override val item = Item.FOCUS_SASH

    override fun renderConsumed(pokemonName: String): String = "$pokemonName hung on using its Focus Sash!"
}

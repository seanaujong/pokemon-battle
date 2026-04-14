package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

object SitrusBerryText : ItemText {
    override val item = Item.SITRUS_BERRY

    override fun renderHealing(
        amount: Int,
        pokemonName: String,
    ): String = "$pokemonName ate its Sitrus Berry and restored HP!"
    // renderConsumed intentionally null — the healing line is the user-visible signal.
}

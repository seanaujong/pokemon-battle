package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

object RockyHelmetText : ItemText {
    override val item = Item.ROCKY_HELMET

    override fun renderDamage(
        amount: Int,
        pokemonName: String,
    ): String = "$pokemonName was hurt by the Rocky Helmet!"
}

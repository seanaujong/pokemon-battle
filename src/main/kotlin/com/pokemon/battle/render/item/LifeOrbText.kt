package com.pokemon.battle.render.item

import com.pokemon.battle.model.Item

object LifeOrbText : ItemText {
    override val item = Item.LIFE_ORB

    override fun renderDamage(
        amount: Int,
        pokemonName: String,
    ): String = "$pokemonName was hurt by its Life Orb!"
}

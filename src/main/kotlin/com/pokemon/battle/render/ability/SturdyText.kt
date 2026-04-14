package com.pokemon.battle.render.ability

import com.pokemon.battle.model.Ability

object SturdyText : AbilityText {
    override val ability = Ability.STURDY

    override fun renderTriggered(pokemonName: String): String = "$pokemonName endured the hit with Sturdy!"
}

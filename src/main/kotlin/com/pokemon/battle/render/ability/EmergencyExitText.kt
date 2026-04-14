package com.pokemon.battle.render.ability

import com.pokemon.battle.model.Ability

object EmergencyExitText : AbilityText {
    override val ability = Ability.EMERGENCY_EXIT

    override fun renderTriggered(pokemonName: String): String = "$pokemonName is retreating with Emergency Exit!"
}

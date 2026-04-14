package com.pokemon.battle.data.ability

import com.pokemon.battle.engine.ability.AbilityEffect
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.PokemonState

/** Klutz: the holder's held item is inert. */
object KlutzEffect : AbilityEffect {
    override val ability = Ability.KLUTZ

    override fun suppressesHeldItem(holder: PokemonState): Boolean = true
}

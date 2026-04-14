package com.pokemon.battle.engine

import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemRegistry
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.StatusCondition

fun interface SpeedResolver {
    fun effectiveSpeed(pokemon: PokemonState): Double
}

/**
 * Gen V+ speed = base * stage * paralysis-penalty * item-mod * ability-mod.
 * Item modifier covers Choice Scarf (1.5x), Iron Ball (0.5x), etc.
 * Ability modifier covers Swift Swim, Sand Rush, Chlorophyll, etc. (currently all return 1.0).
 */
val GenVSpeedResolver =
    SpeedResolver { pokemon ->
        val base = pokemon.baseEffectiveSpeed()
        val paralysisMod = if (pokemon.status == StatusCondition.PARALYSIS) 0.5 else 1.0
        val itemMod = ItemRegistry.effectForHolder(pokemon)?.speedModifier(pokemon) ?: 1.0
        val abilityMod = AbilityRegistry.effectFor(pokemon.ability)?.speedModifier(pokemon) ?: 1.0
        base * paralysisMod * itemMod * abilityMod
    }

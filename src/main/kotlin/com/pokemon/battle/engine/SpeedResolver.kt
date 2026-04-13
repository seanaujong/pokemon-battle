package com.pokemon.battle.engine

import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.StatusCondition

fun interface SpeedResolver {
    fun effectiveSpeed(pokemon: PokemonState): Double
}

/** Gen V+ speed: base * stage * paralysis (0.5x). Future: Choice Scarf, Swift Swim, etc. */
val GenVSpeedResolver =
    SpeedResolver { pokemon ->
        val base = pokemon.baseEffectiveSpeed()
        if (pokemon.status == StatusCondition.PARALYSIS) base * 0.5 else base
    }

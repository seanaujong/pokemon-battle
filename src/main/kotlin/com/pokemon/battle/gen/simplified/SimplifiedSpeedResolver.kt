package com.pokemon.battle.gen.simplified

import com.pokemon.battle.engine.SpeedResolver
import com.pokemon.battle.model.PokemonState

/**
 * Simplified speed: no paralysis modifier. Paralyzed Pokemon moves at full speed.
 */
val SimplifiedSpeedResolver =
    SpeedResolver { pokemon: PokemonState ->
        pokemon.baseEffectiveSpeed()
    }

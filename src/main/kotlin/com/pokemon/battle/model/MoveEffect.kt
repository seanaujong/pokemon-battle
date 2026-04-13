package com.pokemon.battle.model

sealed interface MoveEffect {
    data class StatBoost(val stat: StatType, val stages: Int) : MoveEffect

    data class SetVolatile(val volatile: Volatile) : MoveEffect

    /** Attacker switches out after a successful move (U-turn, Volt Switch). */
    data object SelfSwitch : MoveEffect
}

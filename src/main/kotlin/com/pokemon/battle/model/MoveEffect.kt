package com.pokemon.battle.model

sealed interface MoveEffect {
    data class StatBoost(val stat: StatType, val stages: Int) : MoveEffect
}

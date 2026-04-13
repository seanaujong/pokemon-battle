package com.pokemon.battle

sealed interface Volatile {
    data object Flinch : Volatile
    data class Confusion(val turnsRemaining: Int) : Volatile
    data object Protect : Volatile
}

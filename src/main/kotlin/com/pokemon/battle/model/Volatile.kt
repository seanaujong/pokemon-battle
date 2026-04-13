package com.pokemon.battle.model

sealed interface Volatile {
    data object Flinch : Volatile

    data class Confusion(val turnsRemaining: Int) : Volatile

    data class Sleep(val turnsRemaining: Int) : Volatile

    data object Protect : Volatile

    /** Consecutive uses of a protection move (Protect, Detect, …). Drives diminishing success. */
    data class ProtectCounter(val consecutive: Int) : Volatile
}

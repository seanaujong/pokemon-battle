package com.pokemon.battle.model

sealed interface Volatile {
    data object Flinch : Volatile

    data class Confusion(val turnsRemaining: Int) : Volatile

    data class Sleep(val turnsRemaining: Int) : Volatile

    data object Protect : Volatile

    /** Consecutive uses of a protection move (Protect, Detect, …). Drives diminishing success. */
    data class ProtectCounter(val consecutive: Int) : Volatile

    /**
     * Move lock from a Choice item (Band, Specs, Scarf). Cleared on switch by
     * [com.pokemon.battle.engine.resolveSwitchOutClearing] along with other volatiles.
     * Enforcement is a choice-layer concern (AI/UI should restrict move selection);
     * the engine emits the volatile so choice layers can read it.
     */
    data class ChoiceLocked(val move: Move) : Volatile

    /**
     * Set when a Pokemon switches in (voluntary, forced, or faint replacement). Consumed /
     * cleared at the start of the Pokemon's next acting turn (or at end of turn if they
     * don't act). Gate for Fake Out, First Impression, Mat Block.
     */
    data object JustSwitchedIn : Volatile
}

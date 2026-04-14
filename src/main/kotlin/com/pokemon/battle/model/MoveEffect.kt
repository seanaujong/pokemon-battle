package com.pokemon.battle.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface MoveEffect {
    @Serializable
    data class StatBoost(val stat: StatType, val stages: Int) : MoveEffect

    @Serializable
    data class SetVolatile(val volatile: Volatile) : MoveEffect

    /** Attacker switches out after a successful move (U-turn, Volt Switch). */
    @Serializable
    data object SelfSwitch : MoveEffect

    /** Set Trick Room on the field for [turns] turns. Re-using while active clears it (toggle). */
    @Serializable
    data class SetTrickRoom(val turns: Int) : MoveEffect

    /** Set a [SideCondition] on the user's side for [turns] turns (Tailwind, future Reflect/Light Screen). */
    @Serializable
    data class SetSideConditionOnUserSide(val condition: SideCondition, val turns: Int) : MoveEffect

    /**
     * Set or increment a [SideHazard] layer on the opposing side. Layer increments by 1
     * each cast up to [maxLayers]; using the move while already at max fails silently
     * (we emit nothing). Stealth Rock / Sticky Web use `maxLayers = 1`; Spikes = 3;
     * Toxic Spikes = 2.
     */
    @Serializable
    data class SetHazardOnOpposingSide(val hazard: SideHazard, val maxLayers: Int) : MoveEffect

    /**
     * Clear all entry hazards from the user's own side (Rapid Spin, Defog — user-side
     * removal scope only; Defog's opponent-side / terrain / evasion effects are deferred).
     * Emits one [com.pokemon.battle.engine.HazardRemoved] event per hazard present.
     */
    @Serializable
    data object ClearHazardsOnUserSide : MoveEffect

    /**
     * Raise or lower the user's own stat by [stages], regardless of the move's target.
     * Used by damaging moves whose secondary effect is self-targeted (Rapid Spin +1
     * Speed in Gen 8+), where the move's primary target is the opponent.
     */
    @Serializable
    data class UserStatBoost(val stat: StatType, val stages: Int) : MoveEffect
}

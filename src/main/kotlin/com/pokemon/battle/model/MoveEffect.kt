package com.pokemon.battle.model

sealed interface MoveEffect {
    data class StatBoost(val stat: StatType, val stages: Int) : MoveEffect

    data class SetVolatile(val volatile: Volatile) : MoveEffect

    /** Attacker switches out after a successful move (U-turn, Volt Switch). */
    data object SelfSwitch : MoveEffect

    /** Set Trick Room on the field for [turns] turns. Re-using while active clears it (toggle). */
    data class SetTrickRoom(val turns: Int) : MoveEffect

    /** Set a [SideCondition] on the user's side for [turns] turns (Tailwind, future Reflect/Light Screen). */
    data class SetSideConditionOnUserSide(val condition: SideCondition, val turns: Int) : MoveEffect
}

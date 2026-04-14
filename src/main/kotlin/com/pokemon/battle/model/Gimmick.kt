package com.pokemon.battle.model

/**
 * One-shot battle-long transformations introduced over the gens. Each is a separate slot
 * in the budget that [com.pokemon.battle.engine.Ruleset] decides legality over. The enum
 * is pure identity — mechanics (stat swap, movepool replace, type override, etc.) live
 * in future gimmick-specific code.
 */
enum class GimmickKind {
    MEGA,
    Z_MOVE,
    DYNAMAX,
    TERA,
}

/** Raw record of a gimmick activation. Stored on [com.pokemon.battle.engine.BattleState]. */
data class UsedGimmick(
    val kind: GimmickKind,
    val slot: Slot,
    val turn: Int,
)

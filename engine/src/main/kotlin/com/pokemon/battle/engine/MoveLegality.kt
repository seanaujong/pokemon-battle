package com.pokemon.battle.engine

import com.pokemon.battle.model.FailReason

/**
 * Result of [Ruleset.canUseMove]. Either the move is [Allowed], or it is [Forbidden]
 * with a specific [FailReason] the engine surfaces via [MoveFailed].
 *
 * See diary 039 for the design. The engine enforces legality at execution time; choice
 * layers (AI/UI) can mirror the check via [BattleState.validMovesFor] to avoid even
 * offering an illegal option.
 */
sealed interface MoveLegality {
    data object Allowed : MoveLegality

    data class Forbidden(val reason: FailReason) : MoveLegality
}

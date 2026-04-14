package com.pokemon.battle.engine

import com.pokemon.battle.model.Slot
import kotlinx.serialization.Serializable

/**
 * A mid-turn prompt the engine is waiting on. See diary 055.
 *
 * Phase 2 (diary 055): first concrete variant — [SwitchTargetRequest] for self-switch
 * moves (U-turn, Volt Switch). More variants follow as mechanics arrive that need
 * mid-turn prompts (Baton Pass, Revival Blessing, etc.).
 */
@Serializable
sealed interface InputRequest

/**
 * Asks the caller to pick a bench replacement after a self-switch move landed damage.
 * The caller answers with a [SwitchTargetResponse] whose [SwitchTargetResponse.benchIndex]
 * is one of [eligibleBenchIndices].
 */
@Serializable
data class SwitchTargetRequest(
    val userSlot: Slot,
    val reason: SwitchReason,
    val eligibleBenchIndices: List<Int>,
) : InputRequest

@Serializable
enum class SwitchReason {
    /** The user's move (U-turn, Volt Switch, Parting Shot...) triggers a self-switch on hit. */
    SELF_SWITCH_MOVE,
}

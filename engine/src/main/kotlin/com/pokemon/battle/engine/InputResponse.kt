package com.pokemon.battle.engine

/**
 * A caller's answer to an [InputRequest]. See diary 055.
 *
 * Phase 2 (diary 055): first concrete variant — [SwitchTargetResponse].
 */
sealed interface InputResponse

/** Caller's chosen bench index in response to a [SwitchTargetRequest]. */
data class SwitchTargetResponse(
    val benchIndex: Int,
) : InputResponse

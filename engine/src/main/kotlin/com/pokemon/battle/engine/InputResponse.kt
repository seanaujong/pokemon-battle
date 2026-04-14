package com.pokemon.battle.engine

import kotlinx.serialization.Serializable

/**
 * A caller's answer to an [InputRequest]. See diary 055.
 *
 * Phase 2 (diary 055): first concrete variant — [SwitchTargetResponse].
 */
@Serializable
sealed interface InputResponse

/** Caller's chosen bench index in response to a [SwitchTargetRequest]. */
@Serializable
data class SwitchTargetResponse(
    val benchIndex: Int,
) : InputResponse

package com.pokemon.battle.engine

/**
 * A caller's answer to an [InputRequest]. See diary 055.
 *
 * Phase 1 has no variants. Phase 2 adds `SwitchTargetResponse` to match
 * `SwitchTargetRequest`.
 */
sealed interface InputResponse

package com.pokemon.battle.engine

/**
 * A mid-turn prompt the engine is waiting on. See diary 055.
 *
 * Phase 1 defines the hierarchy with no concrete variants yet — no phase emits
 * a pause. Phase 2 will introduce `SwitchTargetRequest` as the first consumer
 * (for U-turn and friends).
 */
sealed interface InputRequest

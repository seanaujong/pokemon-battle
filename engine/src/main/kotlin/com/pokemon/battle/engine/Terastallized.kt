package com.pokemon.battle.engine

import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.UsedGimmick

/**
 * A Pokemon Terastallized — sets its effective type to [com.pokemon.battle.model.Pokemon.teraType]
 * and marks the flag that drives the Tera STAB rule.
 *
 * Separate from [GimmickUsed] because Tera has *mechanics* (type change + STAB rule)
 * beyond pure bookkeeping, and keeping those mechanics in the event's `apply` is the
 * event-sourcing shape the engine already uses.
 *
 * Legality is gated by [BattleState.canUseGimmick] upstream in
 * [com.pokemon.battle.phase.MoveExecutionPhase]; by the time this event is emitted
 * the Ruleset has said yes.
 */
data class Terastallized(val slot: Slot) : GameEvent {
    override fun apply(state: BattleState): BattleState {
        val pokemon = state.pokemonFor(slot)
        val teraType = pokemon.pokemon.teraType ?: error("Tera activation on $slot but teraType is null")
        val updated = pokemon.copy(terastallized = true, typeOverride = listOf(teraType))
        return state
            .withPokemon(slot, updated)
            .withGimmickUsed(UsedGimmick(GimmickKind.TERA, slot, turn = state.turn))
    }
}

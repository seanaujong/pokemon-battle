package com.pokemon.battle.engine

import com.pokemon.battle.model.GimmickKind
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.UsedGimmick
import kotlinx.serialization.Serializable

/**
 * A Pokemon activated a battle gimmick (Mega, Z, Dynamax, Tera). Records the event on
 * the state's gimmick history. Gimmick-specific mechanics (stat swap, type override,
 * movepool replace) are additional events emitted alongside or after this one —
 * a future-diary concern; this event is purely bookkeeping.
 */
@Serializable
data class GimmickUsed(
    val kind: GimmickKind,
    val slot: Slot,
) : BattleEvent {
    override fun apply(state: BattleState): BattleState = state.withGimmickUsed(UsedGimmick(kind, slot, turn = state.turn))
}

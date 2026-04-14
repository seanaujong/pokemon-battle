package com.pokemon.battle.data.item

import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.GameEvent
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.ability.AbilityRegistry
import com.pokemon.battle.engine.item.ItemEffect
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType

/**
 * Weakness Policy: when the holder is hit by a super-effective damaging move, the
 * holder's Attack and Special Attack each rise by 2 stages, and the Policy is
 * consumed.
 *
 * v1 choices:
 * - Only fires on [Effectiveness.SUPER_EFFECTIVE]. Neutral / not-very-effective
 *   hits are ignored.
 * - Does not fire if [damageDealt] is zero (Substitute / 0-roll edge cases aside —
 *   at 0 damage we treat it as "no hit connected"). Real cartridge behaviour fires
 *   after the attack connects *and* deals damage, which matches this rule for
 *   mainline play.
 * - Stat changes are emitted even if the holder is at max stages already; the
 *   [StatChanged] event's `apply` clamps internally.
 *
 * FIDELITY NOTE: Weakness Policy does *not* check whether the holder has already
 * fainted from the triggering hit — that gate lives one layer up in
 * [com.pokemon.battle.phase.MoveExecutionPhase.onHitEvents], which skips hooks
 * when the defender is fainted.
 */
object WeaknessPolicyEffect : ItemEffect {
    override val item = Item.WEAKNESS_POLICY

    override fun onHolderTookDamage(
        state: BattleState,
        holderSlot: Slot,
        attackerSlot: Slot,
        damageDealt: Int,
        effectiveness: Effectiveness,
        abilities: AbilityRegistry,
    ): List<GameEvent> {
        if (damageDealt <= 0) return emptyList()
        if (effectiveness != Effectiveness.SUPER_EFFECTIVE) return emptyList()
        return listOf(
            StatChanged(holderSlot, StatType.ATTACK, BOOST_STAGES),
            StatChanged(holderSlot, StatType.SPECIAL_ATTACK, BOOST_STAGES),
            ItemConsumed(holderSlot, Item.WEAKNESS_POLICY),
        )
    }

    private const val BOOST_STAGES = 2
}

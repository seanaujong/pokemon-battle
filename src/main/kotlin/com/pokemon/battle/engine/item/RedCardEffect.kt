package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.resolveSwitchInAbility
import com.pokemon.battle.engine.resolveSwitchOutClearing
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot

/**
 * Red Card: when the holder is hit by a damaging move, force the attacker to switch out.
 * The card is consumed. If the attacker has no valid bench replacement, the card does
 * nothing (Red Card fails silently; in real games the card isn't consumed either —
 * we match that behavior).
 *
 * Limitation: the engine picks the first available bench Pokemon for the attacker.
 * Real games pick randomly (actually the opposing player doesn't choose either — it
 * really is random). First-available is a pragmatic stand-in.
 */
object RedCardEffect : ItemEffect {
    override val item = Item.RED_CARD

    override fun onHolderTookDamage(
        holder: PokemonState,
        holderSlot: Slot,
        attacker: PokemonState,
        attackerSlot: Slot,
        state: BattleState,
        damageDealt: Int,
    ): List<BattleEvent> {
        if (damageDealt <= 0) return emptyList()
        if (attacker.isFainted) return emptyList()

        val attackerBench = state.benchFor(attackerSlot.side)
        val replacementIndex = attackerBench.indexOfFirst { !it.isFainted }
        if (replacementIndex < 0) return emptyList()

        val events = mutableListOf<BattleEvent>()
        events.add(ItemConsumed(holderSlot, Item.RED_CARD))
        events.addAll(resolveSwitchOutClearing(state, attackerSlot))
        events.add(SwitchOut(attackerSlot))
        events.add(SwitchIn(attackerSlot, replacementIndex))

        val stateAfterSwitch = events.fold(state) { s, e -> e.apply(s) }
        events.addAll(resolveSwitchInAbility(stateAfterSwitch, attackerSlot))

        return events
    }

    override fun renderConsumed(pokemonName: String): String = "$pokemonName held up a Red Card!"
}

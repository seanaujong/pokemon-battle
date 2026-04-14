package com.pokemon.battle.engine.item

import com.pokemon.battle.engine.BattleEvent
import com.pokemon.battle.engine.VolatileAdded
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Move
import com.pokemon.battle.model.MoveCategory
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Volatile

/**
 * Shared behavior for Choice items (Band, Specs, Scarf):
 * - [damageBoostCategory] != null: boost damage by 1.5× for moves of that category
 * - [speedBoost]: multiplier on the holder's speed (1.5× for Scarf, 1.0 for Band/Specs)
 * - After the holder uses a damaging move, emit [Volatile.ChoiceLocked] so choice layers
 *   (AI, UI) restrict future turns to that move. Enforcement is a choice-layer concern;
 *   the engine just publishes the state.
 *
 * The lock is cleared on switch-out via the generic volatile clearing in [SwitchPhase].
 */
private class ChoiceItem(
    override val item: Item,
    private val damageBoostCategory: MoveCategory? = null,
    private val speedBoost: Double = 1.0,
) : ItemEffect {
    override fun attackerDamageModifier(
        attacker: PokemonState,
        move: Move,
    ): Double =
        if (damageBoostCategory != null && move.category == damageBoostCategory) {
            CHOICE_DAMAGE_MULTIPLIER
        } else {
            1.0
        }

    override fun speedModifier(holder: PokemonState): Double = speedBoost

    override fun afterUserMoveDamage(
        user: PokemonState,
        userSlot: Slot,
        move: Move,
        damageLanded: Boolean,
    ): List<BattleEvent> {
        // Only lock if the move actually landed damage. Real games lock on attempted use
        // including misses — we defer that until a hook for "after any attempted move"
        // exists (would be cleaner than piggybacking on damageLanded).
        if (!damageLanded) return emptyList()
        if (user.volatiles.any { it is Volatile.ChoiceLocked }) return emptyList()
        return listOf(VolatileAdded(userSlot, Volatile.ChoiceLocked(move)))
    }

    companion object {
        private const val CHOICE_DAMAGE_MULTIPLIER = 1.5
    }
}

object ChoiceBandEffect : ItemEffect by ChoiceItem(
    item = Item.CHOICE_BAND,
    damageBoostCategory = MoveCategory.PHYSICAL,
)

object ChoiceSpecsEffect : ItemEffect by ChoiceItem(
    item = Item.CHOICE_SPECS,
    damageBoostCategory = MoveCategory.SPECIAL,
)

object ChoiceScarfEffect : ItemEffect by ChoiceItem(
    item = Item.CHOICE_SCARF,
    speedBoost = 1.5,
)

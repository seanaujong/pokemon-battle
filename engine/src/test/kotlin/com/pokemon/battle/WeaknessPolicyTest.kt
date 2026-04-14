package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeaknessPolicyTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(GenVRegistries),
                SwitchPhase(GenVRegistries),
                MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(GenVRegistries),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Weakness Policy fires on super-effective hit boosting Attack and SpAtk by 2 and consumes`() {
        // Flamethrower (Fire) vs Venusaur (Grass/Poison) is 2x super-effective.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.WEAKNESS_POLICY)
        val state = BattleState.singles(attacker, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val policyStatBoosts =
            result.events
                .filterIsInstance<StatChanged>()
                .filter { it.target == Slot.p2() && it.stages == 2 }
        val atkBoost = policyStatBoosts.singleOrNull { it.stat == StatType.ATTACK }
        val spaBoost = policyStatBoosts.singleOrNull { it.stat == StatType.SPECIAL_ATTACK }

        assertTrue(atkBoost != null, "Weakness Policy should emit a +2 Attack stage change on holder")
        assertTrue(spaBoost != null, "Weakness Policy should emit a +2 Special Attack stage change on holder")

        val consumed = result.events.filterIsInstance<ItemConsumed>().filter { it.item == Item.WEAKNESS_POLICY }
        assertEquals(1, consumed.size, "Weakness Policy should be consumed exactly once")
        assertEquals(Slot.p2(), consumed.single().target)
    }

    @Test
    fun `Weakness Policy does not fire on neutral hit`() {
        // Tackle (Normal) vs Venusaur (Grass/Poison) is neutral damage.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.WEAKNESS_POLICY)
        val state = BattleState.singles(attacker, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val consumed = result.events.filterIsInstance<ItemConsumed>().filter { it.item == Item.WEAKNESS_POLICY }
        assertTrue(consumed.isEmpty(), "Weakness Policy should not be consumed on a neutral hit")

        // Also no +2 boosts from the Policy on the holder this turn.
        val holderBoosts =
            result.events
                .filterIsInstance<StatChanged>()
                .filter { it.target == Slot.p2() && it.stages == 2 }
        assertTrue(holderBoosts.isEmpty(), "Weakness Policy should not emit stat boosts on a neutral hit")
    }

    @Test
    fun `Weakness Policy does not fire on immune hit`() {
        // Tackle (Normal) vs Gengar (Ghost/Poison): Normal → Ghost is 0x (immune).
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val gengar = Pokemon(pokedex["Gengar"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(gengar, currentHp = gengar.maxHp, item = Item.WEAKNESS_POLICY)
        val state = BattleState.singles(attacker, defender)

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.TACKLE),
                TurnChoice.UseMove(MoveDex.SHADOW_BALL),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val consumed = result.events.filterIsInstance<ItemConsumed>().filter { it.item == Item.WEAKNESS_POLICY }
        assertTrue(consumed.isEmpty(), "Weakness Policy should not fire on an immune (0 damage) hit")

        val holderBoosts =
            result.events
                .filterIsInstance<StatChanged>()
                .filter { it.target == Slot.p2() && it.stages == 2 }
        assertTrue(holderBoosts.isEmpty(), "Weakness Policy should not emit stat boosts on an immune hit")
    }
}

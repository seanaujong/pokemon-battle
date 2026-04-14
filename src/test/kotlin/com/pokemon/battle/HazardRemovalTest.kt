package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.HazardRemoved
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.SideHazard
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for hazard-removal moves: Rapid Spin and Defog.
 *
 * Scope note: we only implement the user-side hazard clear. Real Defog also lowers
 * opponent Evasion, clears opposing-side hazards, and removes terrain — deferred
 * per diary 044. These tests match the implemented scope.
 */
class HazardRemovalTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(),
            ),
        )

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    @Test
    fun `Rapid Spin clears hazards on user's side`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                sideHazards =
                    mapOf(
                        Side.SIDE_1 to
                            mapOf(
                                SideHazard.STEALTH_ROCK to 1,
                                SideHazard.SPIKES to 2,
                            ),
                    ),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.RAPID_SPIN),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        val removals =
            result.events.filterIsInstance<HazardRemoved>().filter { it.side == Side.SIDE_1 }
        assertEquals(2, removals.size, "Both hazards on user's side should be removed")
        assertTrue(removals.any { it.hazard == SideHazard.STEALTH_ROCK })
        assertTrue(removals.any { it.hazard == SideHazard.SPIKES })
        assertTrue(finalState.hazardsOn(Side.SIDE_1).isEmpty(), "User side should have no hazards left")
    }

    @Test
    fun `Rapid Spin raises user Speed by 1 stage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.RAPID_SPIN),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)

        val speedBoost =
            result.events.filterIsInstance<StatChanged>()
                .firstOrNull { it.target == Slot.p1() && it.stat == StatType.SPEED }
        assertTrue(speedBoost != null, "Rapid Spin should raise user's Speed")
        assertEquals(1, speedBoost!!.stages)
    }

    @Test
    fun `Defog clears hazards on user's side`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                sideHazards =
                    mapOf(
                        Side.SIDE_1 to
                            mapOf(
                                SideHazard.STICKY_WEB to 1,
                                SideHazard.TOXIC_SPIKES to 2,
                            ),
                    ),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.DEFOG),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        val removals =
            result.events.filterIsInstance<HazardRemoved>().filter { it.side == Side.SIDE_1 }
        assertEquals(2, removals.size)
        assertTrue(finalState.hazardsOn(Side.SIDE_1).isEmpty())
    }

    @Test
    fun `Rapid Spin does not clear hazards on opposing side`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                sideHazards =
                    mapOf(
                        Side.SIDE_1 to mapOf(SideHazard.STEALTH_ROCK to 1),
                        Side.SIDE_2 to mapOf(SideHazard.SPIKES to 2),
                    ),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.RAPID_SPIN),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        // User side cleared, opposing side intact
        assertTrue(finalState.hazardsOn(Side.SIDE_1).isEmpty())
        assertEquals(2, finalState.hazardsOn(Side.SIDE_2)[SideHazard.SPIKES])
        assertFalse(
            result.events.filterIsInstance<HazardRemoved>().any { it.side == Side.SIDE_2 },
            "No HazardRemoved events should target the opposing side",
        )
    }

    @Test
    fun `Rapid Spin with no hazards emits no HazardRemoved events but still boosts Speed`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.RAPID_SPIN),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolve(state, choices)

        assertTrue(result.events.filterIsInstance<HazardRemoved>().isEmpty())
        assertTrue(
            result.events.filterIsInstance<StatChanged>()
                .any { it.target == Slot.p1() && it.stat == StatType.SPEED && it.stages == 1 },
            "Speed boost should still fire with no hazards present",
        )
    }
}

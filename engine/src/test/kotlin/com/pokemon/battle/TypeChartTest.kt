package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.InverseTypeChart
import com.pokemon.battle.engine.StandardTypeChart
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Effectiveness
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Type
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeChartTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    // ============================================================
    // Mainline Pokemon mechanics — reachable in normal play
    // ============================================================

    // --- Standard chart sanity ---

    @Test
    fun `standard chart - Fire vs Water is not very effective`() {
        val multiplier = StandardTypeChart.effectiveness(Type.FIRE, listOf(Type.WATER))
        assertEquals(0.5, multiplier)
    }

    @Test
    fun `standard chart - Ground vs Flying is immune`() {
        val multiplier = StandardTypeChart.effectiveness(Type.GROUND, listOf(Type.FLYING))
        assertEquals(0.0, multiplier)
    }

    // ============================================================
    // Custom-format / extensibility — Inverse Battle is a Battle
    // Maison challenge format, not part of standard play. These
    // tests verify the engine supports swapping the type chart.
    // ============================================================

    // --- Inverse chart ---

    @Test
    fun `inverse chart - Fire vs Water is super effective`() {
        val multiplier = InverseTypeChart.effectiveness(Type.FIRE, listOf(Type.WATER))
        assertEquals(2.0, multiplier)
    }

    @Test
    fun `inverse chart - Water vs Fire is not very effective`() {
        val multiplier = InverseTypeChart.effectiveness(Type.WATER, listOf(Type.FIRE))
        assertEquals(0.5, multiplier)
    }

    @Test
    fun `inverse chart - Ground vs Flying is neutral (immunity flipped)`() {
        val multiplier = InverseTypeChart.effectiveness(Type.GROUND, listOf(Type.FLYING))
        assertEquals(1.0, multiplier)
    }

    @Test
    fun `inverse chart - neutral stays neutral`() {
        val multiplier = InverseTypeChart.effectiveness(Type.FIRE, listOf(Type.FIGHTING))
        assertEquals(1.0, multiplier)
    }

    // --- Inverse battle integration ---

    @Test
    fun `inverse damage calculator uses inverted effectiveness`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(blastoise, currentHp = blastoise.maxHp)

        val standardResult =
            GenVDamageCalculator(StandardTypeChart)
                .calculate(attacker, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null)
        val inverseResult =
            GenVDamageCalculator(InverseTypeChart)
                .calculate(attacker, defender, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null)

        // Standard: Fire vs Water = 0.5x (not very effective)
        assertEquals(Effectiveness.NOT_VERY_EFFECTIVE, standardResult.effectiveness)
        // Inverse: Fire vs Water = 2.0x (super effective)
        assertEquals(Effectiveness.SUPER_EFFECTIVE, inverseResult.effectiveness)

        assertTrue(
            inverseResult.damage > standardResult.damage,
            "Inverse should deal more damage (${inverseResult.damage} vs ${standardResult.damage})",
        )
    }

    @Test
    fun `full inverse battle turn with pipeline`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = blastoise.maxHp),
            )

        val inversePipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    SwitchPhase(),
                    MoveExecutionPhase(
                        damageCalculator = GenVDamageCalculator(InverseTypeChart),
                        roll = fixedRoll,
                        chanceCheck = noChance,
                    ),
                    EndOfTurnPhase(),
                ),
            )

        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = inversePipeline.resolveToCompletion(state, choices)
        val damageEvents = result.events.filterIsInstance<DamageDealt>()

        // Charizard's Flamethrower vs Blastoise: Fire vs Water = super effective in inverse
        val flameDamage = damageEvents.first { it.target == Slot.p2() }
        assertEquals(Effectiveness.SUPER_EFFECTIVE, flameDamage.effectiveness)

        // Blastoise's Ice Beam vs Charizard: Ice vs Fire/Flying
        // Standard: Ice vs Fire = 0.5x, Ice vs Flying = 2x → 1x neutral
        // Inverse: Ice vs Fire = 2x, Ice vs Flying = 0.5x → 1x neutral (still neutral!)
        val iceDamage = damageEvents.first { it.target == Slot.p1() }
        assertEquals(Effectiveness.NEUTRAL, iceDamage.effectiveness)
    }
}

package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.HazardDamage
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusApplied
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Item
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
import kotlin.test.assertTrue

class HeavyDutyBootsTest {
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
    fun `Heavy-Duty Boots bypasses Stealth Rock`() {
        // Charizard (Fire/Flying) would take 4x rock damage = maxHp/2 without Boots.
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                        Slot.p2() to PokemonState(blastoise, currentHp = blastoise.maxHp),
                    ),
                bench =
                    mapOf(
                        Side.SIDE_1 to
                            listOf(
                                PokemonState(charizard, currentHp = charizard.maxHp, item = Item.HEAVY_DUTY_BOOTS),
                            ),
                    ),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.STEALTH_ROCK to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        assertTrue(
            result.events.filterIsInstance<HazardDamage>().none { it.target == Slot.p1() },
            "Boots holder should take no hazard damage",
        )
    }

    @Test
    fun `Heavy-Duty Boots bypasses Spikes`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50) // grounded — would take Spikes

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                bench =
                    mapOf(
                        Side.SIDE_1 to
                            listOf(
                                PokemonState(swampert, currentHp = swampert.maxHp, item = Item.HEAVY_DUTY_BOOTS),
                            ),
                    ),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.SPIKES to 3)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        assertTrue(
            result.events.filterIsInstance<HazardDamage>()
                .none { it.hazard == SideHazard.SPIKES },
            "Boots holder should take no Spikes damage",
        )
    }

    @Test
    fun `Heavy-Duty Boots bypasses Toxic Spikes`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50) // grounded, non-Poison, non-Steel

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                bench =
                    mapOf(
                        Side.SIDE_1 to
                            listOf(
                                PokemonState(swampert, currentHp = swampert.maxHp, item = Item.HEAVY_DUTY_BOOTS),
                            ),
                    ),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.TOXIC_SPIKES to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        assertTrue(
            result.events.filterIsInstance<StatusApplied>().none { it.target == Slot.p1() },
            "Boots holder should not be poisoned by Toxic Spikes",
        )
    }

    @Test
    fun `Heavy-Duty Boots bypasses Sticky Web`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50) // grounded

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                bench =
                    mapOf(
                        Side.SIDE_1 to
                            listOf(
                                PokemonState(swampert, currentHp = swampert.maxHp, item = Item.HEAVY_DUTY_BOOTS),
                            ),
                    ),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.STICKY_WEB to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        assertTrue(
            result.events.filterIsInstance<StatChanged>()
                .none { it.target == Slot.p1() && it.stat == StatType.SPEED },
            "Boots holder should not have Speed dropped by Sticky Web",
        )
    }

    @Test
    fun `without Boots, hazards still fire (regression)`() {
        // Same Swampert setup as the Spikes test but without Boots.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                bench =
                    mapOf(
                        Side.SIDE_1 to listOf(PokemonState(swampert, currentHp = swampert.maxHp)),
                    ),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.SPIKES to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val damage =
            result.events.filterIsInstance<HazardDamage>()
                .firstOrNull { it.hazard == SideHazard.SPIKES }
        assertTrue(damage != null, "Non-Boots holder should take Spikes damage")
        assertEquals(swampert.maxHp / 8, damage!!.amount)
    }
}

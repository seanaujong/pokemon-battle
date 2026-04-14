package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.HazardDamage
import com.pokemon.battle.engine.HazardRemoved
import com.pokemon.battle.engine.HazardSet
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusApplied
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.SideHazard
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntryHazardsTest {
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
    // Stealth Rock — type-effectiveness-based damage, hits everyone
    // ============================================================

    @Test
    fun `Stealth Rock hits switch-in by Rock effectiveness`() {
        // Charizard (Fire/Flying) — Rock is 4x effective. maxHp × 4 / 8 = maxHp/2.
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
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(charizard, currentHp = charizard.maxHp))),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.STEALTH_ROCK to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val damage =
            result.events.filterIsInstance<HazardDamage>()
                .firstOrNull { it.target == Slot.p1() && it.hazard == SideHazard.STEALTH_ROCK }
        assertTrue(damage != null, "Stealth Rock should hit Charizard on switch-in")
        assertEquals(charizard.maxHp / 2, damage!!.amount, "Fire/Flying takes 4x rock: maxHp/2")
    }

    @Test
    fun `Stealth Rock hits a neutral type for maxHp over 8`() {
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val dragapult = Pokemon(pokedex["Dragapult"]!!, level = 50)

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(dragapult, currentHp = dragapult.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(blastoise, currentHp = blastoise.maxHp))),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.STEALTH_ROCK to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val damage =
            result.events.filterIsInstance<HazardDamage>()
                .firstOrNull { it.target == Slot.p1() && it.hazard == SideHazard.STEALTH_ROCK }
        assertTrue(damage != null)
        // Water (Blastoise): Rock 2x vs Water? No — Rock is neutral vs Water. Wait: Rock vs Water = 1x.
        assertEquals(blastoise.maxHp / 8, damage!!.amount, "Neutral Rock takes maxHp/8")
    }

    // ============================================================
    // Spikes — grounded-only, damage scales with layers
    // ============================================================

    @Test
    fun `Spikes 1 layer deals 1 over 8 maxHp`() {
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
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(swampert, currentHp = swampert.maxHp))),
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
        assertTrue(damage != null)
        assertEquals(swampert.maxHp / 8, damage!!.amount)
    }

    @Test
    fun `Flying types skip Spikes`() {
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50) // Fire/Flying

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                        Slot.p2() to PokemonState(blastoise, currentHp = blastoise.maxHp),
                    ),
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(charizard, currentHp = charizard.maxHp))),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.SPIKES to 3)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val spikesDamage =
            result.events.filterIsInstance<HazardDamage>()
                .filter { it.hazard == SideHazard.SPIKES }
        assertTrue(spikesDamage.isEmpty(), "Flying Charizard should skip Spikes")
    }

    @Test
    fun `Levitate skips Spikes`() {
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val gengar = Pokemon(pokedex["Gengar"]!!, level = 50)

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                        Slot.p2() to PokemonState(blastoise, currentHp = blastoise.maxHp),
                    ),
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(gengar, currentHp = gengar.maxHp, ability = Ability.LEVITATE))),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.SPIKES to 3)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val spikesDamage =
            result.events.filterIsInstance<HazardDamage>()
                .filter { it.hazard == SideHazard.SPIKES }
        assertTrue(spikesDamage.isEmpty(), "Levitate Gengar should skip Spikes")
    }

    // ============================================================
    // Toxic Spikes — poison on switch-in; Poison types absorb
    // ============================================================

    @Test
    fun `Toxic Spikes poisons a grounded switch-in`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50) // Grass/Poison, but POISON absorbs...
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50) // Water/Ground

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(venusaur, currentHp = venusaur.maxHp),
                    ),
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(swampert, currentHp = swampert.maxHp))),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.TOXIC_SPIKES to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val status =
            result.events.filterIsInstance<StatusApplied>()
                .firstOrNull { it.target == Slot.p1() }
        assertTrue(status != null, "Swampert should be poisoned")
        assertEquals(StatusCondition.POISON, status!!.status)
    }

    @Test
    fun `Grounded Poison type absorbs Toxic Spikes`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50) // Grass/Poison, grounded

        val state =
            BattleState(
                slots =
                    mapOf(
                        Slot.p1() to PokemonState(charizard, currentHp = charizard.maxHp),
                        Slot.p2() to PokemonState(blastoise, currentHp = blastoise.maxHp),
                    ),
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(venusaur, currentHp = venusaur.maxHp))),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.TOXIC_SPIKES to 2)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.fold(state) { s, e -> e.apply(s) }

        // Poison type absorbs — no status applied
        assertTrue(
            result.events.filterIsInstance<StatusApplied>().none { it.target == Slot.p1() },
            "Poison type should not be poisoned",
        )
        // Toxic Spikes cleared
        assertTrue(
            result.events.filterIsInstance<HazardRemoved>().any {
                it.side == Side.SIDE_1 && it.hazard == SideHazard.TOXIC_SPIKES
            },
            "Toxic Spikes should be removed",
        )
        assertFalse(SideHazard.TOXIC_SPIKES in finalState.hazardsOn(Side.SIDE_1))
    }

    // ============================================================
    // Sticky Web — Speed drop, grounded only
    // ============================================================

    @Test
    fun `Sticky Web drops Speed of grounded switch-in`() {
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
                bench = mapOf(Side.SIDE_1 to listOf(PokemonState(swampert, currentHp = swampert.maxHp))),
                sideHazards = mapOf(Side.SIDE_1 to mapOf(SideHazard.STICKY_WEB to 1)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.Switch(benchIndex = 0),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val speedDrop =
            result.events.filterIsInstance<StatChanged>()
                .firstOrNull { it.target == Slot.p1() && it.stat == StatType.SPEED }
        assertTrue(speedDrop != null)
        assertEquals(-1, speedDrop!!.stages)
    }

    // ============================================================
    // Setter moves — each increments layers
    // ============================================================

    @Test
    fun `Stealth Rock move sets the hazard on opposing side`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.STEALTH_ROCK),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val set =
            result.events.filterIsInstance<HazardSet>()
                .firstOrNull { it.hazard == SideHazard.STEALTH_ROCK && it.side == Side.SIDE_2 }
        assertTrue(set != null)
        assertEquals(1, set!!.layers)
    }

    @Test
    fun `Spikes layers stack up to 3`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        var state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.SPIKES),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        // Three turns of Spikes
        repeat(3) {
            val result = pipeline().resolveToCompletion(state, choices)
            state = result.events.fold(state) { s, e -> e.apply(s) }
        }

        assertEquals(3, state.hazardsOn(Side.SIDE_2)[SideHazard.SPIKES])

        // 4th cast shouldn't increment
        val result = pipeline().resolveToCompletion(state, choices)
        val afterFourth = result.events.fold(state) { s, e -> e.apply(s) }
        assertEquals(3, afterFourth.hazardsOn(Side.SIDE_2)[SideHazard.SPIKES])

        // ...and no new HazardSet event for layer > 3
        val newSet =
            result.events.filterIsInstance<HazardSet>()
                .filter { it.hazard == SideHazard.SPIKES }
        assertTrue(newSet.isEmpty(), "4th Spikes should not emit a HazardSet event")
    }
}

package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.DamageDealt
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.calculateDamage
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.StatStages
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CriticalHitTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val noChance: ChanceCheck = { _, _ -> false }

    // --- Crit deals more damage ---

    @Test
    fun `critical hit deals 1_5x damage`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val defender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        val normalDamage = calculateDamage(attacker, defender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = false)
        val critDamage = calculateDamage(attacker, defender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = true)

        assertTrue(
            critDamage.damage > normalDamage.damage,
            "Crit should deal more: ${critDamage.damage} vs ${normalDamage.damage}",
        )
    }

    // --- Crit ignores stat stages ---

    @Test
    fun `crit ignores defender positive defense stages`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val boostedDefender =
            PokemonState(
                venusaur,
                currentHp = venusaur.maxHp,
                statStages = StatStages(specialDefense = 6),
            )
        val neutralDefender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        // Non-crit: +6 SpDef drastically reduces damage
        val normalVsBoosted = calculateDamage(attacker, boostedDefender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = false)
        val normalVsNeutral = calculateDamage(attacker, neutralDefender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = false)
        assertTrue(normalVsBoosted.damage < normalVsNeutral.damage, "Boosted defense should reduce damage")

        // Crit: ignores +6 SpDef → same damage as vs neutral
        val critVsBoosted = calculateDamage(attacker, boostedDefender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = true)
        val critVsNeutral = calculateDamage(attacker, neutralDefender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = true)
        assertEquals(critVsBoosted.damage, critVsNeutral.damage, "Crit should ignore defender's +6 SpDef")
    }

    @Test
    fun `crit does not ignore defender negative defense stages`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val droppedDefender =
            PokemonState(
                venusaur,
                currentHp = venusaur.maxHp,
                statStages = StatStages(specialDefense = -2),
            )
        val neutralDefender = PokemonState(venusaur, currentHp = venusaur.maxHp)

        // Crit still applies defender's -2 SpDef (negative stages are kept)
        val critVsDropped = calculateDamage(attacker, droppedDefender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = true)
        val critVsNeutral = calculateDamage(attacker, neutralDefender, MoveDex.FLAMETHROWER, roll = { 100 }, isCritical = true)
        assertTrue(critVsDropped.damage > critVsNeutral.damage, "Crit should still use defender's -2 SpDef")
    }

    // --- Crit in pipeline with roll ---

    @Test
    fun `roll of 1 triggers crit in pipeline`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        // roll = { 1 } means: damage roll = 1 (min), AND crit roll = 1 (crit triggers)
        val critPipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    SwitchPhase(),
                    MoveExecutionPhase(roll = { 1 }, chanceCheck = noChance),
                    EndOfTurnPhase(),
                ),
            )

        val result = critPipeline.resolveToCompletion(state, choices)
        val damageEvents = result.events.filterIsInstance<DamageDealt>()

        // At least one hit should be critical (roll(1..24) == 1 → true)
        assertTrue(damageEvents.any { it.critical }, "Roll of 1 should trigger crits")
    }

    @Test
    fun `roll of 100 never triggers crit`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val noCritPipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(),
                    SwitchPhase(),
                    MoveExecutionPhase(roll = { 100 }, chanceCheck = noChance),
                    EndOfTurnPhase(),
                ),
            )

        val result = noCritPipeline.resolveToCompletion(state, choices)
        val damageEvents = result.events.filterIsInstance<DamageDealt>()

        assertTrue(damageEvents.none { it.critical }, "Roll of 100 should never trigger crits")
    }
}

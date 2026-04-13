package com.pokemon.battle

import com.pokemon.battle.data.*
import com.pokemon.battle.model.*
import com.pokemon.battle.engine.*
import com.pokemon.battle.phase.*
import com.pokemon.battle.loop.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DataLayerTest {

    // --- Pokedex ---

    @Test
    fun `Pokedex loads species from CSV`() {
        val pokedex = Pokedex.loadFromClasspath()

        assertTrue(pokedex.containsKey("Charizard"))
        assertTrue(pokedex.containsKey("Venusaur"))
        assertTrue(pokedex.size >= 20, "Should have at least 20 species")
    }

    @Test
    fun `Charizard has correct base stats`() {
        val pokedex = Pokedex.loadFromClasspath()
        val charizard = pokedex["Charizard"]!!

        assertEquals("Charizard", charizard.name)
        assertEquals(listOf(Type.FIRE, Type.FLYING), charizard.types)
        assertEquals(78, charizard.baseHp)
        assertEquals(84, charizard.baseAttack)
        assertEquals(78, charizard.baseDefense)
        assertEquals(109, charizard.baseSpecialAttack)
        assertEquals(85, charizard.baseSpecialDefense)
        assertEquals(100, charizard.baseSpeed)
    }

    @Test
    fun `mono-type species loads correctly`() {
        val pokedex = Pokedex.loadFromClasspath()
        val pikachu = pokedex["Pikachu"]!!

        assertEquals(listOf(Type.ELECTRIC), pikachu.types)
    }

    // --- MoveDex ---

    @Test
    fun `MoveDex contains all registered moves`() {
        assertTrue(MoveDex.all.size >= 14)
        assertTrue(MoveDex.all.containsKey("Flamethrower"))
        assertTrue(MoveDex.all.containsKey("Swords Dance"))
        assertTrue(MoveDex.all.containsKey("Earthquake"))
    }

    @Test
    fun `Flamethrower has correct properties`() {
        val flamethrower = MoveDex["Flamethrower"]
        assertEquals(Type.FIRE, flamethrower.type)
        assertEquals(MoveCategory.SPECIAL, flamethrower.category)
        assertEquals(90, flamethrower.power)
        assertEquals(MoveTarget.ONE_OPPONENT, flamethrower.target)
    }

    @Test
    fun `Swords Dance has stat boost effect`() {
        val sd = MoveDex["Swords Dance"]
        assertEquals(MoveCategory.STATUS, sd.category)
        assertEquals(MoveTarget.SELF, sd.target)
        assertEquals(1, sd.effects.size)

        val effect = sd.effects[0] as MoveEffect.StatBoost
        assertEquals(StatType.ATTACK, effect.stat)
        assertEquals(2, effect.stages)
    }

    @Test
    fun `Earthquake targets all other slots`() {
        val eq = MoveDex["Earthquake"]
        assertEquals(MoveTarget.ALL_OTHER, eq.target)
    }

    // --- Integration: full battle from database ---

    @Test
    fun `build and run a battle from Pokedex and MoveDex`() {
        val pokedex = Pokedex.loadFromClasspath()

        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state = BattleState.singles(
            PokemonState(charizard, currentHp = charizard.maxHp),
            PokemonState(venusaur, currentHp = venusaur.maxHp)
        )

        val pipeline = TurnPipeline(listOf(
            MoveOrderPhase(),
            SwitchPhase(),
            MoveExecutionPhase(roll = { 100 }, chanceCheck = { _, _ -> false }),
            EndOfTurnPhase()
        ))

        val result = pipeline.resolve(state, TurnChoices.singles(
            TurnChoice.UseMove(MoveDex["Flamethrower"]),
            TurnChoice.UseMove(MoveDex["Sludge Bomb"])
        ))

        // Charizard is faster (base 100 vs 80), uses super-effective Flamethrower
        val order = result.events.filterIsInstance<MoveOrderDecided>()
        assertEquals(Slot.p1(), order[0].order.first())

        val damage = result.events.filterIsInstance<DamageDealt>()
        assertTrue(damage.isNotEmpty())
        assertEquals(Effectiveness.SUPER_EFFECTIVE, damage[0].effectiveness)
    }
}

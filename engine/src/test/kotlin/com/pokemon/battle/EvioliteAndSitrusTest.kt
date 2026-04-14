package com.pokemon.battle

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.GenVDamageCalculator
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.ItemHealing
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvioliteAndSitrusTest {
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
    // Eviolite — 1.5x Def/SpDef for holder (equivalent to ~0.667x damage)
    // ============================================================

    @Test
    fun `Eviolite reduces physical damage taken`() {
        val infernape = Pokemon(pokedex["Infernape"]!!, level = 50)
        val swampert = Pokemon(pokedex["Swampert"]!!, level = 50)

        val attacker = PokemonState(infernape, currentHp = infernape.maxHp)
        val plain = PokemonState(swampert, currentHp = swampert.maxHp)
        val withEviolite = PokemonState(swampert, currentHp = swampert.maxHp, item = Item.EVIOLITE)

        val plainDmg =
            GenVDamageCalculator(GenVRegistries)
                .calculate(attacker, plain, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false, null).damage
        val evioliteDmg =
            GenVDamageCalculator(GenVRegistries)
                .calculate(attacker, withEviolite, MoveDex.EARTHQUAKE, fixedRoll, 1.0, false, null).damage

        assertTrue(evioliteDmg < plainDmg, "Eviolite should reduce damage: eviolite=$evioliteDmg plain=$plainDmg")
    }

    @Test
    fun `Eviolite reduces special damage taken`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val attacker = PokemonState(charizard, currentHp = charizard.maxHp)
        val plain = PokemonState(venusaur, currentHp = venusaur.maxHp)
        val withEviolite = PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.EVIOLITE)

        val plainDmg =
            GenVDamageCalculator(GenVRegistries)
                .calculate(attacker, plain, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage
        val evioliteDmg =
            GenVDamageCalculator(GenVRegistries)
                .calculate(attacker, withEviolite, MoveDex.FLAMETHROWER, fixedRoll, 1.0, false, null).damage

        assertTrue(evioliteDmg < plainDmg, "Eviolite should reduce special damage: eviolite=$evioliteDmg plain=$plainDmg")
    }

    // ============================================================
    // Sitrus Berry — heals 25% when HP drops to or below 50%
    // ============================================================

    @Test
    fun `Sitrus Berry triggers when damage drops HP below 50 percent`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        // Start Blastoise at exactly threshold+1 HP so any hit drops it below threshold.
        // Fire vs Water = 0.5x + STAB + Blastoise's bulk: damage is moderate, won't KO.
        val threshold = blastoise.maxHp / 2
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = threshold + 1, item = Item.SITRUS_BERRY),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val healing = result.events.filterIsInstance<ItemHealing>().filter { it.item == Item.SITRUS_BERRY }
        val consumed = result.events.filterIsInstance<ItemConsumed>().filter { it.item == Item.SITRUS_BERRY }

        assertEquals(1, healing.size, "Sitrus should heal once")
        assertEquals(1, consumed.size, "Sitrus should be consumed")
        assertEquals(blastoise.maxHp / 4, healing[0].amount, "Sitrus heals 25% max HP")
    }

    @Test
    fun `Sitrus Berry does not trigger if already below 50 percent before damage`() {
        // If already below threshold, berry should NOT trigger on further damage.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Venusaur already at 40% HP — already below threshold
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(
                    venusaur,
                    currentHp = (venusaur.maxHp * 4) / 10,
                    item = Item.SITRUS_BERRY,
                ),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        val healing = result.events.filterIsInstance<ItemHealing>().filter { it.item == Item.SITRUS_BERRY }
        assertTrue(healing.isEmpty(), "Sitrus should not trigger when already below threshold")
    }

    @Test
    fun `Sitrus Berry does not trigger if hit doesn't cross threshold`() {
        // Starting at 100%, small hit. HP stays above 50%.
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        // Use Flamethrower (Fire) vs Blastoise (Water, resists) — minimal damage
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = blastoise.maxHp, item = Item.SITRUS_BERRY),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        val healing = result.events.filterIsInstance<ItemHealing>().filter { it.item == Item.SITRUS_BERRY }
        assertTrue(healing.isEmpty(), "Sitrus should not trigger if hit doesn't cross threshold")
        assertEquals(Item.SITRUS_BERRY, finalState.pokemonFor(Slot.p2()).item, "Sitrus remains held")
    }
}

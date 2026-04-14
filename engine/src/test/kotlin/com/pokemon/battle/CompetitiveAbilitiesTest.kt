package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.AbilityTriggered
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.ItemConsumed
import com.pokemon.battle.engine.PokemonFainted
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.StatStages
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompetitiveAbilitiesTest {
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
    // Sturdy — same shape as Focus Sash, but on the ability side
    // ============================================================

    @Test
    fun `Sturdy survives a KO hit at full HP`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, statStages = StatStages(specialAttack = 2)),
                PokemonState(venusaur, currentHp = venusaur.maxHp, ability = Ability.STURDY),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        assertEquals(1, finalState.pokemonFor(Slot.p2()).currentHp, "Sturdy leaves holder at 1 HP")
        assertFalse(finalState.pokemonFor(Slot.p2()).isFainted)
        assertTrue(
            result.events.filterIsInstance<AbilityTriggered>().any { it.ability == Ability.STURDY },
            "AbilityTriggered should fire for Sturdy",
        )
    }

    @Test
    fun `Sturdy does not trigger when not at full HP`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp, statStages = StatStages(specialAttack = 2)),
                PokemonState(venusaur, currentHp = venusaur.maxHp - 10, ability = Ability.STURDY),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        assertTrue(
            result.events.filterIsInstance<PokemonFainted>().any { it.slot == Slot.p2() },
            "Venusaur should faint — Sturdy not at full HP",
        )
    }

    // ============================================================
    // Emergency Exit — forced switch when HP crosses 50%
    // ============================================================

    @Test
    fun `Emergency Exit triggers when HP drops below 50 percent`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // Blastoise at threshold+1, will drop below after the hit
        val threshold = blastoise.maxHp / 2
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = threshold + 1, ability = Ability.EMERGENCY_EXIT),
                p2Bench = listOf(PokemonState(venusaur, currentHp = venusaur.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        // Emergency Exit triggered
        assertTrue(
            result.events.filterIsInstance<AbilityTriggered>().any { it.ability == Ability.EMERGENCY_EXIT },
            "Emergency Exit should trigger",
        )
        // Switch happened — Venusaur is now in p2
        val switchOut = result.events.filterIsInstance<SwitchOut>().filter { it.slot == Slot.p2() }
        val switchIn = result.events.filterIsInstance<SwitchIn>().filter { it.slot == Slot.p2() }
        assertEquals(1, switchOut.size)
        assertEquals(1, switchIn.size)
        assertEquals("Venusaur", finalState.pokemonFor(Slot.p2()).pokemon.species.name)
    }

    @Test
    fun `Emergency Exit does nothing without a bench replacement`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val threshold = blastoise.maxHp / 2
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(blastoise, currentHp = threshold + 1, ability = Ability.EMERGENCY_EXIT),
                // no bench
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.ICE_BEAM),
            )

        val result = pipeline().resolveToCompletion(state, choices)

        assertTrue(
            result.events.filterIsInstance<SwitchOut>().isEmpty(),
            "No switch without bench",
        )
    }

    // ============================================================
    // Red Card — forced opponent switch
    // ============================================================

    @Test
    fun `Red Card forces attacker to switch after being hit`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.RED_CARD),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        // Red Card consumed
        assertTrue(
            result.events.filterIsInstance<ItemConsumed>().any { it.item == Item.RED_CARD },
            "Red Card should be consumed",
        )
        // Attacker (Charizard at p1) forced to switch
        val switchOut = result.events.filterIsInstance<SwitchOut>().filter { it.slot == Slot.p1() }
        assertEquals(1, switchOut.size, "Charizard should switch out")
        assertEquals("Blastoise", finalState.pokemonFor(Slot.p1()).pokemon.species.name)
    }

    @Test
    fun `Red Card does nothing if attacker has no bench`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)

        // No bench for Charizard's side
        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.RED_CARD),
            )
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        assertTrue(
            result.events.filterIsInstance<ItemConsumed>().none { it.item == Item.RED_CARD },
            "Red Card should not be consumed when attacker has no bench",
        )
        assertEquals(Item.RED_CARD, finalState.pokemonFor(Slot.p2()).item, "Red Card remains held")
    }

    @Test
    fun `Red Card does nothing on non-damaging move`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val state =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                PokemonState(venusaur, currentHp = venusaur.maxHp, item = Item.RED_CARD),
                p1Bench = listOf(PokemonState(blastoise, currentHp = blastoise.maxHp)),
            )
        // Charizard uses Swords Dance (targets self, no damage to Venusaur)
        val choices =
            TurnChoices.singles(
                TurnChoice.UseMove(MoveDex.SWORDS_DANCE),
                TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
            )

        val result = pipeline().resolveToCompletion(state, choices)
        val finalState = result.events.filterIsInstance<com.pokemon.battle.engine.GameEvent>().fold(state) { s, e -> e.apply(s) }

        assertTrue(
            result.events.filterIsInstance<ItemConsumed>().none { it.item == Item.RED_CARD },
            "Red Card should not trigger on non-damaging move",
        )
        assertEquals(Item.RED_CARD, finalState.pokemonFor(Slot.p2()).item)
    }
}

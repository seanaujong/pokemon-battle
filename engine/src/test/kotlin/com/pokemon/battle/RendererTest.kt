package com.pokemon.battle

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.MoveFailed
import com.pokemon.battle.engine.StatChanged
import com.pokemon.battle.engine.StatusCleared
import com.pokemon.battle.engine.SwitchIn
import com.pokemon.battle.engine.SwitchOut
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.engine.WeatherDamage
import com.pokemon.battle.engine.WeatherSet
import com.pokemon.battle.engine.WeatherTick
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.model.FailReason
import com.pokemon.battle.model.FieldState
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.model.StatType
import com.pokemon.battle.model.StatusCondition
import com.pokemon.battle.model.Type
import com.pokemon.battle.model.Weather
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import com.pokemon.battle.render.TextRenderer
import com.pokemon.battle.render.renderBattle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RendererTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(),
                SwitchPhase(),
                MoveExecutionPhase(roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(),
            ),
        )

    // --- Single turn rendering ---

    @Test
    fun `render Charizard vs Venusaur turn`() {
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

        val result = pipeline().resolveToCompletion(state, choices)
        var currentState = state
        val allLines = mutableListOf<String>()
        for (event in result.events) {
            val stateAfter = event.apply(currentState)
            allLines.addAll(TextRenderer.render(event, currentState, stateAfter))
            currentState = stateAfter
        }

        assertTrue(allLines.any { it == "Charizard used Flamethrower!" })
        assertTrue(allLines.any { it == "It's super effective!" })
        assertTrue(allLines.any { it.contains("Venusaur took") && it.contains("damage!") })
        assertTrue(allLines.any { it == "Venusaur used Sludge Bomb!" })
    }

    // --- Status rendering ---

    @Test
    fun `render paralysis failure`() {
        val species = Species("TestMon", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 100)
        val state =
            BattleState.singles(
                PokemonState(Pokemon(species, 50), currentHp = 100, status = StatusCondition.PARALYSIS),
                PokemonState(Pokemon(species, 50), currentHp = 100),
            )

        val event = MoveFailed(Slot.p1(), FailReason.FULLY_PARALYZED)
        val lines = TextRenderer.render(event, state, state)

        assertEquals(listOf("TestMon is fully paralyzed!"), lines)
    }

    @Test
    fun `render sleep and wake`() {
        val species = Species("Sleepy", listOf(Type.NORMAL), 80, 100, 80, 80, 80, 100)
        val state =
            BattleState.singles(
                PokemonState(Pokemon(species, 50), currentHp = 100, status = StatusCondition.SLEEP),
                PokemonState(Pokemon(species, 50), currentHp = 100),
            )

        val sleepLines = TextRenderer.render(MoveFailed(Slot.p1(), FailReason.ASLEEP), state, state)
        assertEquals(listOf("Sleepy is fast asleep!"), sleepLines)

        val wakeLines = TextRenderer.render(StatusCleared(Slot.p1(), StatusCondition.SLEEP), state, state)
        assertEquals(listOf("Sleepy woke up!"), wakeLines)
    }

    // --- Stat change rendering ---

    @Test
    fun `render stat boosts with correct phrasing`() {
        val species = Species("Fighter", listOf(Type.FIGHTING), 80, 100, 80, 80, 80, 100)
        val state =
            BattleState.singles(
                PokemonState(Pokemon(species, 50), currentHp = 100),
                PokemonState(Pokemon(species, 50), currentHp = 100),
            )

        val rose1 = TextRenderer.render(StatChanged(Slot.p1(), StatType.ATTACK, 1), state, state)
        assertEquals(listOf("Fighter's Attack rose!"), rose1)

        val rose2 = TextRenderer.render(StatChanged(Slot.p1(), StatType.ATTACK, 2), state, state)
        assertEquals(listOf("Fighter's Attack rose sharply!"), rose2)

        val fell1 = TextRenderer.render(StatChanged(Slot.p2(), StatType.SPEED, -1), state, state)
        assertEquals(listOf("Fighter's Speed fell!"), fell1)

        val fell2 = TextRenderer.render(StatChanged(Slot.p2(), StatType.SPEED, -2), state, state)
        assertEquals(listOf("Fighter's Speed fell harshly!"), fell2)
    }

    // --- Switch rendering ---

    @Test
    fun `render switch out and in`() {
        val speciesA = Species("Charizard", listOf(Type.FIRE, Type.FLYING), 78, 84, 78, 109, 85, 100)
        val speciesB = Species("Blastoise", listOf(Type.WATER), 79, 83, 100, 85, 105, 78)
        val pokemonA = Pokemon(speciesA, 50)
        val pokemonB = Pokemon(speciesB, 50)

        val stateBefore =
            BattleState.singles(
                PokemonState(pokemonA, currentHp = pokemonA.maxHp),
                PokemonState(Pokemon(speciesB, 50), currentHp = 100),
                p1Bench = listOf(PokemonState(pokemonB, currentHp = pokemonB.maxHp)),
            )

        val switchOutLines = TextRenderer.render(SwitchOut(Slot.p1()), stateBefore, stateBefore)
        assertEquals(listOf("Charizard, come back!"), switchOutLines)

        // After switch-in, the slot has Blastoise
        val stateAfter = stateBefore.withPokemon(Slot.p1(), PokemonState(pokemonB, currentHp = pokemonB.maxHp))
        val switchInLines = TextRenderer.render(SwitchIn(Slot.p1(), 0), stateBefore, stateAfter)
        assertEquals(listOf("Go! Blastoise!"), switchInLines)
    }

    // --- Weather rendering ---

    @Test
    fun `render weather events`() {
        val species = Species("TestMon", listOf(Type.FIRE), 80, 100, 80, 80, 80, 100)
        val state =
            BattleState.singles(
                PokemonState(Pokemon(species, 50), currentHp = 100),
                PokemonState(Pokemon(species, 50), currentHp = 100),
                field = FieldState(weather = Weather.SANDSTORM, weatherTurnsRemaining = 2),
            )

        val dmgLines = TextRenderer.render(WeatherDamage(Slot.p1(), 9, Weather.SANDSTORM), state, state)
        assertEquals(listOf("TestMon is buffeted by the sandstorm!"), dmgLines)

        val tickLines = TextRenderer.render(WeatherTick(Weather.SANDSTORM, 1), state, state)
        assertEquals(listOf("The sandstorm rages."), tickLines)

        val endLines = TextRenderer.render(WeatherTick(Weather.SANDSTORM, 0), state, state)
        assertEquals(listOf("The sandstorm subsided."), endLines)

        val setLines = TextRenderer.render(WeatherSet(Weather.RAIN, 5), state, state)
        assertEquals(listOf("It started to rain!"), setLines)
    }

    // --- Full battle rendering ---

    @Test
    fun `render a complete multi-turn battle`() {
        val charizard = Pokemon(pokedex["Charizard"]!!, level = 50)
        val venusaur = Pokemon(pokedex["Venusaur"]!!, level = 50)
        val blastoise = Pokemon(pokedex["Blastoise"]!!, level = 50)

        val initialState =
            BattleState.singles(
                PokemonState(charizard, currentHp = charizard.maxHp),
                // Both at 1 HP — KO'd on first and second turn
                PokemonState(venusaur, currentHp = 1),
                p2Bench = listOf(PokemonState(blastoise, currentHp = 1)),
            )

        val result =
            BattleLoop(
                pipeline = pipeline(),
                choiceProvider = {
                    TurnChoices.singles(
                        TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                        TurnChoice.UseMove(MoveDex.SLUDGE_BOMB),
                    )
                },
                faintReplacementProvider = { _, _ -> 0 },
            ).run(initialState)

        val text = renderBattle(result, initialState)

        assertTrue(text.any { it == "--- Turn 1 ---" })
        assertTrue(text.any { it.contains("Charizard used Flamethrower!") })
        assertTrue(text.any { it.contains("fainted!") })
        assertTrue(text.any { it.contains("Go!") }) // replacement
        assertTrue(text.any { it == "--- Turn 2 ---" })
        assertTrue(text.any { it.contains("Side 1 wins!") })

        // Print it — the first human-readable battle output!
        println("\n=== RENDERED BATTLE ===")
        text.forEach { println(it) }
        println("=== END ===\n")
    }
}

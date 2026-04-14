package com.pokemon.battle.cli

import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [HumanChoiceProvider] — the stdin-driven side of the CLI.
 *
 * Input is injected as a queue of lines; output is captured into a list so we
 * can assert on structural properties without pinning exact prompt strings.
 */
class HumanChoiceProviderTest {
    private val pokedex = Pokedex.loadFromClasspath()

    private fun pikachu() = Pokemon(pokedex.getValue("Pikachu"), level = 50)

    private fun charizard() = Pokemon(pokedex.getValue("Charizard"), level = 50)

    private fun venusaur() = Pokemon(pokedex.getValue("Venusaur"), level = 50)

    private fun blastoise() = Pokemon(pokedex.getValue("Blastoise"), level = 50)

    private fun fullHp(p: Pokemon) = PokemonState(p, currentHp = p.maxHp)

    // Mainline — flows reachable driving the CLI normally

    @Test
    fun `picking a regular move returns UseMove with no self-switch target`() {
        val active = pikachu()
        val state =
            BattleState.singles(
                p1 = fullHp(active),
                p2 = fullHp(venusaur()),
                p1Bench = listOf(fullHp(charizard())),
            )
        val inputs = ArrayDeque(listOf("1"))
        val output = mutableListOf<String>()
        val provider =
            HumanChoiceProvider(
                side = Side.SIDE_1,
                movePools = mapOf("Pikachu" to listOf(MoveDex.THUNDERBOLT, MoveDex.FLAMETHROWER)),
                input = { inputs.removeFirstOrNull() },
                output = { output += it },
            )

        val choices = provider.getChoices(state)

        assertEquals(TurnChoice.UseMove(MoveDex.THUNDERBOLT, switchTo = null), choices.choiceFor(Slot.p1()))
        assertTrue(output.any { it.contains("Thunderbolt") }, "move menu should list Thunderbolt; got $output")
    }

    @Test
    fun `picking a bench option returns Switch with that bench index`() {
        val active = pikachu()
        val state =
            BattleState.singles(
                p1 = fullHp(active),
                p2 = fullHp(venusaur()),
                p1Bench = listOf(fullHp(charizard()), fullHp(blastoise())),
            )
        // Move pool has 1 move, so menu is:
        //   1. Thunderbolt
        //   2. Switch to Charizard (bench index 0)
        //   3. Switch to Blastoise (bench index 1)
        val inputs = ArrayDeque(listOf("3"))
        val output = mutableListOf<String>()
        val provider =
            HumanChoiceProvider(
                side = Side.SIDE_1,
                movePools = mapOf("Pikachu" to listOf(MoveDex.THUNDERBOLT)),
                input = { inputs.removeFirstOrNull() },
                output = { output += it },
            )

        val choices = provider.getChoices(state)

        assertEquals(TurnChoice.Switch(benchIndex = 1), choices.choiceFor(Slot.p1()))
        assertTrue(output.any { it.contains("Blastoise") }, "menu should mention bench Blastoise; got $output")
    }

    @Test
    fun `getReplacement returns the picked bench index`() {
        // Fainted active, two-bench.
        val faintedActive = PokemonState(pikachu(), currentHp = 0)
        val state =
            BattleState.singles(
                p1 = faintedActive,
                p2 = fullHp(venusaur()),
                p1Bench = listOf(fullHp(charizard()), fullHp(blastoise())),
            )
        val inputs = ArrayDeque(listOf("2"))
        val output = mutableListOf<String>()
        val provider =
            HumanChoiceProvider(
                side = Side.SIDE_1,
                movePools = emptyMap(),
                input = { inputs.removeFirstOrNull() },
                output = { output += it },
            )

        val chosen = provider.getReplacement(state, Slot.p1())

        assertEquals(1, chosen)
        assertTrue(
            output.any { it.contains("replacement", ignoreCase = true) },
            "prompt should mention picking a replacement; got $output",
        )
    }

    // Mainline — self-switch UX moved to mid-turn (diary 055 Phase 2).

    @Test
    fun `self-switch move returns UseMove with switchTo null — mid-turn prompt handles target`() {
        val active = charizard()
        val state =
            BattleState.singles(
                p1 = fullHp(active),
                p2 = fullHp(venusaur()),
                p1Bench = listOf(fullHp(pikachu()), fullHp(blastoise())),
            )
        val inputs = ArrayDeque(listOf("1"))
        val output = mutableListOf<String>()
        val provider =
            HumanChoiceProvider(
                side = Side.SIDE_1,
                movePools = mapOf("Charizard" to listOf(MoveDex.U_TURN)),
                input = { inputs.removeFirstOrNull() },
                output = { output += it },
            )

        val choices = provider.getChoices(state)

        // switchTo is null — the engine will pause after damage and call respond().
        assertEquals(TurnChoice.UseMove(MoveDex.U_TURN, switchTo = null), choices.choiceFor(Slot.p1()))
    }

    @Test
    fun `respond to SwitchTargetRequest returns chosen bench index`() {
        val active = charizard()
        val state =
            BattleState.singles(
                p1 = fullHp(active),
                p2 = fullHp(venusaur()),
                p1Bench = listOf(fullHp(pikachu()), fullHp(blastoise())),
            )
        val inputs = ArrayDeque(listOf("2"))
        val output = mutableListOf<String>()
        val provider =
            HumanChoiceProvider(
                side = Side.SIDE_1,
                movePools = emptyMap(),
                input = { inputs.removeFirstOrNull() },
                output = { output += it },
            )
        val request =
            com.pokemon.battle.engine.SwitchTargetRequest(
                userSlot = Slot.p1(),
                reason = com.pokemon.battle.engine.SwitchReason.SELF_SWITCH_MOVE,
                eligibleBenchIndices = listOf(0, 1),
            )

        val response = provider.respond(state, request)

        assertEquals(com.pokemon.battle.engine.SwitchTargetResponse(benchIndex = 1), response)
        assertTrue(
            output.any { it.contains("replacement", ignoreCase = true) },
            "mid-turn prompt should mention picking a replacement; got $output",
        )
    }
}

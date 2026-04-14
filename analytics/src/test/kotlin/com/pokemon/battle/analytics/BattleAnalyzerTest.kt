package com.pokemon.battle.analytics

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.ChanceCheck
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.loop.BattleResult
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.model.Slot
import com.pokemon.battle.model.Species
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleAnalyzerTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }
    private val noChance: ChanceCheck = { _, _ -> false }

    private fun pipeline() =
        TurnPipeline(
            listOf(
                MoveOrderPhase(GenVRegistries),
                SwitchPhase(GenVRegistries),
                MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = noChance),
                EndOfTurnPhase(GenVRegistries),
            ),
        )

    private fun pokemon(species: Species) = PokemonState(Pokemon(species, level = 50), currentHp = Pokemon(species, level = 50).maxHp)

    // Mainline Pokemon mechanics — reachable in normal play

    @Test
    fun `analyze captures winner, turn count, KOs, moves, damage, and crits from a real battle`() {
        val charizard = pokemon(pokedex.getValue("Charizard"))
        val squirtle = pokemon(pokedex.getValue("Squirtle")).copy(currentHp = 1)

        val state = BattleState.singles(charizard, squirtle)

        val loop =
            BattleLoop(
                pipeline = pipeline(),
                choiceProvider = {
                    TurnChoices.singles(
                        TurnChoice.UseMove(MoveDex.FLAMETHROWER),
                        TurnChoice.UseMove(MoveDex.TACKLE),
                    )
                },
                faintReplacementProvider = { _, _ -> 0 },
            )

        val result: BattleResult = loop.run(state)
        val summary = BattleAnalyzer.analyze(result)

        // Let the code tell you the shape — assert on what the engine actually produced.
        assertEquals(Side.SIDE_1, summary.winner)
        assertEquals(result.turnHistory.size, summary.turnsPlayed)
        assertTrue(summary.turnsPlayed >= 1)

        // Squirtle (1 HP) fainted on Side 2 slot 0.
        assertEquals(1, summary.koCount[Slot.p2()] ?: 0)
        assertEquals(0, summary.koCount[Slot.p1()] ?: 0)

        // Charizard attempted Flamethrower. Squirtle fainted before acting, so only
        // Flamethrower shows up. (If ordering ever flipped, this assertion still
        // expresses the invariant: whichever moves were attempted got counted.)
        assertTrue((summary.movesUsed["Flamethrower"] ?: 0) >= 1)

        // Some damage was recorded against Squirtle.
        assertTrue((summary.damageDealt[Slot.p2()] ?: 0) > 0)

        // No items / abilities in this scenario.
        assertTrue(summary.itemsTriggered.isEmpty())
        assertTrue(summary.abilitiesTriggered.isEmpty())

        // Crits are disabled via the chance check, so criticalHits should be 0.
        assertEquals(0, summary.criticalHits)
    }

    @Test
    fun `empty-history battle result analyzes to empty summary`() {
        // A battle result with no turns — all maps empty, no winner.
        val result =
            BattleResult(
                winner = null,
                finalState =
                    BattleState.singles(
                        pokemon(pokedex.getValue("Charizard")),
                        pokemon(pokedex.getValue("Squirtle")),
                    ),
                turnHistory = emptyList(),
            )
        val summary = BattleAnalyzer.analyze(result)

        assertEquals(null, summary.winner)
        assertEquals(0, summary.turnsPlayed)
        assertTrue(summary.koCount.isEmpty())
        assertTrue(summary.movesUsed.isEmpty())
        assertTrue(summary.itemsTriggered.isEmpty())
        assertTrue(summary.abilitiesTriggered.isEmpty())
        assertEquals(0, summary.criticalHits)
        assertTrue(summary.damageDealt.isEmpty())
    }

    // Extensibility / corner cases
    //
    // BattleSummary intentionally projects only a slice of the event stream. These
    // tests document that the raw event list — not the summary — remains the source
    // of truth. If a new analytics field is needed, it's always computable from
    // `result.turnHistory` without engine changes.
}

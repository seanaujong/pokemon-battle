package com.pokemon.battle.analytics

import com.pokemon.battle.data.GenVRegistries
import com.pokemon.battle.data.MoveDex
import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.engine.BattleState
import com.pokemon.battle.engine.TurnChoice
import com.pokemon.battle.engine.TurnChoices
import com.pokemon.battle.engine.TurnPipeline
import com.pokemon.battle.loop.BattleLoop
import com.pokemon.battle.loop.BattleResult
import com.pokemon.battle.model.Pokemon
import com.pokemon.battle.model.PokemonState
import com.pokemon.battle.model.Side
import com.pokemon.battle.persistence.BattleMetadata
import com.pokemon.battle.persistence.PersistedBattle
import com.pokemon.battle.persistence.toPersisted
import com.pokemon.battle.phase.EndOfTurnPhase
import com.pokemon.battle.phase.MoveExecutionPhase
import com.pokemon.battle.phase.MoveOrderPhase
import com.pokemon.battle.phase.SwitchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises `BattleCorpus` aggregations against a tiny real corpus — 3 scripted
 * battles built in-memory. No file I/O in the test; [PersistedBattle] is built
 * directly via [toPersisted]. This matches diary 078's layering commitment:
 * `:analytics` is I/O-free; `:persistence` is where disk access lives.
 */
class BattleCorpusTest {
    private val pokedex = Pokedex.loadFromClasspath()
    private val fixedRoll: (IntRange) -> Int = { 100 }

    private fun runBattle(): BattleResult {
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

        val pipeline =
            TurnPipeline(
                listOf(
                    MoveOrderPhase(GenVRegistries),
                    SwitchPhase(GenVRegistries),
                    MoveExecutionPhase(GenVRegistries, roll = fixedRoll, chanceCheck = { _, _ -> false }),
                    EndOfTurnPhase(GenVRegistries),
                ),
            )
        return BattleLoop(
            pipeline = pipeline,
            choiceProvider = { choices },
            faintReplacementProvider = { _, _ -> 0 },
            registries = GenVRegistries,
            maxTurns = 10,
        ).run(state)
    }

    private fun stubMetadata(id: String) =
        BattleMetadata(
            battleId = id,
            startedAtEpochMs = 0L,
            endedAtEpochMs = 0L,
            formatTag = "test",
        )

    @Test
    fun `corpus aggregates reflect scripted battles`() {
        val corpus = (1..3).map { i -> toPersisted(runBattle(), stubMetadata("battle-$i")) }

        val winCounts = BattleCorpus.winsBySide(corpus.asSequence())
        // Charizard's Flamethrower is super-effective on Venusaur; SIDE_1 wins every time.
        assertEquals(3, winCounts[Side.SIDE_1])
        assertEquals(null, winCounts[Side.SIDE_2])

        val rates = BattleCorpus.winRate(corpus.asSequence())
        assertEquals(1.0, rates[Side.SIDE_1]!!, 0.001)

        val moves = BattleCorpus.moveUsage(corpus.asSequence())
        assertTrue(moves["Flamethrower"]!! >= 3, "at least 3 Flamethrower uses across 3 battles")
        assertTrue(moves.containsKey("Sludge Bomb"), "Sludge Bomb appears at least once before Venusaur faints")

        assertTrue(BattleCorpus.koCount(corpus.asSequence()) >= 3, "at least 3 KOs across 3 battles")
    }
}

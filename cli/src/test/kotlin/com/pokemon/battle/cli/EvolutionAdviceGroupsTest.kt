package com.pokemon.battle.cli

import com.pokemon.battle.data.EvolutionLineDex
import com.pokemon.battle.data.GenerationMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Grouping is computed from the committed Roselia line (base Budew), the worked case
 * for the user's two asks: collapse games with identical advice, and only show games
 * where the evolution is actually possible.
 */
class EvolutionAdviceGroupsTest {
    private val budew = EvolutionLineDex.loadFromClasspath().getValue("budew")

    private fun edge(
        from: String,
        to: String,
    ) = budew.edges.first { it.from == from && it.to == to }

    private fun generationsOf(games: List<String>) = games.map { GenerationMap.generationOf(it) }

    @Test
    fun `each available game lands in exactly one group, and identical games collapse`() {
        val groups = adviceGroups(budew, edge("roselia", "roserade"))
        val games = groups.flatMap { it.versionGroups }
        assertEquals(games.size, games.toSet().size, "a game appears in exactly one group")
        assertTrue(groups.size < games.size, "identical games must collapse into shared groups")
        // Heartgold Soulsilver and Platinum produce identical advice — same group.
        val hgssGroup = groups.first { "heartgold-soulsilver" in it.versionGroups }
        assertTrue("platinum" in hgssGroup.versionGroups)
    }

    @Test
    fun `a game is excluded when either stage is absent from it`() {
        // Roserade is gen IV+, so the Roselia -> Roserade edge skips gen III entirely.
        val roseradeGames = adviceGroups(budew, edge("roselia", "roserade")).flatMap { it.versionGroups }
        assertTrue("III" !in generationsOf(roseradeGames), "no Roserade before gen IV")

        // Budew is gen IV+, so the Budew -> Roselia edge skips gen III too — even though
        // Roselia exists there, you can't obtain the Budew to evolve.
        val budewGames = adviceGroups(budew, edge("budew", "roselia")).flatMap { it.versionGroups }
        assertTrue("III" !in generationsOf(budewGames), "no Budew before gen IV")
    }

    @Test
    fun `the evolve-freely group sorts last and holds only games with no loss`() {
        val groups = adviceGroups(budew, edge("budew", "roselia"))
        assertTrue(groups.last().flags.isEmpty(), "evolve-freely sorts last")
        assertTrue(groups.dropLast(1).all { it.flags.isNotEmpty() }, "only the trailing group is empty")
    }

    @Test
    fun `gamesLabel names the generation once within a gen and per-game across gens`() {
        assertEquals("Heartgold Soulsilver, Platinum (gen IV)", gamesLabel(listOf("heartgold-soulsilver", "platinum")))
        assertEquals("Black White (V), X Y (VI)", gamesLabel(listOf("black-white", "x-y")))
    }
}

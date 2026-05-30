package com.pokemon.battle.ingest.transform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The transform must capture the secondary evolution conditions PokeAPI attaches —
 * friendship, time of day, a held item on a trade — not just `{trigger, minLevel,
 * item}`. Without them, friendship evolutions render as bare "level-up" and held-item
 * trades as bare "trade". Built from a crafted chain so the assertion is independent
 * of any live response.
 */
class EvolutionLineConditionsTest {
    // aa --(friendship, day)--> bb --(trade holding King's Rock)--> cc
    private val chain =
        """
        {"chain":{"species":{"name":"aa"},"evolves_to":[
          {"species":{"name":"bb"},
           "evolution_details":[{"trigger":{"name":"level-up"},"min_happiness":160,"time_of_day":"day"}],
           "evolves_to":[
             {"species":{"name":"cc"},
              "evolution_details":[{"trigger":{"name":"trade"},"held_item":{"name":"kings-rock"}}],
              "evolves_to":[]}
           ]}
        ]}}
        """.trimIndent()

    private val edges = EvolutionLineTransform.transform("aa", chain, emptyMap()).edges.associateBy { it.from }

    @Test
    fun `friendship and time of day are captured`() {
        val edge = edges.getValue("aa")
        assertEquals(160, edge.minHappiness)
        assertEquals("day", edge.timeOfDay)
        assertNull(edge.minLevel, "a friendship evolution has no level gate")
    }

    @Test
    fun `a held item on a trade is captured`() {
        val edge = edges.getValue("bb")
        assertEquals("trade", edge.trigger)
        assertEquals("kings-rock", edge.heldItem)
    }
}

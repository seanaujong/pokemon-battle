package com.pokemon.battle.server.team

import com.pokemon.battle.data.Pokedex
import com.pokemon.battle.model.Ability
import com.pokemon.battle.model.Item
import com.pokemon.battle.model.Nature
import com.pokemon.battle.model.StatType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmogonParserTest {
    private val sampleTeam =
        """
        Charizard @ Life Orb
        Ability: Blaze
        Level: 50
        EVs: 252 SpA / 4 SpD / 252 Spe
        Timid Nature
        IVs: 0 Atk
        - Flamethrower
        - Thunderbolt
        - Earthquake
        - U-turn

        Venusaur
        Ability: Overgrow
        Level: 50
        Bold Nature
        - Sludge Bomb
        - Earthquake
        - Ice Beam
        - Growl
        """.trimIndent()

    @Test
    fun `parses two sets`() {
        val sets = SmogonParser.parseTeam(sampleTeam)
        assertEquals(2, sets.size)
    }

    @Test
    fun `first set fields resolve correctly`() {
        val charizard = SmogonParser.parseTeam(sampleTeam)[0]
        assertEquals("Charizard", charizard.species)
        assertEquals(Item.LIFE_ORB, charizard.item)
        assertEquals(Ability.BLAZE, charizard.ability)
        assertEquals(50, charizard.level)
        assertEquals(Nature.TIMID, charizard.nature)
        assertEquals(252, charizard.evs.forStat(StatType.SPECIAL_ATTACK))
        assertEquals(252, charizard.evs.forStat(StatType.SPEED))
        assertEquals(4, charizard.evs.forStat(StatType.SPECIAL_DEFENSE))
        assertEquals(0, charizard.ivs.forStat(StatType.ATTACK))
        assertEquals(31, charizard.ivs.forStat(StatType.SPEED))
        assertEquals(listOf("Flamethrower", "Thunderbolt", "Earthquake", "U-turn"), charizard.moves)
    }

    @Test
    fun `item is null when no @ in header`() {
        val venusaur = SmogonParser.parseTeam(sampleTeam)[1]
        assertNull(venusaur.item)
        assertEquals(Ability.OVERGROW, venusaur.ability)
        assertEquals(Nature.BOLD, venusaur.nature)
    }

    @Test
    fun `TeamBuilder resolves species and moves`() {
        val pokedex = Pokedex.loadFromClasspath()
        val sets = SmogonParser.parseTeam(sampleTeam)
        val team = TeamBuilder.build(sets, pokedex)

        assertEquals(2, team.size)
        val charizard = team[0]
        assertEquals("Charizard", charizard.state.pokemon.species.name)
        assertEquals(Item.LIFE_ORB, charizard.state.item)
        assertEquals(Ability.BLAZE, charizard.state.ability)
        assertEquals(charizard.state.maxHp, charizard.state.currentHp)
        assertEquals(4, charizard.moves.size)
        assertEquals("Flamethrower", charizard.moves[0].name)
    }
}

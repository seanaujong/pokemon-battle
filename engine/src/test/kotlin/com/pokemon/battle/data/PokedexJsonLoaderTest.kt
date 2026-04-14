package com.pokemon.battle.data

import com.pokemon.battle.model.Type
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end proof that the ingestion pipeline produces files the engine can load.
 * Reads the committed outputs of `:data-ingestion:run` and asserts stat accuracy
 * against mainline ground truth. If PokeAPI ever revises a base stat, this test
 * flags the drift.
 */
class PokedexJsonLoaderTest {
    private val speciesDir: Path = Path.of("src/main/resources/pokedex/species")

    @Test
    fun `loads ingested species files from disk`() {
        val species = Pokedex.loadFromJsonDirectory(speciesDir)

        assertTrue(species.containsKey("Pikachu"))
        assertTrue(species.containsKey("Charizard"))
        assertTrue(species.containsKey("Venusaur"))
        assertTrue(species.containsKey("Blastoise"))
    }

    @Test
    fun `ingested Pikachu matches mainline base stats`() {
        val pikachu = Pokedex.loadFromJsonDirectory(speciesDir).getValue("Pikachu")

        assertEquals(listOf(Type.ELECTRIC), pikachu.types)
        assertEquals(35, pikachu.baseHp)
        assertEquals(55, pikachu.baseAttack)
        assertEquals(40, pikachu.baseDefense)
        assertEquals(50, pikachu.baseSpecialAttack)
        assertEquals(50, pikachu.baseSpecialDefense)
        assertEquals(90, pikachu.baseSpeed)
    }

    @Test
    fun `ingested Charizard is Fire and Flying in slot order`() {
        val charizard = Pokedex.loadFromJsonDirectory(speciesDir).getValue("Charizard")

        assertEquals(listOf(Type.FIRE, Type.FLYING), charizard.types)
    }

    @Test
    fun `loadJsonFromClasspath reads the manifest and loads every listed species`() {
        // This is the runtime path engine consumers (PlayMain, tests) actually use —
        // no filesystem assumptions; everything via the classpath.
        val species = Pokedex.loadJsonFromClasspath()

        assertTrue(species.containsKey("Pikachu"), "manifest should include Pikachu")
        assertTrue(species.containsKey("Great Tusk"), "Smogon-ingested species should appear")
        assertEquals(35, species.getValue("Pikachu").baseHp)
    }
}

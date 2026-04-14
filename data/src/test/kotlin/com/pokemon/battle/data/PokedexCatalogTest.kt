package com.pokemon.battle.data

import com.pokemon.battle.model.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the generated [PokedexCatalog] (diary 064). Symbol-keyed counterpart to
 * [PokedexJsonLoaderTest], which exercises the runtime classpath path. Both
 * paths surface the same domain `Species` values; this test confirms the
 * generator emits well-known stats correctly and that the convenience `all` map
 * agrees with the symbol fields.
 */
class PokedexCatalogTest {
    @Test
    fun `Pikachu symbol matches mainline base stats`() {
        val pikachu = PokedexCatalog.PIKACHU

        assertEquals("Pikachu", pikachu.name)
        assertEquals(listOf(Type.ELECTRIC), pikachu.types)
        assertEquals(35, pikachu.baseHp)
        assertEquals(55, pikachu.baseAttack)
        assertEquals(40, pikachu.baseDefense)
        assertEquals(50, pikachu.baseSpecialAttack)
        assertEquals(50, pikachu.baseSpecialDefense)
        assertEquals(90, pikachu.baseSpeed)
    }

    @Test
    fun `dual-type species like Charizard preserve type slot order`() {
        assertEquals(listOf(Type.FIRE, Type.FLYING), PokedexCatalog.CHARIZARD.types)
    }

    @Test
    fun `Smogon-ingested species (Great Tusk) is present`() {
        assertEquals("Great Tusk", PokedexCatalog.GREAT_TUSK.name)
    }

    @Test
    fun `the all map agrees with the symbol fields`() {
        assertEquals(PokedexCatalog.PIKACHU, PokedexCatalog.all.getValue("Pikachu"))
        assertEquals(PokedexCatalog.GREAT_TUSK, PokedexCatalog.all.getValue("Great Tusk"))
        assertTrue(PokedexCatalog.all.size >= 98, "catalog should hold every ingested species")
    }
}

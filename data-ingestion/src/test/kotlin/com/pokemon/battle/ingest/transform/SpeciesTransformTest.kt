package com.pokemon.battle.ingest.transform

import com.pokemon.battle.model.Type
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the transform against a minimal PokeAPI-shaped fixture. Once Step 4 runs
 * the ingestion CLI end-to-end, real cached responses are available under
 * `data/raw/pokeapi/pokemon/` and can replace or supplement these inline fixtures.
 */
class SpeciesTransformTest {
    // Mainline Pokemon mechanics — reachable in normal play

    @Test
    fun `transforms single-type species with known stats`() {
        val raw =
            """
            {
              "name": "pikachu",
              "types": [{"slot": 1, "type": {"name": "electric"}}],
              "stats": [
                {"base_stat": 35, "stat": {"name": "hp"}},
                {"base_stat": 55, "stat": {"name": "attack"}},
                {"base_stat": 40, "stat": {"name": "defense"}},
                {"base_stat": 50, "stat": {"name": "special-attack"}},
                {"base_stat": 50, "stat": {"name": "special-defense"}},
                {"base_stat": 90, "stat": {"name": "speed"}}
              ]
            }
            """.trimIndent()

        val result = SpeciesTransform.transform(raw)

        assertEquals("PIKACHU", result.name)
        assertEquals(listOf("ELECTRIC"), result.types)
        assertEquals(35, result.baseHp)
        assertEquals(55, result.baseAttack)
        assertEquals(40, result.baseDefense)
        assertEquals(50, result.baseSpecialAttack)
        assertEquals(50, result.baseSpecialDefense)
        assertEquals(90, result.baseSpeed)
    }

    @Test
    fun `transforms dual-type species preserving type order by slot`() {
        val raw =
            """
            {
              "name": "charizard",
              "types": [
                {"slot": 2, "type": {"name": "flying"}},
                {"slot": 1, "type": {"name": "fire"}}
              ],
              "stats": [
                {"base_stat": 78, "stat": {"name": "hp"}},
                {"base_stat": 84, "stat": {"name": "attack"}},
                {"base_stat": 78, "stat": {"name": "defense"}},
                {"base_stat": 109, "stat": {"name": "special-attack"}},
                {"base_stat": 85, "stat": {"name": "special-defense"}},
                {"base_stat": 100, "stat": {"name": "speed"}}
              ]
            }
            """.trimIndent()

        val result = SpeciesTransform.transform(raw)

        assertEquals(listOf("FIRE", "FLYING"), result.types)
    }

    @Test
    fun `hyphenated slugs are normalized to SCREAMING_SNAKE`() {
        val raw =
            """
            {
              "name": "mr-mime",
              "types": [{"slot": 1, "type": {"name": "psychic"}}],
              "stats": [
                {"base_stat": 40, "stat": {"name": "hp"}},
                {"base_stat": 45, "stat": {"name": "attack"}},
                {"base_stat": 65, "stat": {"name": "defense"}},
                {"base_stat": 100, "stat": {"name": "special-attack"}},
                {"base_stat": 120, "stat": {"name": "special-defense"}},
                {"base_stat": 90, "stat": {"name": "speed"}}
              ]
            }
            """.trimIndent()

        val result = SpeciesTransform.transform(raw)

        assertEquals("MR_MIME", result.name)
    }

    @Test
    fun `transformed SpeciesJson converts cleanly to engine Species`() {
        val raw =
            """
            {
              "name": "pikachu",
              "types": [{"slot": 1, "type": {"name": "electric"}}],
              "stats": [
                {"base_stat": 35, "stat": {"name": "hp"}},
                {"base_stat": 55, "stat": {"name": "attack"}},
                {"base_stat": 40, "stat": {"name": "defense"}},
                {"base_stat": 50, "stat": {"name": "special-attack"}},
                {"base_stat": 50, "stat": {"name": "special-defense"}},
                {"base_stat": 90, "stat": {"name": "speed"}}
              ]
            }
            """.trimIndent()

        val species = SpeciesTransform.transform(raw).toDomain()

        assertEquals(listOf(Type.ELECTRIC), species.types)
        assertEquals(35, species.baseHp)
    }
}

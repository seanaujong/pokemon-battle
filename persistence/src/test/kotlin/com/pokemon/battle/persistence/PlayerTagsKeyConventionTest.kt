package com.pokemon.battle.persistence

import com.pokemon.battle.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `BattleMetadata.playerTags` map is keyed by `Side.name` strings by
 * convention — `"SIDE_1"` / `"SIDE_2"`. Four places (and counting) now rely on
 * this convention:
 *
 * - `MatrixEvalMain` writes the keys.
 * - `BattleCorpus.matchupWinRates` reads them.
 * - `docs/corpus-queries.md` SQL cookbook references them (`metadata->>'playerTags'->>'SIDE_1'`).
 * - `scripts/analyst-query.main.kts` reads them via the Kotlin type `Side.name`.
 *
 * Diary 082 first flagged the need for this test; diary 085 made the
 * convention more load-bearing by duplicating it across Kotlin + SQL + script;
 * this test shipped under diary 086's "finish the flagged items" scope.
 *
 * If someone ever renames a `Side` enum value (e.g. `SIDE_1` → `PLAYER_A`), this
 * test fails loudly. It doesn't fail at the callsites because those use
 * `Side.SIDE_1.name` which compiles regardless — the name is the silent drift
 * point.
 */
class PlayerTagsKeyConventionTest {
    @Test
    fun `Side enum values produce the expected convention keys`() {
        // Both entries must be present and named exactly as the SQL cookbook
        // and analyst scripts expect. Renaming requires coordinated updates
        // across that whole set.
        assertEquals("SIDE_1", Side.SIDE_1.name)
        assertEquals("SIDE_2", Side.SIDE_2.name)
    }

    @Test
    fun `PlayerTags round-trip through BattleMetadata with Side dot name keys`() {
        val metadata =
            BattleMetadata(
                battleId = "test",
                startedAtEpochMs = 0L,
                endedAtEpochMs = 0L,
                formatTag = "test",
                playerTags = mapOf(Side.SIDE_1.name to "TypeAI", Side.SIDE_2.name to "RandomAI"),
            )

        assertEquals("TypeAI", metadata.playerTags[Side.SIDE_1.name])
        assertEquals("RandomAI", metadata.playerTags[Side.SIDE_2.name])
        // Reading via the raw string (what SQL and Python scripts do) must agree
        // with reading via Side.name (what Kotlin callers do).
        assertEquals(metadata.playerTags["SIDE_1"], metadata.playerTags[Side.SIDE_1.name])
        assertEquals(metadata.playerTags["SIDE_2"], metadata.playerTags[Side.SIDE_2.name])
    }

    @Test
    fun `Every Side value has a distinct name (guards against collision)`() {
        val names = Side.entries.map { it.name }
        assertEquals(names.size, names.toSet().size, "Side.name values must be unique")
        assertTrue(names.all { it.isNotBlank() })
    }
}

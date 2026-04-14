package com.pokemon.battle.ingest.codegen

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drift check (diary 064): the committed `PokedexCatalog.kt` must match what
 * codegen would produce *right now* from the committed species JSON. Catches:
 *
 * 1. JSON refreshed without re-running `:data-ingestion:codegenSpecies` →
 *    catalog stale relative to data.
 * 2. Hand-edits to `PokedexCatalog.kt` (which `// Generated. Do not edit.`
 *    asks contributors to avoid).
 * 3. Codegen template changes that aren't reflected in the committed catalog.
 *
 * Calls the same `renderCatalog` function the codegen entrypoint uses, so
 * there's no duplicated rendering logic to keep in sync.
 */
class PokedexCodegenTest {
    @Test
    fun `committed PokedexCatalog matches what codegen would produce now`() {
        val repoRoot = Path.of("..").toAbsolutePath().normalize()
        val speciesDir = repoRoot.resolve("data/src/main/resources/pokedex/species")
        val manifest = speciesDir.resolve("index.txt")
        val catalogPath = repoRoot.resolve("data/src/main/kotlin/com/pokemon/battle/data/PokedexCatalog.kt")

        val expected = renderCatalog(manifest, speciesDir)
        val actual = catalogPath.readText()

        assertEquals(
            expected,
            actual,
            "PokedexCatalog.kt is stale relative to the JSON species data. " +
                "Run `./gradlew :data-ingestion:codegenSpecies` and commit the result.",
        )
    }
}

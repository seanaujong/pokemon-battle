package com.pokemon.battle.cli

import com.pokemon.battle.data.EvolutionEdgeJson
import com.pokemon.battle.data.EvolutionLineJson
import com.pokemon.battle.data.MoveAcquisitionJson
import com.pokemon.battle.ingest.fetch.PokeApiClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fetch is injected via `buildBundle`, so these never touch the live network.
 * They cover the on-demand resolution logic; the advisor's own correctness lives in
 * `EvolutionDelayAdvisorTest`.
 */
class OnDemandEvolutionLinesTest {
    private fun tempCache(): Path = Files.createTempDirectory("on-demand-lines")

    // A two-stage fake line. The queried stage is the *evolved* form, so the bundle is
    // keyed by the root "testbase" — mirroring "drizzile" resolving to the Sobble line.
    private val fakeBundle =
        EvolutionLineJson(
            base = "testbase",
            edges = listOf(EvolutionEdgeJson(from = "testbase", to = "teststage", trigger = "level-up", minLevel = 16)),
            learnsets = mapOf("testbase" to mapOf("scarlet-violet" to listOf(MoveAcquisitionJson("tackle", "level-up", 5)))),
        )

    // ---- Mainline behaviour — reachable in normal play -----------------------------

    @Test
    fun `a committed line resolves without ingesting`() {
        // kricketot is in the committed curated set; resolving must not fetch.
        val resolver =
            OnDemandEvolutionLines(
                cacheDir = tempCache(),
                buildBundle = { error("should not fetch for a committed species") },
            )
        val resolved = assertIs<OnDemandEvolutionLines.Resolution.Resolved>(resolver.resolve("kricketot"))
        assertNull(resolved.cachePath, "committed lines are not written to the cache")
    }

    @Test
    fun `a missing line is ingested on demand into the gitignored cache`() {
        val cache = tempCache()
        var fetches = 0
        val resolver =
            OnDemandEvolutionLines(
                cacheDir = cache,
                buildBundle = {
                    fetches++
                    fakeBundle
                },
            )

        val resolved = assertIs<OnDemandEvolutionLines.Resolution.Resolved>(resolver.resolve("teststage"))

        assertEquals(1, fetches)
        assertEquals("testbase", resolved.line.base)
        assertEquals(cache.resolve("testbase.json"), resolved.cachePath)
        assertTrue(resolved.cachePath!!.exists(), "bundle is written to the cache dir")
    }

    @Test
    fun `a cached on-demand line resolves on the next call without re-fetching`() {
        val cache = tempCache()
        OnDemandEvolutionLines(cacheDir = cache, buildBundle = { fakeBundle }).resolve("teststage")

        // A fresh resolver over the same cache dir must read the file, not fetch again.
        val again =
            OnDemandEvolutionLines(cacheDir = cache, buildBundle = { error("should read the cache, not fetch") })
                .resolve("testbase")

        val resolved = assertIs<OnDemandEvolutionLines.Resolution.Resolved>(again)
        assertNull(resolved.cachePath, "an already-present line resolves without a fresh ingest")
    }

    @Test
    fun `a fetch failure degrades to Unavailable rather than crashing`() {
        val resolver =
            OnDemandEvolutionLines(
                cacheDir = tempCache(),
                buildBundle = { throw PokeApiClient.FetchException("https://pokeapi.co/x", 404, "Not Found") },
            )
        assertIs<OnDemandEvolutionLines.Resolution.Unavailable>(resolver.resolve("notapokemon"))
    }

    @Test
    fun `an ingested line that lacks the queried species is rejected, not cached`() {
        val cache = tempCache()
        // fakeBundle covers {testbase, teststage}; ask for something the line does not contain.
        val resolver = OnDemandEvolutionLines(cacheDir = cache, buildBundle = { fakeBundle })

        assertIs<OnDemandEvolutionLines.Resolution.Unavailable>(resolver.resolve("unrelated"))
        assertEquals(0L, Files.list(cache).use { it.count() }, "a rejected bundle is not written to the cache")
    }
}

package com.pokemon.battle.ingest.smogon

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fetches Smogon monthly chaos JSON with an on-disk politeness cache. Same contract
 * as [com.pokemon.battle.ingest.fetch.PokeApiClient]: the cache is the gitignored
 * `.cache/smogon/` tier, and re-fetching is cheap since the API is stable.
 *
 * Politeness: 500ms delay between uncached requests (Smogon is volunteer-run and
 * requests the slower cadence relative to PokeAPI's rate limits).
 */
class SmogonClient(
    private val cacheRoot: Path = Path.of(".cache/smogon"),
    private val baseUrl: String = "https://www.smogon.com/stats",
    private val userAgent: String = "pokemon-battle-engine/1.0 (github.com/seanaujong/pokemon-battle)",
    private val requestDelayMillis: Long = REQUEST_DELAY_MILLIS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val httpGet: (String, String) -> HttpResult = ::defaultHttpGet,
) {
    /**
     * Returns the raw chaos JSON for a (month, format, rating) triple, fetching
     * once and caching thereafter. Throws [FetchException] on non-2xx responses.
     */
    fun fetchChaos(
        month: String,
        format: String,
        ratingCutoff: Int,
    ): String {
        val cachePath = cacheRoot.resolve(month).resolve("chaos").resolve("$format-$ratingCutoff.json")
        if (Files.exists(cachePath)) {
            return Files.readString(cachePath)
        }
        sleep(requestDelayMillis)
        val url = "$baseUrl/$month/chaos/$format-$ratingCutoff.json"
        val result = httpGet(url, userAgent)
        if (result.status !in HTTP_OK_RANGE) {
            throw FetchException(url, result.status, result.body)
        }
        Files.createDirectories(cachePath.parent)
        Files.writeString(cachePath, result.body)
        return result.body
    }

    data class HttpResult(val status: Int, val body: String)

    class FetchException(url: String, status: Int, body: String) :
        RuntimeException("GET $url returned $status: ${body.take(BODY_SNIPPET_LENGTH)}")

    companion object {
        private const val REQUEST_DELAY_MILLIS = 500L
        private const val BODY_SNIPPET_LENGTH = 200
        private val HTTP_OK_RANGE = 200..299
    }
}

private fun defaultHttpGet(
    url: String,
    userAgent: String,
): SmogonClient.HttpResult {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", userAgent)
    conn.setRequestProperty("Accept", "application/json")
    return conn.use {
        val status = it.responseCode
        val stream = if (status in 200..299) it.inputStream else it.errorStream
        val body = stream?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
        SmogonClient.HttpResult(status, body)
    }
}

private inline fun <R> HttpURLConnection.use(block: (HttpURLConnection) -> R): R =
    try {
        block(this)
    } finally {
        disconnect()
    }

package com.pokemon.battle.ingest.fetch

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Fetches PokeAPI responses with an on-disk cache. Cache is committed to the repo
 * (see diary 041): the cache IS the fixture set; fresh clones work offline.
 *
 * Politeness: 100ms sleep before every uncached request, custom User-Agent header.
 * Failed responses are NOT written to cache — do not poison with transient 5xx.
 */
class PokeApiClient(
    private val cacheRoot: Path = Path.of("data/raw/pokeapi"),
    private val baseUrl: String = "https://pokeapi.co/api/v2",
    private val userAgent: String = "pokemon-battle-engine/1.0 (github.com/seanaujong/pokemon-battle)",
    private val requestDelayMillis: Long = REQUEST_DELAY_MILLIS,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val httpGet: (String, String) -> HttpResult = ::defaultHttpGet,
) {
    /**
     * Returns the raw JSON body for the given endpoint + slug, fetching once and
     * caching thereafter. Throws [FetchException] on non-2xx responses.
     */
    fun fetch(
        endpoint: String,
        slug: String,
    ): String {
        val cachePath = cacheRoot.resolve(endpoint).resolve("$slug.json")
        if (Files.exists(cachePath)) {
            return Files.readString(cachePath)
        }
        sleep(requestDelayMillis)
        val url = "$baseUrl/$endpoint/$slug"
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
        private const val REQUEST_DELAY_MILLIS = 100L
        private const val BODY_SNIPPET_LENGTH = 200
        private val HTTP_OK_RANGE = 200..299
    }
}

private fun defaultHttpGet(
    url: String,
    userAgent: String,
): PokeApiClient.HttpResult {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("User-Agent", userAgent)
    conn.setRequestProperty("Accept", "application/json")
    return conn.use {
        val status = it.responseCode
        val stream = if (status in 200..299) it.inputStream else it.errorStream
        val body = stream?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
        PokeApiClient.HttpResult(status, body)
    }
}

private inline fun <R> HttpURLConnection.use(block: (HttpURLConnection) -> R): R =
    try {
        block(this)
    } finally {
        disconnect()
    }

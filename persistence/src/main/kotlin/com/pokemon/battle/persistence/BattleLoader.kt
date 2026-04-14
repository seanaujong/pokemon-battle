package com.pokemon.battle.persistence

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Reads [PersistedBattle] records back from a directory. Yields a [Sequence] so
 * analytics consumers can stream large corpora without loading everything at
 * once. Order is file-system-dependent (typically sorted by filename, which
 * equals the battle id).
 *
 * Malformed files raise — callers that need to survive bad data should filter
 * or wrap. Keeping the default strict matches the "fail loud" posture of the
 * rest of the engine.
 */
object BattleLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadAll(directory: Path): Sequence<PersistedBattle> {
        if (!Files.isDirectory(directory)) return emptySequence()
        return Files.list(directory)
            .filter { it.extension == "json" }
            .sorted()
            .toList() // materialize the file list so the Files stream can close; the battles themselves are lazy below
            .asSequence()
            .map { path -> json.decodeFromString(PersistedBattle.serializer(), path.readText()) }
    }
}

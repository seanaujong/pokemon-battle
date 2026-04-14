package com.pokemon.battle.persistence

import com.pokemon.battle.loop.BattleResult
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes each completed battle as a JSON file under [directory]. One file per
 * battle, named `<battleId>.json`. The directory is created on first write if
 * it doesn't exist.
 *
 * Pretty-printed by default — the file is small relative to disk (a typical
 * 10-turn singles battle is a few KB) and human-readable matters more than
 * a few bytes saved.
 */
class FileBattleRecorder(
    private val directory: Path,
    prettyPrint: Boolean = true,
) : BattleRecorder {
    private val json =
        Json {
            this.prettyPrint = prettyPrint
            classDiscriminator = "type"
        }

    override fun record(
        result: BattleResult,
        metadata: BattleMetadata,
    ) {
        Files.createDirectories(directory)
        val persisted = toPersisted(result, metadata)
        val encoded = json.encodeToString(PersistedBattle.serializer(), persisted)
        val path = directory.resolve("${metadata.battleId}.json")
        Files.writeString(path, encoded)
    }
}

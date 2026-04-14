package com.pokemon.battle.server

import com.pokemon.battle.persistence.BattleMetadataFactory
import com.pokemon.battle.persistence.BattleRecorder
import com.pokemon.battle.persistence.FileBattleRecorder
import com.pokemon.battle.server.protocol.PROTOCOL_VERSION
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.nio.file.Path

/**
 * JSONL server entry point.
 *
 * Set `POKEMON_BATTLE_RECORDINGS_DIR` to persist every battle this server runs —
 * the completed [com.pokemon.battle.loop.BattleResult] is written as a
 * [com.pokemon.battle.persistence.PersistedBattle] JSON file in the named
 * directory. Unset or empty means "don't record" (the default no-op). Diary 080.
 */
fun main() {
    val input = BufferedReader(InputStreamReader(System.`in`))
    val output = PrintWriter(System.out, true)

    val recordingsDir = System.getenv("POKEMON_BATTLE_RECORDINGS_DIR")?.takeIf { it.isNotBlank() }
    val (recorder, metadata) =
        if (recordingsDir != null) {
            FileBattleRecorder(Path.of(recordingsDir)) to
                BattleMetadataFactory.forNewBattle(
                    formatTag = "server-live",
                    protocolVersion = PROTOCOL_VERSION,
                    clientInfo = "server",
                )
        } else {
            BattleRecorder.NoOp to null
        }

    ServerSession(input, output, recorder = recorder, metadata = metadata).run()
}

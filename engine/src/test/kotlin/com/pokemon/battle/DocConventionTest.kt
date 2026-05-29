package com.pokemon.battle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.streams.toList as javaStreamToList

/**
 * Enforces the self-contained docs convention from `docs/index.md`: a canonical
 * doc must make sense on its own and **never reference a diary entry from its
 * body**. Diaries are the event log (rationale at the time); canonical docs are
 * the materialized view (current truth). A `diary 099`-style pointer is a
 * temporal reference that rots when the decision is superseded — the documentation
 * analogue of depending on an enum ordinal.
 *
 * **Scope.** Every `*.md` under `docs/` is canonical and checked, except:
 * - `docs/index.md` — the link hub, where inter-doc and diary-log pointers live.
 * - anything under `docs/diaries/` — the event log itself; entries cross-reference freely.
 *
 * The forbidden pattern is a diary *number* (`diary 081`, `diaries 041`, or a
 * `diaries/103-...` path). Numberless process instructions ("file a diary first",
 * "the next diary") are fine — they create history, they don't depend on it.
 */
class DocConventionTest {
    private companion object {
        // A diary reference carrying a number: "diary 81", "Diaries 041", "diaries/103-...".
        private val DIARY_NUMBER_REF = Regex("""([Dd]iar(y|ies)\s+\d+|diaries/\d)""")
    }

    @Test
    fun `canonical docs never reference a diary entry`() {
        val docsDir = findDocsDir()
        val offenders = mutableListOf<String>()

        Files.walk(docsDir).use { stream ->
            stream
                .filter { it.name.endsWith(".md") }
                .filter { it.name != "index.md" }
                .filter { path -> path.none { it.name == "diaries" } }
                .javaStreamToList()
                .forEach { path ->
                    DIARY_NUMBER_REF.findAll(path.readText()).forEach { match ->
                        offenders.add("${docsDir.relativize(path)}: \"${match.value}\"")
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Found ${offenders.size} diary reference(s) in canonical docs:")
                offenders.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Per docs/index.md, a canonical doc must stand alone — it must not reference")
                appendLine("a diary entry. Distill the durable why into present-tense prose and drop the")
                appendLine("diary pointer; the rationale lives in the diary, discoverable via the log, not")
                appendLine("linked from here. (Numberless 'file a diary' instructions are fine.)")
            },
        )
    }

    private fun findDocsDir(): Path {
        val candidates =
            listOf(
                Paths.get("../docs").toAbsolutePath().normalize(),
                Paths.get("docs").toAbsolutePath().normalize(),
            )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("docs directory not found; tried: $candidates")
    }
}

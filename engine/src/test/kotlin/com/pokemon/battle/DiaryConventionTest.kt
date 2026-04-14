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
 * Enforces the iteration-loop step 5 rule from CLAUDE.md: every diary that
 * reaches `Status: Complete` must carry a `## Code review` section. Missing
 * sections are the exact failure mode diary 082 caught — the
 * `docs/diaries/temp/` convention let the review step quietly go missing.
 * Embedding the review in the permanent diary is the fix; this test makes
 * the fix enforceable.
 *
 * **Grandfathering:** only diaries numbered [BASELINE_DIARY] and above are
 * checked. Diaries 001–081 predate the rule. When a new Complete diary lands
 * without a review section, this test fails with a pointer to what's missing.
 */
class DiaryConventionTest {
    companion object {
        /**
         * First diary number subject to the rule. Diary 082 established the
         * convention and contains its own review; everything from here on
         * must follow suit.
         */
        private const val BASELINE_DIARY = 82

        private val STATUS_LINE = Regex("""^\*\*Status:\*\*\s*([^\n]+)""", RegexOption.MULTILINE)
        private val CODE_REVIEW_HEADER = Regex("""^##\s+Code\s+review""", RegexOption.MULTILINE)
        private val DIARY_NUMBER = Regex("""^(\d{3})-""")
    }

    @Test
    fun `every Complete diary numbered 082+ has a Code review section`() {
        val diaryDir = findDiaryDir()
        val offenders = mutableListOf<String>()

        Files.list(diaryDir).use { stream ->
            stream
                .filter { it.name.endsWith(".md") && it.name != "README.md" }
                .javaStreamToList()
                .forEach { path ->
                    val number = DIARY_NUMBER.find(path.name)?.groupValues?.get(1)?.toIntOrNull()
                    if (number == null || number < BASELINE_DIARY) return@forEach
                    val text = path.readText()
                    val status = STATUS_LINE.find(text)?.groupValues?.get(1)?.trim() ?: return@forEach
                    if (!status.contains("Complete", ignoreCase = true)) return@forEach
                    if (!CODE_REVIEW_HEADER.containsMatchIn(text)) {
                        offenders.add(path.name)
                    }
                }
        }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Found ${offenders.size} Complete diary(-ies) numbered $BASELINE_DIARY+ missing a '## Code review' section:")
                offenders.forEach { appendLine("  $it") }
                appendLine()
                appendLine("Per CLAUDE.md iteration-loop step 5, any diary that reaches Status: Complete must")
                appendLine("include a '## Code review' section that walks the 16 diagnostic questions. Missing")
                appendLine("section = the step was skipped. If you reviewed and found nothing, state that")
                appendLine("explicitly ('reviewed, no findings') so the section isn't absent.")
            },
        )
    }

    private fun findDiaryDir(): Path {
        val candidates =
            listOf(
                Paths.get("../docs/diaries").toAbsolutePath().normalize(),
                Paths.get("docs/diaries").toAbsolutePath().normalize(),
            )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("diary directory not found; tried: $candidates")
    }
}

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
 * **Module READMEs.** A module's own README is a local index, not a canonical
 * doc — it may cross-link to sibling code/scripts. But the no-diary rule still
 * applies: a diary pointer rots the same way regardless of which file holds it,
 * so every README outside `docs/` is checked too (see the second test below).
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
        val docs =
            Files.walk(docsDir).use { stream ->
                stream
                    .filter { it.name.endsWith(".md") }
                    .filter { it.name != "index.md" }
                    .filter { path -> path.none { it.name == "diaries" } }
                    .javaStreamToList()
            }
        val offenders = diaryRefsIn(docsDir, docs)

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

    /**
     * A module README may act as a local index, but inherits the no-diary rule
     * (see class doc). Driven off `git ls-files` rather than a filesystem walk so
     * gitignored/vendored trees (e.g. the `scripts/analyst-env` venv) are never
     * scanned — only READMEs we actually maintain. `docs/` is excluded because the
     * canonical-docs test above already covers it (e.g. `docs/skills/README.md`).
     */
    @Test
    fun `module READMEs never reference a diary entry`() {
        val repoRoot = findDocsDir().parent
        val readmes =
            trackedFiles(repoRoot)
                .filter { it.name == "README.md" }
                .filter { path -> path.none { it.name == "docs" } }
        val offenders = diaryRefsIn(repoRoot, readmes)

        assertTrue(
            offenders.isEmpty(),
            buildString {
                appendLine("Found ${offenders.size} diary reference(s) in module README(s):")
                offenders.forEach { appendLine("  $it") }
                appendLine()
                appendLine("A README may act as a local index (cross-linking sibling code/scripts), but")
                appendLine("per docs/index.md it must not reference a diary entry — that pointer rots the")
                appendLine("same way it would in a canonical doc. Drop the diary pointer; the rationale")
                appendLine("lives in the diary, discoverable via the log.")
            },
        )
    }

    private fun diaryRefsIn(
        base: Path,
        files: List<Path>,
    ): List<String> =
        files.flatMap { path ->
            DIARY_NUMBER_REF.findAll(path.readText()).map { match ->
                "${base.relativize(path)}: \"${match.value}\""
            }
        }

    /** Git-tracked files under [repoRoot], as absolute paths. */
    private fun trackedFiles(repoRoot: Path): List<Path> {
        val process =
            ProcessBuilder("git", "ls-files", "-z")
                .directory(repoRoot.toFile())
                .start()
        val stdout = process.inputStream.readBytes().decodeToString()
        val stderr = process.errorStream.readBytes().decodeToString()
        val exit = process.waitFor()
        check(exit == 0) { "git ls-files failed (exit $exit) in $repoRoot: $stderr" }
        // `-z` NUL-delimits paths, so names with spaces or newlines survive intact.
        return stdout.split('\u0000')
            .filter { it.isNotEmpty() }
            .map { repoRoot.resolve(it) }
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

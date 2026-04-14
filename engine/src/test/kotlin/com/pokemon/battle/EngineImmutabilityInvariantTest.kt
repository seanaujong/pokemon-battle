package com.pokemon.battle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.streams.toList as javaStreamToList

/**
 * Enforces the engine's concurrency-safety invariant statically: no `var` declarations
 * live at class-body or top-level scope under `engine/src/main`. Function-local `var`s
 * (accumulators, loop counters) are fine — they never escape the stack frame.
 *
 * Why this matters (diary 070): the engine's ability to run N simultaneous battles
 * inside one JVM depends on the absence of shared mutable state. This is a static
 * property, verifiable structurally, not a dynamic one — a concurrent smoke test can
 * pass a thousand times and still miss the one timing window. Catching the violation
 * at build time via this test is more reliable than any runtime check.
 *
 * The check tracks brace context (class/object body vs function body vs top-level).
 * A `var` declaration at class-body or top-level scope fails the test; the same `var`
 * inside a `fun` body is accepted. Primary constructor `var` parameters (which *are*
 * class-level fields) are also caught because they live inside the class declaration
 * before any opening `{`.
 */
class EngineImmutabilityInvariantTest {
    @Test
    fun `engine source has no top-level or class-level var declarations`() {
        val engineMain = findEngineMain()
        val violations = mutableListOf<String>()

        Files.walk(engineMain).use { stream ->
            stream
                .filter { it.extension == "kt" }
                .javaStreamToList()
                .forEach { file ->
                    violations += scanFile(engineMain.relativize(file).toString(), file.readText())
                }
        }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Found ${violations.size} class-level / top-level var declaration(s):")
                violations.forEach { appendLine("  $it") }
                appendLine()
                appendLine("The engine must stay free of shared mutable state so that N battles can")
                appendLine("run concurrently in one JVM (diary 070). Function-local vars are fine —")
                appendLine("move the mutation into the function body, or use `val` + a functional")
                appendLine("accumulation pattern.")
            },
        )
    }

    private fun findEngineMain(): Path {
        val candidates =
            listOf(
                Paths.get("src/main/kotlin").toAbsolutePath(),
                Paths.get("engine/src/main/kotlin").toAbsolutePath(),
            )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("engine source directory not found; tried: $candidates")
    }

    /**
     * Scan one file, returning violations. Uses a shallow lexer that strips strings /
     * comments, then walks brace-by-brace maintaining a stack of scope kinds. A `var`
     * declaration in any scope other than `FUNCTION` is a violation.
     */
    private fun scanFile(
        relPath: String,
        source: String,
    ): List<String> {
        val lines = stripStringsAndComments(source).lines()
        val scopeStack = ArrayDeque<Scope>().apply { add(Scope.TOP_LEVEL) }
        val violations = mutableListOf<String>()

        for ((idx, rawLine) in lines.withIndex()) {
            val trimmed = rawLine.trim()
            if (scopeStack.last() != Scope.FUNCTION && VAR_DECLARATION.containsMatchIn(trimmed)) {
                violations += "$relPath:${idx + 1}: $trimmed"
            }
            updateScopeStack(rawLine, trimmed, scopeStack)
        }
        return violations
    }

    private fun updateScopeStack(
        rawLine: String,
        trimmed: String,
        scopeStack: ArrayDeque<Scope>,
    ) {
        val pending = pendingScopeForLine(trimmed)
        for (ch in rawLine) {
            when (ch) {
                '{' -> scopeStack.addLast(pending.pollNext() ?: defaultInnerScope(scopeStack.last()))
                '}' -> if (scopeStack.size > 1) scopeStack.removeLast()
            }
        }
    }

    private fun defaultInnerScope(outer: Scope): Scope =
        if (outer == Scope.CLASS_BODY || outer == Scope.TOP_LEVEL) Scope.FUNCTION else outer

    private fun ArrayDeque<Scope>.pollNext(): Scope? = if (isEmpty()) null else removeFirst()

    /**
     * Look at a line's head keywords to decide what kind of scope its next `{` opens.
     * A line may declare multiple scopes (rare); return them in order.
     */
    private fun pendingScopeForLine(line: String): ArrayDeque<Scope> {
        val result = ArrayDeque<Scope>()
        if (CLASS_DECL.containsMatchIn(line)) result += Scope.CLASS_BODY
        if (FUN_DECL.containsMatchIn(line)) result += Scope.FUNCTION
        if (INIT_DECL.containsMatchIn(line)) result += Scope.FUNCTION
        if (COMPANION_DECL.containsMatchIn(line)) result += Scope.CLASS_BODY
        return result
    }

    /** Remove string literals and comments so their braces / `var` tokens can't confuse the scanner. */
    private fun stripStringsAndComments(source: String): String {
        // Block comments first, then line comments, then strings (both triple and single-quoted).
        return source
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("//[^\n]*"), "")
            .replace(Regex("\"\"\"(.|\n)*?\"\"\""), "\"\"")
            .replace(Regex("\"(\\\\.|[^\"\\\\])*\""), "\"\"")
    }

    private enum class Scope { TOP_LEVEL, CLASS_BODY, FUNCTION }

    companion object {
        private val VAR_DECLARATION =
            Regex("""^(private |internal |public |protected )?(override )?var\s+\w""")
        private val CLASS_DECL =
            Regex("""\b(class|object|interface|enum class)\b""")
        private val FUN_DECL = Regex("""\bfun\b""")
        private val INIT_DECL = Regex("""^\s*init\s*\{""")
        private val COMPANION_DECL = Regex("""\bcompanion\s+object\b""")
    }
}

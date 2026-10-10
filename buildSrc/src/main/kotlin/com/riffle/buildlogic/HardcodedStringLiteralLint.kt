package com.riffle.buildlogic

import java.io.File

/**
 * Detects hard-coded English string literals passed directly to Compose [Text] / [BasicText]
 * or assigned to [contentDescription]. Every user-visible string must come from a
 * composeResources [stringResource] call or an Android [R.string] reference so it is
 * translatable.
 *
 * Matches:
 *   - `Text("…")` / `BasicText("…")` — any non-empty, non-punctuation-only literal
 *   - `contentDescription = "…"` — same filter
 *
 * Exclusions:
 *   - Test source sets (`androidTest`, `jvmTest`, `commonTest`, `iosTest`, etc.)
 *   - `buildSrc/` itself
 *   - Lines whose literal contains only symbols / digits / whitespace (not letters)
 *   - Empty string literals
 */
object HardcodedStringLiteralLint {

    private val TEXT_LITERAL: Regex = Regex(
        """(?:BasicText|Text)\s*\(\s*"([^"\\]|\\.)+"""",
    )
    private val CONTENT_DESC_LITERAL: Regex = Regex(
        """contentDescription\s*=\s*"([^"\\]|\\.)+"""",
    )

    /** Literal is only symbols/digits/whitespace — not a translatable English string. */
    private val SYMBOLS_ONLY: Regex = Regex("""^[^a-zA-Z]+$""")

    /** Single-letter string — used as a visual design indicator (e.g. font-size "A" labels), not a translatable string. */
    private val SINGLE_LETTER: Regex = Regex("""^[A-Za-z]$""")

    data class Offender(val file: File, val lineNumber: Int, val line: String) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)}:$lineNumber — ${line.trim()}"
    }

    fun findOffenders(
        scanRoots: List<File>,
        projectRoot: File,
        /** Additional paths (relative to projectRoot) that are exempt from the check. */
        extraAllowlist: List<String> = emptyList(),
    ): List<Offender> {
        val allowedPaths = extraAllowlist.map { projectRoot.resolve(it).absolutePath }

        val testDirPattern = Regex(
            """[/\\](androidTest|jvmTest|commonTest|iosTest|iosAppTests|iosAppUnitTests)[/\\]""",
        )

        return scanRoots
            .asSequence()
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { f -> testDirPattern.containsMatchIn(f.absolutePath) }
            .filterNot { f -> allowedPaths.any { f.absolutePath.startsWith(it) } }
            .flatMap { f ->
                val offenders = mutableListOf<Offender>()
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        val trimmed = line.trimStart()
                        // Skip comment lines
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed

                        val textMatch = TEXT_LITERAL.containsMatchIn(line)
                        val cdMatch = CONTENT_DESC_LITERAL.containsMatchIn(line)
                        if (textMatch || cdMatch) {
                            // Extract the literal value to apply the symbols-only filter.
                            // Two distinct patterns: Text("…") uses '(' and contentDescription = "…" uses '='.
                            val literal = if (textMatch) {
                                Regex("""(?:BasicText|Text)\s*\(\s*"([^"]*)"""").find(line)?.groupValues?.getOrNull(1) ?: ""
                            } else {
                                Regex("""contentDescription\s*=\s*"([^"]*)"""").find(line)?.groupValues?.getOrNull(1) ?: ""
                            }
                            if (literal.isNotEmpty() && !SYMBOLS_ONLY.matches(literal) && !SINGLE_LETTER.matches(literal)) {
                                offenders += Offender(f, idx + 1, line)
                            }
                        }
                    }
                }
                offenders.asSequence()
            }
            .toList()
    }
}

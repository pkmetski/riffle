package com.riffle.buildlogic

import java.io.File

/**
 * Pure detector for `Log.[dweiv]("RIFFLE_…"` literals outside `core/logging`.
 *
 * Lifted out of `build.gradle.kts` so the rule is JUnit-testable. The gradle task
 * `checkRiffleLogTags` is a thin wrapper around [findRiffleLogTagOffenders].
 *
 * Matches `Log.d`/`w`/`e`/`i`/`v` (fully-qualified or not). Excludes `RIFFLE_TEST`
 * (the androidTest-only tag) via a `(?!TEST)` lookahead.
 */
object RiffleLogTagLint {

    val FORBIDDEN_PATTERN: Regex = Regex("""Log\.[dweiv]\("RIFFLE_(?!TEST)""")

    data class Offender(val file: File, val lineNumber: Int, val line: String) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)}:$lineNumber — $line"
    }

    /**
     * Walks [scanRoots] and returns every offending line. A file is skipped if its
     * absolute path starts with [allowedRoot] (the only place RIFFLE_* literals may live).
     */
    fun findRiffleLogTagOffenders(
        scanRoots: List<File>,
        allowedRoot: File,
    ): List<Offender> {
        val allowedPath = allowedRoot.absolutePath
        val offenders = mutableListOf<Offender>()
        scanRoots
            .asSequence()
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.absolutePath.startsWith(allowedPath) }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (FORBIDDEN_PATTERN.containsMatchIn(line)) {
                            offenders += Offender(f, idx + 1, line)
                        }
                    }
                }
            }
        return offenders
    }
}

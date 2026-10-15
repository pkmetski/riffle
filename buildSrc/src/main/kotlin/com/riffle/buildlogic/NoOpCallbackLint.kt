package com.riffle.buildlogic

import java.io.File

/**
 * Detects literal no-op lambdas (`= {}`, `{ _ -> }`, `{ _, _ -> }`, `{ _, _, _ -> }`)
 * passed as arguments inside host files. Host files are the entry points where shared
 * screens are instantiated — a silent default here means a feature gap on that platform.
 *
 * Every intentional no-op in a host file must be a named constant (e.g. `NoOp.playlistAdvanceUnavailable`)
 * that documents the reason. The constant itself is allowed to be defined as `{}` or `{ _, _ -> }`.
 */
object NoOpCallbackLint {

    /** Project-relative paths of host files where literal no-op lambdas are forbidden. */
    val HOST_FILES: Set<String> = setOf(
        "app/src/main/kotlin/com/riffle/app/navigation/MainScreen.kt",
        "app/src/main/kotlin/com/riffle/app/navigation/LibraryNavGraph.kt",
        "app/src/main/kotlin/com/riffle/app/navigation/SettingsNavGraph.kt",
        "app/src/main/kotlin/com/riffle/app/navigation/ReaderNavGraph.kt",
        "app/src/main/kotlin/com/riffle/app/navigation/SourceNavGraph.kt",
        "shared/src/iosMain/kotlin/com/riffle/shared/HomeScreen.kt",
        "shared/src/iosMain/kotlin/com/riffle/shared/LibraryNav.kt",
        "shared/src/iosMain/kotlin/com/riffle/shared/source/SourceOnboardingHost.kt",
    )

    /** Patterns for literal no-op lambdas passed as arguments. */
    private val LITERAL_NOOP_AS_ARGUMENT = Regex(
        """=\s*\{\s*(?:_(?:\s*,\s*_)*)?\s*-?>?\s*\}"""
    )

    data class Offender(
        val file: File,
        val lineNumber: Int,
        val line: String,
    ) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)}:$lineNumber — literal no-op lambda in host file — ${line.trim()}"
    }

    fun findOffenders(
        projectRoot: File,
        hostFiles: Set<String> = HOST_FILES,
    ): List<Offender> {
        val offenders = mutableListOf<Offender>()
        for (relativePath in hostFiles) {
            val file = projectRoot.resolve(relativePath)
            if (!file.exists()) continue
            file.useLines { lines ->
                lines.forEachIndexed { idx, line ->
                    val trimmed = line.trimStart()
                    // Skip comment lines and val/var declarations (those ARE defining named constants).
                    if (trimmed.startsWith("//") ||
                        trimmed.startsWith("*") ||
                        trimmed.startsWith("/*") ||
                        trimmed.startsWith("val ") ||
                        trimmed.startsWith("var ") ||
                        trimmed.startsWith("private val ") ||
                        trimmed.startsWith("private var ")
                    ) return@forEachIndexed
                    if (LITERAL_NOOP_AS_ARGUMENT.containsMatchIn(line)) {
                        offenders += Offender(file, idx + 1, line)
                    }
                }
            }
        }
        return offenders
    }
}

package com.riffle.buildlogic

import java.io.File

/**
 * Enforces OkHttp confinement: `okhttp3.*` imports are only allowed inside
 * `core/net/src/jvmMain` and `core/net/src/androidMain` (which doesn't exist yet —
 * the check no-ops for missing paths). Every other Kotlin source file in the repo
 * must use the Ktor abstraction instead of OkHttp directly.
 *
 * Detection logic lives here (JUnit-testable). The Gradle task wrapper in the root
 * `build.gradle.kts` calls [findOkHttpOutsideCoreNet].
 */
object OkHttpConfinementLint {

    /** Source-tree root prefixes (relative to project root) where `okhttp3.*` imports are permitted. */
    val ALLOWED_ROOTS: List<String> = listOf(
        "core/net/src/jvmMain",
        "core/net/src/androidMain",
    )

    val OKHTTP_IMPORT_PATTERN: Regex = Regex(
        """^\s*import\s+okhttp3\.""",
    )

    data class Offender(
        val file: File,
        val lineNumber: Int,
        val line: String,
    ) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)}:$lineNumber — forbidden okhttp3 import — ${line.trim()}"
    }

    fun findOkHttpOutsideCoreNet(
        projectRoot: File,
        allowedRoots: List<String> = ALLOWED_ROOTS,
    ): List<Offender> {
        val allowedPaths = allowedRoots
            .map { projectRoot.resolve(it).canonicalFile }
            .filter { it.exists() }
            .map { it.toPath() }

        val scanRoots = listOf(
            projectRoot.resolve("app/src"),
            projectRoot.resolve("core"),
        ).filter { it.exists() }

        val offenders = mutableListOf<Offender>()
        scanRoots
            .asSequence()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { f ->
                val path = f.canonicalFile.toPath()
                allowedPaths.any(path::startsWith)
            }
            .filterNot { TEST_SOURCE_SET_PATTERN.containsMatchIn(it.invariantSeparatorsPath) }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (OKHTTP_IMPORT_PATTERN.containsMatchIn(line)) {
                            offenders += Offender(f, idx + 1, line)
                        }
                    }
                }
            }
        return offenders
    }

    private val TEST_SOURCE_SET_PATTERN = Regex("""(?:^|/)src/(?:test|androidTest|[^/]+Test)/""")
}

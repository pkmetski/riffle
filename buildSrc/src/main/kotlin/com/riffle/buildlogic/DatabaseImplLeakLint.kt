package com.riffle.buildlogic

import java.io.File

/**
 * Keeps Room and SQLite implementation APIs private to the database modules.
 *
 * Every other module must depend on DAO contracts and `RiffleDatabaseAccess` instead of importing
 * `RiffleDatabase`, Room, or SQLite APIs. Otherwise engine details leak back across the KMP seam.
 */
object DatabaseImplLeakLint {
    private val IMPLEMENTATION_ROOTS = listOf(
        "core/database",
        "core/database-api",
    )

    val FORBIDDEN_IMPORT_PATTERN: Regex = Regex(
        """^\s*import\s+(?:com\.riffle\.core\.database\.(?:RiffleDatabase(?:\s|$)|\*)|androidx\.(?:room|sqlite)\.)""",
    )

    data class Offender(
        val file: File,
        val lineNumber: Int,
        val line: String,
    ) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)}:$lineNumber — persistence implementation import — ${line.trim()}"
    }

    fun findDatabaseImplLeaks(
        projectRoot: File,
        scanRoots: List<File> = listOf(
            projectRoot.resolve("app/src"),
            projectRoot.resolve("core"),
        ),
    ): List<Offender> {
        val implementationRoots = IMPLEMENTATION_ROOTS.map(projectRoot::resolve)
        return scanRoots
            .asSequence()
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                implementationRoots.any { implementationRoot ->
                    file.toPath().startsWith(implementationRoot.toPath())
                }
            }
            .flatMap { file ->
                file.useLines { lines ->
                    lines.mapIndexedNotNull { index, line ->
                        if (FORBIDDEN_IMPORT_PATTERN.containsMatchIn(line)) {
                            Offender(file, index + 1, line)
                        } else {
                            null
                        }
                    }.toList().asSequence()
                }
            }
            .toList()
    }
}

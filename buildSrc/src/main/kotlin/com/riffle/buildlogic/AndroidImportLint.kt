package com.riffle.buildlogic

import java.io.File

/**
 * Pure detector for `android.*`, `androidx.*` (except `androidx.annotation`), and
 * `java.util.logging` imports inside the multi-platform-core modules. Analogue of
 * [ServerReferenceLint] / [RiffleLogTagLint] — the gradle task `checkNoAndroidImports`
 * is a thin wrapper around [findAndroidImportOffenders].
 *
 * The multi-platform plan (issue #550, ADR forthcoming) carves a set of `core:*`
 * modules that must stay pure-Kotlin so a future KMP target can consume them
 * unchanged. This lint fails CI the moment any of those modules pulls in an
 * Android dependency, catching drift at the import statement before it spreads.
 *
 * The target module list is fixed here — these are the platform-agnostic core
 * modules. They may not exist yet (Phase 0 only installs the guardrail); when a
 * later phase creates the module, this check becomes active for it automatically.
 * Adding a new pure-Kotlin core module means adding its path to [DEFAULT_MODULE_ROOTS].
 */
object AndroidImportLint {

    /**
     * Module source roots to scan, project-relative. Phase 0 lists the set from
     * the multi-platform plan (#550). Later phases physically create the modules;
     * this list gates them the moment they land.
     */
    val DEFAULT_MODULE_ROOTS: List<String> = listOf(
        "core/models",
        "core/net",
        "core/sources",
        "core/sync",
        "core/annotations",
    )

    /**
     * Forbidden import prefixes. `androidx.annotation` is explicitly allowed — it
     * is pure-JVM (no Android runtime code) and every platform-agnostic module can
     * safely depend on it for `@VisibleForTesting`, `@IntRange`, etc.
     */
    val FORBIDDEN_IMPORT_PATTERN: Regex = Regex(
        """^\s*import\s+(android\.[A-Za-z_][A-Za-z0-9_.]*|androidx\.(?!annotation(\.|;|\s|$))[A-Za-z_][A-Za-z0-9_.]*|java\.util\.logging(\.[A-Za-z_][A-Za-z0-9_.]*)?)""",
    )

    /**
     * Files inside a scanned module that are grandfathered from the lint. Empty
     * to start (per #550 — "empty allowlist to start; grows only with
     * justification"). Every future entry needs a one-line justification.
     */
    val ALLOWLIST: Set<String> = emptySet()

    data class Offender(
        val file: File,
        val lineNumber: Int,
        val line: String,
    ) {
        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)}:$lineNumber — forbidden import — ${line.trim()}"
    }

    /**
     * Walks each of [moduleRoots] (project-relative paths under [projectRoot]) and
     * returns every offending import line. Missing module directories are skipped
     * — the guardrail is future-ready and no-ops for modules that don't exist yet.
     * Test source sets (`/src/test/`, `/src/androidTest/`) are skipped; fakes and
     * fixtures legitimately reference Android APIs when hosted inside the Android
     * app for instrumentation. Files in [allowlist] (paths relative to
     * [projectRoot]) are skipped entirely.
     */
    fun findAndroidImportOffenders(
        projectRoot: File,
        moduleRoots: List<String> = DEFAULT_MODULE_ROOTS,
        allowlist: Set<String> = ALLOWLIST,
    ): List<Offender> {
        val offenders = mutableListOf<Offender>()
        moduleRoots
            .asSequence()
            .map { projectRoot.resolve(it) }
            .filter { it.exists() }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot {
                val p = it.absolutePath
                p.contains("/src/test/") || p.contains("/src/androidTest/")
            }
            .forEach { f ->
                val rel = f.relativeTo(projectRoot).path
                if (rel in allowlist) return@forEach
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (FORBIDDEN_IMPORT_PATTERN.containsMatchIn(line)) {
                            offenders += Offender(f, idx + 1, line)
                        }
                    }
                }
            }
        return offenders
    }
}

package com.riffle.buildlogic

import java.io.File

/**
 * Pure detector for `Server`-typed identifiers (`\bServer[A-Z]…`) and the bare
 * literal `serverId` outside a grandfathered allowlist. Analogue of
 * `RiffleLogTagLint` — the gradle task `checkNoServerReferences` is a thin
 * wrapper around [findServerReferenceOffenders].
 *
 * The taxonomy is now Source/Service (ADR 0041). New Kotlin sites introducing
 * `ServerFoo` or `serverId` are treated as drift; the allowlist grandfathers
 * every file that legitimately carries the old spelling today (Storyteller-
 * adjacent internals, the `ServerType` enum, historical Room migration SQL
 * that must remain verbatim, and the few identifier holdouts).
 */
object ServerReferenceLint {

    val SERVER_IDENT_PATTERN: Regex = Regex("""\bServer[A-Z]""")
    val SERVER_ID_PATTERN: Regex = Regex("""\bserverId\b""")

    /**
     * Project-relative paths grandfathered from the lint. Drop entries as follow-up
     * PRs migrate each file to Source/Service spelling. Add a new entry only when a
     * file legitimately refers to "server" (ABS URL forms, AbsServer HTTP client
     * types, Storyteller service internals, or the ServerType enum itself).
     */
    val ALLOWLIST: Set<String> = setOf(
        // The ServerType enum itself is the taxonomy hinge — the enum name stays.
        "core/domain/src/main/kotlin/com/riffle/core/domain/ServerType.kt",
        // Room database + migrations reference historical `serverId` columns and
        // `servers` table in SQL that must remain verbatim to preserve migration
        // history. Identifier holdouts (ServerRepository comment) live here too.
        "core/database/src/main/kotlin/com/riffle/core/database/RiffleDatabase.kt",
        // Domain models that still carry `serverType: ServerType` parameters.
        "core/domain/src/main/kotlin/com/riffle/core/domain/Source.kt",
        "core/domain/src/main/kotlin/com/riffle/core/domain/SourceRepository.kt",
        "core/domain/src/main/kotlin/com/riffle/core/domain/PendingSource.kt",
        "core/domain/src/main/kotlin/com/riffle/core/domain/ReadingSession.kt",
        "core/domain/src/main/kotlin/com/riffle/core/domain/ProgressReconciler.kt",
        "core/domain/src/main/kotlin/com/riffle/core/domain/ProgressSyncController.kt",
        "core/domain/src/main/kotlin/com/riffle/core/domain/HighlightsResumeStore.kt",
        // Data layer: Source repo carries the serverType field; Storyteller +
        // WebDAV internals + reading-session repo pass `serverType` through.
        "core/data/src/main/kotlin/com/riffle/core/data/SourceRepositoryImpl.kt",
        "core/data/src/main/kotlin/com/riffle/core/data/StorytellerReadaloudSyncer.kt",
        "core/data/src/main/kotlin/com/riffle/core/data/ReadaloudReviewRepositoryImpl.kt",
        "core/data/src/main/kotlin/com/riffle/core/data/ReadaloudMatchingService.kt",
        "core/data/src/main/kotlin/com/riffle/core/data/ReadingSessionRepositoryImpl.kt",
        "core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt",
        "core/data/src/main/kotlin/com/riffle/core/data/PreferenceStoreFactories.kt",
        // Network clients — ABS and Storyteller HTTP surfaces carry `serverType`.
        "core/network/src/main/kotlin/com/riffle/core/network/AbsApiClient.kt",
        "core/network/src/main/kotlin/com/riffle/core/network/StorytellerApi.kt",
        "core/network/src/main/kotlin/com/riffle/core/network/StorytellerApiClient.kt",
        "core/network/src/main/kotlin/com/riffle/core/network/NetworkResult.kt",
        // Catalog abs adapter carries ServerException.
        "core/catalog/src/main/kotlin/com/riffle/core/catalog/abs/CatalogException.kt",
        // App-side view-models + screens that thread ServerType through and the
        // reader session `ServerJumpCoordinator` identifier holdout.
        "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt",
        "app/src/main/kotlin/com/riffle/app/feature/reader/session/ServerJumpCoordinator.kt",
        "app/src/main/kotlin/com/riffle/app/feature/reader/session/ReaderSessionLifecycle.kt",
        "app/src/main/kotlin/com/riffle/app/feature/reader/session/PositionOrchestrator.kt",
        "app/src/main/kotlin/com/riffle/app/feature/settings/SettingsViewModel.kt",
        "app/src/main/kotlin/com/riffle/app/feature/settings/SettingsScreen.kt",
        // Split-out sections/drill-in for Settings (post-#XXX). Same ServerType/serverType usage
        // as SettingsScreen — routing per-source rendering and identifying the Storyteller Service.
        "app/src/main/kotlin/com/riffle/app/feature/settings/sections/SourcesSection.kt",
        "app/src/main/kotlin/com/riffle/app/feature/settings/sections/ReadaloudSection.kt",
        "app/src/main/kotlin/com/riffle/app/feature/settings/readaloud/ReadaloudSettingsScreen.kt",
        "app/src/main/kotlin/com/riffle/app/feature/settings/readaloud/ReadaloudMatchesViewModel.kt",
        "app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt",
        "app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerViewModel.kt",
        "app/src/main/kotlin/com/riffle/app/feature/navigation/NavigationDrawerComposable.kt",
        "app/src/main/kotlin/com/riffle/app/feature/server/AddSourceViewModel.kt",
        "app/src/main/kotlin/com/riffle/app/feature/annotations/AnnotationsListViewModel.kt",
    )

    data class Offender(
        val file: File,
        val lineNumber: Int,
        val line: String,
        val kind: Kind,
    ) {
        enum class Kind(val label: String) {
            SERVER_IDENT("Server-typed identifier (\\bServer[A-Z])"),
            SERVER_ID("bare `serverId` literal"),
        }

        fun render(projectRoot: File): String =
            "${file.relativeTo(projectRoot)}:$lineNumber — ${kind.label} — ${line.trim()}"
    }

    /**
     * Walks [scanRoots] and returns every offending line. Files in [allowlist]
     * (paths relative to [projectRoot]) are skipped entirely. Test source sets
     * (`/src/test/`, `/src/androidTest/`) are also skipped — fixtures legitimately
     * reference the taxonomy under test.
     */
    fun findServerReferenceOffenders(
        scanRoots: List<File>,
        projectRoot: File,
        allowlist: Set<String> = ALLOWLIST,
    ): List<Offender> {
        val offenders = mutableListOf<Offender>()
        scanRoots
            .asSequence()
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
                        val trimmed = line.trimStart()
                        // Skip comment-only lines so KDoc / historical notes don't trip.
                        if (trimmed.startsWith("//") ||
                            trimmed.startsWith("*") ||
                            trimmed.startsWith("/*")
                        ) return@forEachIndexed
                        if (SERVER_IDENT_PATTERN.containsMatchIn(line)) {
                            offenders += Offender(f, idx + 1, line, Offender.Kind.SERVER_IDENT)
                        }
                        if (SERVER_ID_PATTERN.containsMatchIn(line)) {
                            offenders += Offender(f, idx + 1, line, Offender.Kind.SERVER_ID)
                        }
                    }
                }
            }
        return offenders
    }
}

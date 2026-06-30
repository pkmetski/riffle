plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Enforces that `Log.[dweiv]("RIFFLE_…"` literals only live in core/logging.
// Anything else: route the call through `Logger` + `LogChannel`. See #337.
// Excludes RIFFLE_TEST (androidTest tag).
tasks.register("checkRiffleLogTags") {
    group = "verification"
    description = "Fails if any RIFFLE_* log-tag literal escapes core/logging."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val forbidden = Regex("""Log\.[dweiv]\("RIFFLE_(?!TEST)""")
        val allowedRoot = layout.projectDirectory.dir("core/logging/src/main").asFile.absolutePath
        val scanRoots = listOf(
            layout.projectDirectory.dir("app/src").asFile,
            layout.projectDirectory.dir("core").asFile,
        ).filter { it.exists() }

        val offenders = mutableListOf<String>()
        scanRoots
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.absolutePath.startsWith(allowedRoot) }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (forbidden.containsMatchIn(line)) {
                            offenders += "${f.relativeTo(layout.projectDirectory.asFile)}:${idx + 1} — $line"
                        }
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "RIFFLE_* log-tag literals must live in core/logging/.../LogChannel.kt.\n" +
                    "Route these through Logger + LogChannel (see #337):\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}

// Enforces that direct `System.currentTimeMillis()` / `System.nanoTime()` and `Dispatchers.IO/Main/Default`
// calls route through the Clock + DispatcherProvider seams (#338). Existing sites are grandfathered via
// the allowlist below; new sites — anywhere else — fail the build. Drop entries as bulk-sweep follow-up
// PRs migrate each file.
tasks.register("checkRiffleInfraSeams") {
    group = "verification"
    description = "Fails if Clock / DispatcherProvider are bypassed (System.currentTimeMillis / Dispatchers.[IMD]) outside the allowlist."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val clockPattern = Regex("""\bSystem\.(currentTimeMillis|nanoTime)\(\)""")
        // Match Dispatchers.IO / .Main / .Main.immediate / .Default — but not Dispatchers.Unconfined and
        // not type references like `kotlinx.coroutines.Dispatchers` inside KDoc/imports. Word-boundary
        // before `Dispatchers` excludes nothing real and keeps regex simple.
        val dispatcherPattern = Regex("""\bDispatchers\.(IO|Main|Default)\b""")

        // Files allowed to mention the literals — seams + grandfathered sites pending follow-up PRs.
        val allowlist = setOf(
            // Seam interfaces + impls.
            "core/domain/src/main/kotlin/com/riffle/core/domain/Clock.kt",
            "core/domain/src/main/kotlin/com/riffle/core/domain/DispatcherProvider.kt",
            "core/domain/src/main/kotlin/com/riffle/core/domain/SystemClock.kt",
            "core/domain/src/main/kotlin/com/riffle/core/domain/DefaultDispatcherProvider.kt",
            // ---- Grandfathered — Clock sweep follow-up.
            "core/data/src/main/kotlin/com/riffle/core/data/AnnotationStoreImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/LibraryRepositoryImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/ReadaloudResumeStoreImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/ReadingSessionRepositoryImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/TimestampedPositionStore.kt",
            // ---- Grandfathered — DispatcherProvider sweep follow-up (core/data).
            "core/data/src/main/kotlin/com/riffle/core/data/AppUpdateRepositoryImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/AudiobookBundleDownloader.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/AudiobookDownloadRepositoryImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/KeystoreTokenStorage.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/LocalStoreImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/LocalStoreMigrator.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/ReadaloudAudioRepositoryImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/ServerFilesCleanerImpl.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/StorytellerSidecarFetcher.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/WebDavAnnotationSyncTarget.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/di/DataModule.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/di/modules/SyncModule.kt",
            // ---- Grandfathered — DispatcherProvider sweep follow-up (app/feature).
            "app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookController.kt",
            "app/src/main/kotlin/com/riffle/app/feature/navigation/HomeScreen.kt",
            "app/src/main/kotlin/com/riffle/app/feature/navigation/HomeViewModel.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderViewModel.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/autoscroll/AutoScrollController.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/controllers/SearchController.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/presenter/ReadiumPresenter.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/PlayerCoordinator.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudController.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/ReadaloudStreamingSessionFactory.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/readaloud/StreamingAudioDownloader.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/session/ReadaloudSession.kt",
        )

        val scanRoots = listOf(
            layout.projectDirectory.dir("app/src/main").asFile,
            layout.projectDirectory.dir("core").asFile,
        ).filter { it.exists() }

        val offenders = mutableListOf<String>()
        scanRoots
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            // Only enforce on production source — tests legitimately reference the literals in fakes.
            .filterNot { it.absolutePath.contains("/src/test/") || it.absolutePath.contains("/src/androidTest/") }
            .forEach { f ->
                val rel = f.relativeTo(layout.projectDirectory.asFile).path
                if (rel in allowlist) return@forEach
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        // Skip comment-only lines so doc-comments mentioning the literals don't trip.
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                        if (clockPattern.containsMatchIn(line)) {
                            offenders += "$rel:${idx + 1} — System.currentTimeMillis/nanoTime: route through Clock"
                        }
                        if (dispatcherPattern.containsMatchIn(line)) {
                            offenders += "$rel:${idx + 1} — Dispatchers.[IMD]: route through DispatcherProvider"
                        }
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Direct time/dispatcher access bypasses the Clock + DispatcherProvider seams (#338).\n" +
                    "Inject `Clock` and use `clock.nowMs()` / `clock.nowNs()` instead of `System.currentTimeMillis()` / `System.nanoTime()`.\n" +
                    "Inject `DispatcherProvider` and use `dispatchers.io` / `.main` / `.default` instead of `Dispatchers.IO` / `.Main` / `.Default`.\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}

// Enforces that paged/vertical EPUB-reader code talks to its WebView through `RendererBridge`
// (#331), so a NEW JS injection lands as a typed bridge call + capability registration — not as
// another `evaluateJavascript(` scattered through the screen. Continuous mode owns a separate
// WebView pipeline (ContinuousReaderView / ChapterWebView / ContinuousScriptInjector /
// ContinuousStyleInjector) and is explicitly out of scope; the allowlist below carries that
// boundary.
tasks.register("checkRendererBridgeUsage") {
    group = "verification"
    description = "Fails if `evaluateJavascript(` is called outside the renderer-bridge package (excludes continuous mode)."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val forbidden = Regex("""\bevaluateJavascript\s*\(""")
        val bridgePackageRoot = layout.projectDirectory.dir(
            "app/src/main/kotlin/com/riffle/app/feature/reader/renderer",
        ).asFile.absolutePath
        // Continuous mode has its own custom WebViews — out of scope per the issue.
        val continuousAllowlist = setOf(
            "app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebView.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderView.kt",
        )
        val scanRoots = listOf(
            layout.projectDirectory.dir("app/src/main").asFile,
        ).filter { it.exists() }

        val offenders = mutableListOf<String>()
        scanRoots
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.absolutePath.startsWith(bridgePackageRoot) }
            .forEach { f ->
                val rel = f.relativeTo(layout.projectDirectory.asFile).path
                if (rel in continuousAllowlist) return@forEach
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                        if (forbidden.containsMatchIn(line)) {
                            offenders += "$rel:${idx + 1} — $line"
                        }
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "evaluateJavascript( must go through RendererBridge in paged/vertical mode (#331).\n" +
                    "Add a typed bridge method (and a RendererCapability if it's a per-page install)\n" +
                    "instead of calling fragment.evaluateJavascript directly:\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}

// Make it part of the normal `./gradlew check` run.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkRiffleLogTags"))
        dependsOn(rootProject.tasks.named("checkRiffleInfraSeams"))
        dependsOn(rootProject.tasks.named("checkRendererBridgeUsage"))
    }
}

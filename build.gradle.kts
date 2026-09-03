import com.riffle.buildlogic.AndroidImportLint
import com.riffle.buildlogic.CheckTranslationsTask
import com.riffle.buildlogic.CreateTranslationTask
import com.riffle.buildlogic.DatabaseImplLeakLint
import com.riffle.buildlogic.OkHttpConfinementLint
import com.riffle.buildlogic.RiffleLogTagLint
import com.riffle.buildlogic.ServerReferenceLint
import com.riffle.buildlogic.TestGuardrailLint

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
}

// ktor-client-okhttp:3.1.3 pulls in okhttp-sse:4.12.0 as a transitive dep.
// That jar references okhttp3.internal.Util, removed in OkHttp 5.x, breaking R8.
// Force okhttp-sse to the same 5.x version used for the main OkHttp artifact so
// the SSE classes exist but the internal reference is gone.
subprojects {
    configurations.all {
        resolutionStrategy {
            force("com.squareup.okhttp3:okhttp-sse:${libs.versions.okhttp.get()}")
        }
    }
}

// Enforces that `Log.[dweiv]("RIFFLE_…"` literals only live in core/logging.
// Anything else: route the call through `Logger` + `LogChannel`. See #337.
// Excludes RIFFLE_TEST (androidTest tag). Detection logic lives in
// buildSrc/.../RiffleLogTagLint.kt so it's JUnit-testable (#347).
tasks.register("checkRiffleLogTags") {
    group = "verification"
    description = "Fails if any RIFFLE_* log-tag literal escapes core/logging."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val projectRoot = layout.projectDirectory.asFile
        val offenders = RiffleLogTagLint.findRiffleLogTagOffenders(
            scanRoots = listOf(
                layout.projectDirectory.dir("app/src").asFile,
                layout.projectDirectory.dir("core").asFile,
            ),
            allowedRoot = layout.projectDirectory.dir("core/logging/src").asFile,
        )
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "RIFFLE_* log-tag literals must live in core/logging/.../LogChannel.kt.\n" +
                    "Route these through Logger + LogChannel (see #337):\n" +
                    offenders.joinToString("\n") { it.render(projectRoot) },
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
            // core:common KMP — SystemClock is the production Clock impl; permitted in jvmMain.
            "core/common/src/jvmMain/kotlin/com/riffle/core/common/SystemClock.kt",
            // ---- Grandfathered — Clock sweep follow-up.
            "app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt",
            "app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt",
            "app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt",
            // Logger core stamps ISO timestamps for `d`/`w`/`e` calls; routing Clock through the
            // logger primitive would invert the dependency direction. Grandfathered.
            "core/logging/src/androidMain/kotlin/com/riffle/core/logging/AndroidLogger.kt",
            // ---- Grandfathered — DispatcherProvider sweep follow-up. LocalFiles ingestion
            // pipeline (#475) does direct SAF file I/O and needs Dispatchers.IO. Migrate when the
            // rest of the LocalFiles layer routes through DispatcherProvider.
            "core/data/src/androidMain/kotlin/com/riffle/core/data/localfiles/AndroidCopyInService.kt",
            "core/data/src/androidMain/kotlin/com/riffle/core/data/localfiles/SafFolderWalker.kt",
            // CBZ reader dispatches: archive I/O (Dispatchers.IO), image decode for produceState
            // in the reader Compose (Dispatchers.IO), and panel-detector prefetch
            // (Dispatchers.Default). Same rationale as the AudiobookPlayerViewModel entry —
            // migrate once the reader layer routes through DispatcherProvider.
            "app/src/main/kotlin/com/riffle/app/feature/reader/cbz/CbzReaderViewModel.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/cbz/CbzReaderScreen.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/cbz/CbzThumbnailStrip.kt",
            // ---- Grandfathered when CI enforcement of these lints was turned on (the custom
            // checks were wired into `check`, which no CI job ran — drift below accumulated
            // unenforced). Same sweep-follow-up rationale as the blocks above.
            // DefaultDispatcherProvider moved main → jvmMain in the core carve-out (#576).
            "core/domain/src/jvmMain/kotlin/com/riffle/core/domain/DefaultDispatcherProvider.kt",
            // iOS DispatcherProvider implementation — maps abstract dispatcher names to platform
            // dispatchers. This IS the seam; it's the leaf that calls Dispatchers.* directly.
            "core/domain/src/iosMain/kotlin/com/riffle/core/domain/IosDispatcherProvider.kt",
            // Catalog adapters: network/parse work pinned to Dispatchers.IO/Default.
            "core/catalog-chitanka/src/main/kotlin/com/riffle/core/catalog/chitanka/ChitankaCatalog.kt",
            "core/catalog-gutenberg/src/main/kotlin/com/riffle/core/catalog/gutenberg/GutenbergCatalog.kt",
            "core/catalog-komga/src/main/kotlin/com/riffle/core/catalog/komga/KomgaCatalog.kt",
            // core:data — file I/O, connectivity callbacks, sync timestamps.
            // Developer options PAT store wraps EncryptedSharedPreferences (blocking disk I/O).
            "core/data/src/androidMain/kotlin/com/riffle/core/data/developer/DeveloperOptionsRepositoryImpl.kt",
            "core/data/src/androidMain/kotlin/com/riffle/core/data/ConnectivityObserverImpl.kt",
            "core/data/src/androidMain/kotlin/com/riffle/core/data/SourceRepositoryImpl.kt",
            "core/data/src/androidMain/kotlin/com/riffle/core/data/absbookmark/AbsBookmarkAnnotationSyncTarget.kt",
            "core/data/src/androidMain/kotlin/com/riffle/core/data/localfiles/CopyCoverImageUseCase.kt",
            "core/data/src/androidMain/kotlin/com/riffle/core/data/localfiles/LocalFilesScanner.kt",
            // Reader/session/export layer — same reader-layer rationale as the CBZ entries.
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/FigureZoomOverlay.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/highlights/HighlightsPdfExporter.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/session/ReaderSessionLifecycle.kt",
            "app/src/main/kotlin/com/riffle/app/feature/source/localfiles/PdfiumPdfMetadataExtractor.kt",
            // core:database KMP factory — Room's setQueryCoroutineContext() requires a direct
            // CoroutineContext; injecting DispatcherProvider here would invert the dependency graph.
            "core/database/src/nonIosMain/kotlin/com/riffle/core/database/RiffleDatabaseFactory.kt",
            "core/database/src/nonIosMain/kotlin/com/riffle/core/database/RiffleDatabaseBuilderExt.kt",
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
            .filterNot { it.absolutePath.contains("/src/test/") || it.absolutePath.contains("/src/androidTest/") || it.absolutePath.contains("/src/androidDeviceTest/") || it.absolutePath.contains("/src/androidHostTest/") || it.absolutePath.contains("/src/jvmTest/") || it.absolutePath.contains("/src/commonTest/") }
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
        // Continuous mode has its own custom WebViews — out of scope per the issue. The last two
        // entries are sanctioned non-continuous seams; anything new belongs in RendererBridge.
        val continuousAllowlist = setOf(
            "app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebView.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousReaderView.kt",
            // Continuous-mode controllers/binder drive the same custom ChapterWebViews.
            "app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousDecorationController.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/ContinuousWindowController.kt",
            "app/src/main/kotlin/com/riffle/app/feature/reader/ChapterWebViewBinder.kt",
            // Cadence's continuous chapter-load hook receives a ChapterWebView (issue #403).
            "app/src/main/kotlin/com/riffle/app/feature/reader/EpubReaderScreen.kt",
            // WebViewFiguresInRangeResolver is unwired scaffolding (DI binds the Noop); it must
            // move behind RendererBridge when the WebView seam that wires it lands.
            "app/src/main/kotlin/com/riffle/app/feature/reader/FiguresInRangeResolver.kt",
            // ADR 0056: the presenter's typed suspend wrapper around the Readium navigator's
            // evaluateJavascript IS the sanctioned seam for emphasis-span injection.
            "app/src/main/kotlin/com/riffle/app/feature/reader/presenter/ReadiumPresenter.kt",
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

// Enforces the Source/Service taxonomy (ADR 0049, #443): fails if a Kotlin file
// outside the grandfathered allowlist introduces a `\bServer[A-Z]` identifier
// (e.g. ServerType, ServerRepository) or the bare literal `serverId`. Test source
// sets and comment-only lines are skipped. Detection logic lives in
// buildSrc/.../ServerReferenceLint.kt so it's JUnit-testable.
tasks.register("checkNoServerReferences") {
    group = "verification"
    description = "Fails if new `\\bServer[A-Z]` identifiers or bare `serverId` literals leak in outside the Source/Service allowlist."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val projectRoot = layout.projectDirectory.asFile
        val offenders = ServerReferenceLint.findServerReferenceOffenders(
            scanRoots = listOf(
                layout.projectDirectory.dir("app/src").asFile,
                layout.projectDirectory.dir("core").asFile,
            ),
            projectRoot = projectRoot,
        )
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "The taxonomy is Source/Service (ADR 0049). Rename `ServerFoo` → `SourceFoo` / `ServiceFoo`\n" +
                    "and `serverId` → `sourceId` at the introducing site, or (if the file legitimately\n" +
                    "belongs to Storyteller-adjacent internals / historical migration SQL) add it to\n" +
                    "ServerReferenceLint.ALLOWLIST with a one-line justification.\n" +
                    offenders.joinToString("\n") { it.render(projectRoot) },
            )
        }
    }
}

// Enforces OkHttp confinement (issue #631): `okhttp3.*` imports are only allowed inside
// `core/net/src/jvmMain`. Every other production Kotlin file must use the Ktor abstraction.
// Detection logic lives in buildSrc/.../OkHttpConfinementLint.kt.
tasks.register("checkNoOkHttpOutsideCoreNet") {
    group = "verification"
    description = "Fails if any production source outside core/net/src/jvmMain imports okhttp3.*."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val projectRoot = layout.projectDirectory.asFile
        val offenders = OkHttpConfinementLint.findOkHttpOutsideCoreNet(projectRoot)
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "OkHttp is confined to core/net/src/jvmMain. Use the Ktor HttpClient abstraction " +
                    "instead of importing okhttp3.* directly:\n" +
                    offenders.joinToString("\n") { it.render(projectRoot) },
            )
        }
    }
}

// Enforces the multi-platform-core boundary (#550): fails if any module under the
// platform-agnostic core roots (core:models, core:net, core:sources, core:sync,
// core:annotations) imports `android.*`, `androidx.*` (except androidx.annotation),
// or `java.util.logging`. Empty allowlist to start; grows only with justification.
// The listed modules may not exist yet — the check no-ops for missing directories
// and activates automatically as later phases create each module. Detection logic
// lives in buildSrc/.../AndroidImportLint.kt so it's JUnit-testable.
tasks.register("checkNoAndroidImports") {
    group = "verification"
    description = "Fails if platform-agnostic core modules import android.*/androidx.* (except androidx.annotation) or java.util.logging."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val projectRoot = layout.projectDirectory.asFile
        val offenders = AndroidImportLint.findAndroidImportOffenders(projectRoot)
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Platform-agnostic core modules must stay pure-Kotlin (#550).\n" +
                    "Remove the Android/androidx/java.util.logging import, or move the code to an\n" +
                    "Android-hosting module (core:data, core:network, app, etc.). Only\n" +
                    "`androidx.annotation` is allowed inside the multi-platform core.\n" +
                    offenders.joinToString("\n") { it.render(projectRoot) },
            )
        }
    }
}

// Treats every @Test function as a guardrail: a test that disappears relative to the merge base
// with main — deleted or renamed — fails the build unless a commit message on the branch declares
// it with a `Removed-test: <exact test name>` trailer. Tests pin behavioral claims; the chapter-map
// section-flood regression shipped by renaming three pinning tests to assert the opposite inside a
// large feature diff. The trailer doesn't bypass judgment — it forces the retired claim to be
// visible in `git log` and the PR instead of buried in a test-file diff. Detection logic lives in
// buildSrc/.../TestGuardrailLint.kt; see AGENTS.md "Don't blindly update tests".
tasks.register("checkTestGuardrails") {
    group = "verification"
    description = "Fails if @Test functions disappeared vs origin/main without a Removed-test: commit trailer."
    notCompatibleWithConfigurationCache("invokes git at execution time")

    doLast {
        val projectRoot = layout.projectDirectory.asFile
        fun git(vararg args: String): String? {
            val process = ProcessBuilder(listOf("git") + args)
                .directory(projectRoot)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            return if (process.waitFor() == 0) stdout else null
        }

        val base = git("merge-base", "HEAD", "origin/main")?.trim()
            ?: git("merge-base", "HEAD", "main")?.trim()
        if (base == null) {
            logger.warn("checkTestGuardrails: no merge base with origin/main (shallow clone or missing remote) — skipping.")
            return@doLast
        }
        val head = git("rev-parse", "HEAD")?.trim()
        if (head == null || base == head) return@doLast

        val oldTests = mutableMapOf<String, Set<String>>()
        val newTests = mutableMapOf<String, Set<String>>()
        val nameStatus = git("diff", "--name-status", "-M", base, "HEAD") ?: return@doLast
        for (line in nameStatus.lineSequence().filter { it.isNotBlank() }) {
            val parts = line.split('\t')
            val status = parts[0]
            val oldPath = parts.getOrNull(1) ?: continue
            val newPath = if (status.startsWith("R") || status.startsWith("C")) parts.getOrNull(2) else oldPath
            if (!status.startsWith("A") && TestGuardrailLint.isTestSourceFile(oldPath)) {
                git("show", "$base:$oldPath")?.let { oldTests[oldPath] = TestGuardrailLint.extractTestNames(it) }
            }
            if (!status.startsWith("D") && newPath != null && TestGuardrailLint.isTestSourceFile(newPath)) {
                git("show", "$head:$newPath")?.let { newTests[newPath] = TestGuardrailLint.extractTestNames(it) }
            }
        }

        val declared = TestGuardrailLint.parseDeclaredRemovals(git("log", "--format=%B", "$base..HEAD").orEmpty())
        val offenders = TestGuardrailLint.findUndeclaredRemovals(oldTests, newTests, declared)
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Tests are guardrails: these @Test functions exist at the merge base with main but not on this " +
                    "branch. If retiring each behavioral claim is intentional, declare it with a commit-message " +
                    "trailer line `Removed-test: <exact test name>` and justify the change in the commit body / " +
                    "PR (see AGENTS.md \"Don't blindly update tests\"):\n" +
                    offenders.joinToString("\n") { "  ${it.render()}" },
            )
        }
    }
}

// Keeps Room/SQLite implementation APIs behind the persistence boundary (ADR 0048, #555).
// DAO consumers use RiffleDatabaseAccess and the DAO/entity contracts.
tasks.register("checkNoDatabaseImplLeak") {
    group = "verification"
    description = "Fails if code outside the database modules imports Room/SQLite implementation APIs."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val projectRoot = layout.projectDirectory.asFile
        val offenders = DatabaseImplLeakLint.findDatabaseImplLeaks(projectRoot)
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Persistence implementation APIs are private to core:database and " +
                    "core:database-api (ADR 0048, #555). Depend on RiffleDatabaseAccess and " +
                    "the DAO/entity contracts instead:\n" +
                    offenders.joinToString("\n") { it.render(projectRoot) },
            )
        }
    }
}

// Keeps localized string files complete when new user-facing resources are added.
// Add a locale with `./gradlew createTranslation -Plocale=es-rES` (or `make translation LOCALE=es-rES`),
// fill the generated strings, then run this check.
tasks.register<CheckTranslationsTask>("checkTranslations") {
    group = "verification"
    description = "Fails if localized Android strings are missing, blank, or stale."
    resRoot.set(layout.projectDirectory.dir("app/src/main/res"))
    projectRoot.set(layout.projectDirectory)
}

val translationLocale = providers.gradleProperty("locale")
tasks.register<CreateTranslationTask>("createTranslation") {
    group = "localization"
    description = "Creates or updates app/src/main/res/values-<locale>/strings.xml with missing translatable keys."
    resRoot.set(layout.projectDirectory.dir("app/src/main/res"))
    projectRoot.set(layout.projectDirectory)
    locale.set(translationLocale)
}

// Aggregate for CI: the static lints plus the test-guardrail check. The CI Lint job runs this
// explicitly — module `check` tasks (which also depend on these) are never invoked on CI, where
// unit tests run via `./gradlew test`.
tasks.register("riffleChecks") {
    group = "verification"
    description = "Runs all custom Riffle lint/guardrail checks."
    dependsOn(
        "checkRiffleLogTags",
        "checkRiffleInfraSeams",
        "checkRendererBridgeUsage",
        "checkNoServerReferences",
        "checkNoAndroidImports",
        "checkNoDatabaseImplLeak",
        "checkNoOkHttpOutsideCoreNet",
        "checkTranslations",
        "checkTestGuardrails",
    )
}

// Make it part of the normal `./gradlew check` run.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkRiffleLogTags"))
        dependsOn(rootProject.tasks.named("checkRiffleInfraSeams"))
        dependsOn(rootProject.tasks.named("checkRendererBridgeUsage"))
        dependsOn(rootProject.tasks.named("checkNoServerReferences"))
        dependsOn(rootProject.tasks.named("checkNoAndroidImports"))
        dependsOn(rootProject.tasks.named("checkNoDatabaseImplLeak"))
        dependsOn(rootProject.tasks.named("checkNoOkHttpOutsideCoreNet"))
        dependsOn(rootProject.tasks.named("checkTranslations"))
        dependsOn(rootProject.tasks.named("checkTestGuardrails"))
    }
}

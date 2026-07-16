import com.riffle.buildlogic.AndroidImportLint
import com.riffle.buildlogic.RiffleLogTagLint
import com.riffle.buildlogic.ServerReferenceLint

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
            allowedRoot = layout.projectDirectory.dir("core/logging/src/main").asFile,
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
            // ---- Grandfathered — Clock sweep follow-up.
            "app/src/main/kotlin/com/riffle/app/feature/audiobook/AudiobookPlayerViewModel.kt",
            "app/src/main/kotlin/com/riffle/app/feature/library/LibraryItemsViewModel.kt",
            "app/src/main/kotlin/com/riffle/app/feature/server/AddServerViewModel.kt",
            // Logger core stamps ISO timestamps for `d`/`w`/`e` calls; routing Clock through the
            // logger primitive would invert the dependency direction. Grandfathered.
            "core/logging/src/main/kotlin/com/riffle/core/logging/AndroidLogger.kt",
            // ---- Grandfathered — DispatcherProvider sweep follow-up. LocalFiles ingestion
            // pipeline (#475) does direct SAF file I/O and needs Dispatchers.IO. Migrate when the
            // rest of the LocalFiles layer routes through DispatcherProvider.
            "core/data/src/main/kotlin/com/riffle/core/data/localfiles/AndroidCopyInService.kt",
            "core/data/src/main/kotlin/com/riffle/core/data/localfiles/SafFolderWalker.kt",
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

// Enforces the Source/Service taxonomy (ADR 0041, #443): fails if a Kotlin file
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
                "The taxonomy is Source/Service (ADR 0041). Rename `ServerFoo` → `SourceFoo` / `ServiceFoo`\n" +
                    "and `serverId` → `sourceId` at the introducing site, or (if the file legitimately\n" +
                    "belongs to Storyteller-adjacent internals / historical migration SQL) add it to\n" +
                    "ServerReferenceLint.ALLOWLIST with a one-line justification.\n" +
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

// Make it part of the normal `./gradlew check` run.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkRiffleLogTags"))
        dependsOn(rootProject.tasks.named("checkRiffleInfraSeams"))
        dependsOn(rootProject.tasks.named("checkRendererBridgeUsage"))
        dependsOn(rootProject.tasks.named("checkNoServerReferences"))
        dependsOn(rootProject.tasks.named("checkNoAndroidImports"))
    }
}

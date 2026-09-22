plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

// Riffle's design system: the theme, the icon registry, the form-factor primitive, the cover
// renderer and the presentational library leaves that BOTH hosts draw. Same `android { }` + iOS
// topology and the same reason as :feature:source-ui and :feature:reader-ui — a jvm-target
// Compose artifact cannot be consumed by an Android application, so shared Compose needs an
// androidTarget alongside the iOS ones.
//
// Why this module exists rather than growing :feature:source-ui: source-ui and reader-ui are each
// scoped to one feature surface, and what was missing is the layer *beneath* them. `SourceUiIcons`
// said so itself — "if a screen needs many icons it belongs in a dedicated shared design-system
// module" — while `SharedUiIcons` in :shared quietly became exactly the general icon library that
// warning forbade, with two glyphs inverted against Android's registry. Both are now one
// [com.riffle.feature.designsystem.RiffleIcons].
//
// Why the library leaves (SectionHeader / BookCoverTile / BookGrid) live here rather than in a
// third :feature:library-ui: they are pure presentational renderers — no ViewModel, no navigation,
// no repository — parameterised by `core:models.LibraryItem`, which is a platform-neutral data
// module (ADR 0059). A separate module for three composables would mean a second composeResources
// bundle and a second APK asset bridge for no layering gain.
kotlin {
    android {
        namespace = "com.riffle.feature.designsystem"
        compileSdk = 37
        minSdk = 24

        withHostTest {}

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // `api` for the Compose artifacts so consumers that have no material3 dependency of
            // their own (:shared) can call these composables without re-declaring the stack.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(compose.material3)
            implementation(compose.components.resources)
            api(libs.coil.compose)
            // NetworkHeaders / ImageRequest.httpHeaders — the Authorization header every
            // authenticated cover fetch needs. `coil-network-core` is the multiplatform half;
            // each host supplies its own fetcher engine (OkHttp on Android, Ktor on iOS).
            api(libs.coil.network.core)
            // The typed Logger seam behind LoggingCoverLoadReporter, so cover fetches are
            // traceable on both platforms rather than only through android.util.Log.
            api(project(":core:logging"))
            api(project(":core:models"))
            // CoverGridLayout — the breakpoint/pinch-clamp arithmetic the cover grids index on.
            api(project(":feature:library"))
        }
        iosMain.dependencies {
            // Coil's network fetcher is not auto-registered on Kotlin/Native the way it is on
            // JVM; the Ktor engine ships here and is wired up by the iOS ImageLoader factory.
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

// The generated `Res` accessors default to a package derived from the Gradle coordinates
// (`riffle.feature.design_system.generated.resources`). Pin it to the module's Kotlin package so
// imports read like every other symbol here.
compose.resources {
    packageOfResClass = "com.riffle.feature.designsystem.generated.resources"
    // Public, not the default `internal`: this bundle IS the localisation mechanism for the shared
    // UI, so :shared and :app resolve `Res.string.*` from it directly the way they resolve
    // R.string on Android. Keeping it internal would force a hand-written accessor per string.
    publicResClass = true
}

// The `runComposeUiTest` suites in commonTest cannot run on the Android HOST test task: Compose's
// UI-test harness needs a real Android runtime (it dereferences `android.os.Build.FINGERPRINT`,
// which is null on a bare JVM) and the repo has no Robolectric. They are not skipped — they run
// for real on `:feature:design-system:iosSimulatorArm64Test`, which CI executes. Same per-class
// exclusion pattern as feature/reader-ui, so the pure-logic suites beside them keep running on the
// JVM as well as on iOS.
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("com.riffle.feature.designsystem.BookCoverTileTest")
        excludeTestsMatching("com.riffle.feature.designsystem.SectionHeaderTest")
        isFailOnNoMatchingTests = false
    }
}

// Compose Multiplatform's own Android asset wiring (copyAndroidMainComposeResourcesToAndroidAssets)
// is registered but never configured under AGP 9's com.android.kotlin.multiplatform.library plugin
// ("property 'outputDirectory' doesn't have a configured value"), so the module's composeResources
// silently never reach the consuming APK and every stringResource/painterResource crashes at
// runtime with MissingResourceException. Bridge manually, exactly as :feature:source-ui does:
// assemble the prepared resources into the on-device asset layout (composeResources/<res-package>/…);
// :app adds this directory as an asset srcDir and wires its asset-merge tasks to depend on it.
val copyComposeResourcesForApk by tasks.registering(Copy::class) {
    dependsOn(tasks.matching { it.name == "prepareComposeResourcesTaskForCommonMain" })
    from(layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("composeAssetsForApk/composeResources/com.riffle.feature.designsystem.generated.resources"))
}

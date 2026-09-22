plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

// The library browsing surfaces' Compose, rendered by BOTH the Android app (:app) and the iOS app
// (:shared). Same topology and same reason as :feature:source-ui, :feature:reader-ui and
// :feature:player-ui — a jvm-target Compose artifact cannot be consumed by an Android
// application, so shared library UI needs androidTarget + iOS targets. Everything the two
// platforms both draw for playlists, facet drill-ins and annotation search lives here; the
// platform-bound hosting (Android's navigation graph, iOS's LibraryNav) stays where it is.
//
// Like :feature:reader-ui and :feature:player-ui, and unlike :feature:source-ui, this module
// deliberately has no composeResources: every user-visible string is supplied by the host as a
// [com.riffle.feature.library.ui.LibraryUiLabels], so Android keeps serving them from its own
// `res/values*` (bg/es included) with no resource migration and no APK asset bridging.
kotlin {
    android {
        namespace = "com.riffle.feature.library.ui"
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
            // `api` for the Compose artifacts so :shared (which has no material3 dependency of
            // its own) can call these screens without re-declaring the whole Compose stack.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(compose.material3)
            api(project(":core:domain"))
            api(project(":core:models"))
            // AnnotationEntity.TYPE_BOOKMARK — the stored type token the annotation-search row
            // branches on. Never the literal (AGENTS.md).
            api(project(":core:database-api"))
            api(project(":feature:library"))
            // RiffleTheme, DefaultCoverPlaceholder, CornerBookmarkIndicator — the shared chrome
            // both hosts already wrap their screens in.
            api(project(":feature:design-system"))
            api(project(":feature:source-ui"))
            // Solely for `formatTemplate`, the shared expander for Android-style positional
            // string templates. It is the repo's only implementation and AGENTS.md forbids
            // forking a shared derivation, so this module reuses it rather than declaring a
            // second one. Both hosts already depend on :feature:reader-ui, so nothing new ships.
            implementation(project(":feature:reader-ui"))
            implementation(libs.coil.compose)
            // NetworkHeaders/httpHeaders for the authenticated cover fetch on the
            // annotation-search rows. coil-network-core is the multiplatform half of the network
            // layer; each host supplies its own fetcher (OkHttp on Android, Ktor/Darwin on iOS)
            // when it builds the ImageLoader. Same arrangement as :feature:player-ui.
            implementation(libs.coil.network.core)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

// The `runComposeUiTest` suites in commonTest cannot run on the Android HOST test task: Compose's
// UI-test harness needs a real Android runtime (it dereferences `android.os.Build.FINGERPRINT`,
// which is null on a bare JVM) and the repo has no Robolectric. They are not skipped — they run
// for real on `:feature:library-ui:iosSimulatorArm64Test`, which CI executes. Same arrangement as
// :feature:reader-ui and :feature:player-ui.
//
// The filter is a per-class exclusion rather than dropping `withHostTest {}`, so the pure-logic
// suites beside them keep running on the JVM as well as on iOS.
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("com.riffle.feature.library.ui.PlaylistSurfaceRenderTest")
        excludeTestsMatching("com.riffle.feature.library.ui.FilteredBooksRenderTest")
        excludeTestsMatching("com.riffle.feature.library.ui.AnnotationSearchResultsRenderTest")
        isFailOnNoMatchingTests = false
    }
}

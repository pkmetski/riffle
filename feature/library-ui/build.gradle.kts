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
// Like :feature:source-ui, :feature:reader-ui and :feature:player-ui, this module has an
// android+iOS topology so both hosts can render the same Compose screens. Strings are served via
// composeResources (values/strings.xml + values-es/ + values-bg/) so both hosts use one set of
// translations; the Android app also keeps its own res/values*/ copies for the Android-only
// screens that still reference R.string.
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
            implementation(compose.components.resources)
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
            // DownloadsScreen: LocalItemUi, DownloadsViewModel, LocalMediaType, formatBytes.
            api(project(":feature:downloads"))
            implementation(libs.coil.compose)
            // NetworkHeaders/httpHeaders for the authenticated cover fetch on the
            // annotation-search rows. coil-network-core is the multiplatform half of the network
            // layer; each host supplies its own fetcher (OkHttp on Android, Ktor/Darwin on iOS)
            // when it builds the ImageLoader. Same arrangement as :feature:player-ui.
            implementation(libs.coil.network.core)
            implementation(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
            // LibraryItemsScreen uses koinInject { parametersOf(...) } for its ViewModel defaults.
            implementation(libs.koin.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

compose.resources {
    packageOfResClass = "com.riffle.feature.library.ui.generated.resources"
    publicResClass = true
}

val copyComposeResourcesForApk by tasks.registering(Copy::class) {
    dependsOn(tasks.matching { it.name == "prepareComposeResourcesTaskForCommonMain" })
    from(layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("composeAssetsForApk/composeResources/com.riffle.feature.library.ui.generated.resources"))
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
        excludeTestsMatching("com.riffle.feature.library.ui.LibraryTabContentTest")
        excludeTestsMatching("com.riffle.feature.library.ui.DownloadsConfirmationTest")
        isFailOnNoMatchingTests = false
    }
}

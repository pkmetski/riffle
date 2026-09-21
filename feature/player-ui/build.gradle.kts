plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

// The audiobook player's Compose chrome, rendered by BOTH the Android app (:app) and the iOS app
// (:shared). Same topology and same reason as :feature:source-ui and :feature:reader-ui — a
// jvm-target Compose artifact cannot be consumed by an Android application, so shared player UI
// needs androidTarget + iOS targets. Everything the two platforms both draw for the audiobook
// player lives here; the platform-bound playback engines (Media3 in :app, AVQueuePlayer through
// the Swift bridge in :shared) stay where they are, behind
// [com.riffle.feature.player.AudioPlayerInterface].
//
// Like :feature:reader-ui and unlike :feature:source-ui this module deliberately has no
// composeResources: every user-visible string is supplied by the host as a
// [com.riffle.feature.player.ui.PlayerChromeLabels], so Android keeps serving them from its own
// `res/values*` (bg/es included) with no resource migration and no APK asset bridging.
kotlin {
    android {
        namespace = "com.riffle.feature.player.ui"
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
            // its own) can call these composables without re-declaring the whole Compose stack.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(compose.material3)
            api(project(":core:common"))
            api(project(":core:domain"))
            api(project(":core:models"))
            api(project(":feature:player"))
            // RiffleTheme, DefaultCoverPlaceholder, CornerBookmarkIndicator, asAuthHeader — the
            // shared chrome both hosts already wrap their screens in.
            api(project(":feature:source-ui"))
            // Solely for `formatTemplate`, the shared expander for Android-style positional
            // string templates. It is the repo's only implementation and AGENTS.md forbids
            // forking a shared derivation, so this module reuses it rather than declaring a
            // second one. Both hosts already depend on :feature:reader-ui, so nothing new ships.
            implementation(project(":feature:reader-ui"))
            implementation(libs.coil.compose)
            // NetworkHeaders/httpHeaders for the authenticated cover fetch. coil-network-core is
            // the multiplatform half of the network layer; each host supplies its own fetcher
            // (OkHttp on Android, Ktor/Darwin on iOS) when it builds the ImageLoader.
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
// for real on `:feature:player-ui:iosSimulatorArm64Test`, which CI executes, and Android's own
// rendering of these composables is covered on-device by `app/src/androidTest`
// (`PlayerTitleYearTest`). Same arrangement as :feature:reader-ui.
//
// The filter is a per-class exclusion rather than dropping `withHostTest {}`, so the pure-logic
// suites beside them keep running on the JVM as well as on iOS.
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("com.riffle.feature.player.ui.PlayerSurfaceRenderTest")
        excludeTestsMatching("com.riffle.feature.player.ui.PlayerChromeRenderTest")
        isFailOnNoMatchingTests = false
    }
}

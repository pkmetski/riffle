plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

// The reader's on-screen Compose surfaces, rendered by BOTH the Android app (:app) and the iOS
// app (:shared). Same topology and same reason as :feature:source-ui — a jvm-target Compose
// artifact cannot be consumed by an Android application, so shared reader UI needs
// androidTarget + iOS targets. Put reader Compose that both platforms render here; the
// platform-bound hosting (Readium-Android fragments, the UIKit navigator bridge) stays in
// :app / :shared.
//
// Unlike :feature:source-ui this module deliberately has no composeResources: its only strings
// are the chapter-map labels, and they are supplied by the host as a
// [com.riffle.feature.reader.ui.ChapterMapProgressLabelTemplates] so Android keeps serving them
// from its own `res/values*` (bg/es included) with no resource migration and no APK asset
// bridging.
kotlin {
    android {
        namespace = "com.riffle.feature.reader.ui"
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
            api(project(":feature:reader"))
            // RiffleTheme — the same MaterialTheme both hosts wrap their chrome in.
            api(project(":feature:source-ui"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

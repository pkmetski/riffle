plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

// The shared app shell rendered by both the Android app (:app) and the iOS app (:shared).
// Same android+ios topology as :feature:library-ui and :feature:settings-ui — a jvm-target
// Compose artifact cannot be consumed by an Android application, so the shell needs
// androidTarget + iOS targets. Sections (Library / Riffle / Downloads / Settings),
// per-section back stacks, multiplatform BackHandler, RiffleNavigationDrawer, startup router,
// now-playing dedupe, and the onboarding host all live here. Engine surfaces (EPUB, PDF, CBZ,
// audiobook player, local-file picker, share sheet) are injected composable lambdas so each
// host supplies the right platform implementation.
kotlin {
    android {
        namespace = "com.riffle.feature.shell.ui"
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
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.ui.backhandler)
            api(compose.material3)
            implementation(compose.components.resources)
            api(project(":core:domain"))
            api(project(":core:models"))
            api(project(":feature:navigation"))
            api(project(":feature:library"))
            api(project(":feature:library-ui"))
            api(project(":feature:settings-ui"))
            api(project(":feature:source-ui"))
            api(project(":feature:reader-ui"))
            api(project(":feature:player-ui"))
            api(project(":feature:downloads"))
            api(project(":feature:design-system"))
            api(project(":feature:player"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
        }
        iosMain.dependencies {
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

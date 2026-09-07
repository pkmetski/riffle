plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

// The source-onboarding Compose surface, shared by the Android app (:app) and the iOS app
// (:shared → Compose Multiplatform). This is the first module in the repo with BOTH an
// androidTarget and iOS targets *and* Compose UI: feature/* modules are jvm+ios, and a
// jvm-target Compose artifact cannot be consumed by an Android application, so shared UI needs
// this android+ios topology. Everything Compose that both platforms render belongs here.
kotlin {
    android {
        namespace = "com.riffle.feature.source.ui"
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
            implementation(compose.components.resources)
            implementation(libs.coil.compose)
            implementation(project(":core:common"))
            implementation(project(":core:database-api"))
            api(project(":core:domain"))
            api(project(":core:models"))
            implementation(project(":core:sources"))
            implementation(project(":core:sync"))
            implementation(project(":feature:settings"))
            api(project(":feature:source"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.koin.core)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.androidx.lifecycle.viewmodel.savedstate)
        }
        iosMain.dependencies {
            // Coil's network fetcher is not auto-registered on Kotlin/Native the way it is on
            // JVM, so the Ktor engine ships here and is wired up by the iOS ImageLoader factory.
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // The exhaustive ported suites (AddSourceViewModelTest & friends) live in the Android
        // host-test source set, not commonTest, purely because Kotlin/Native rejects backticked
        // test names containing `(`, `)` or `,` ("Name contains illegal characters") and the
        // repo's checkTestGuardrails forbids renaming an existing @Test. commonTest carries
        // Native-legal coverage of the same shared logic so iOS still executes it.
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The generated `Res` accessors default to a package derived from the Gradle coordinates
// (`riffle.feature.source_ui.generated.resources`). Pin it to the module's Kotlin package so
// imports read like every other symbol here.
compose.resources {
    packageOfResClass = "com.riffle.feature.source.ui.generated.resources"
}

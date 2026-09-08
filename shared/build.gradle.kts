import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    // org.jetbrains.compose registers the iOS resource-sync wiring on
    // embedAndSignAppleFrameworkForXcode; without it, Compose resources from dependency
    // modules (e.g. :feature:source-ui strings/drawables) never reach the app bundle and
    // stringResource() crashes with MissingResourceException at runtime.
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

kotlin {
    val xcf = XCFramework("Riffle")

    // Modules whose declarations must appear in the generated Riffle framework under their
    // plain Kotlin names. Without `export`, Kotlin/Native either omits a dependency's
    // declarations entirely (anything not reachable from `shared`'s own public API — e.g.
    // ColumnSnap) or emits them under a module-name prefix (SourceType → ModelsSourceType).
    // The Swift unit-test target (iosAppUnitTests) asserts directly against this shared
    // Kotlin code, so it needs the real, stable names.
    val exportedModules = listOf(
        ":core:data",
        ":core:database-api",
        ":core:domain",
        ":core:models",
        ":core:sync",
        ":feature:player",
        ":feature:reader",
        ":feature:settings",
    )

    iosArm64 {
        binaries.framework {
            baseName = "Riffle"
            isStatic = true
            exportedModules.forEach { export(project(it)) }
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "Riffle"
            isStatic = true
            exportedModules.forEach { export(project(it)) }
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api` (not `implementation`) is required for the `export(...)` calls above:
            // Kotlin/Native only exports declarations reachable through the api configuration.
            api(project(":core:data"))
            api(project(":core:database-api"))
            api(project(":core:domain"))
            api(project(":core:models"))
            api(project(":core:sync"))
            api(project(":feature:player"))
            api(project(":feature:reader"))
            api(project(":feature:settings"))
            implementation(project(":core:logging"))
            implementation(project(":core:net"))
            implementation(project(":feature:downloads"))
            implementation(project(":feature:library"))
            implementation(project(":core:sources"))
            implementation(project(":feature:source"))
            implementation(project(":feature:source-ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

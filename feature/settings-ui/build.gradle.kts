plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.ktlint)
}

// Settings Compose UI shared by Android (:app) and iOS (:shared). Same topology as
// :feature:source-ui: androidTarget + iOS targets so both hosts can render this module's screens.
// Everything the Settings flow renders on both platforms lives here; platform-bound wiring
// (Android nav graph, iOS HomeScreen pane switch) stays in :app / :shared.
kotlin {
    android {
        namespace = "com.riffle.feature.settings.ui"
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
            api(compose.material3)
            implementation(compose.components.resources)
            // RiffleTheme, TestTags, TabletContentWidthContainer and design tokens.
            api(project(":feature:design-system"))
            // Logic module — derivations, ViewModels, SettingsNavEvent.
            api(project(":feature:settings"))
            api(project(":core:domain"))
            api(project(":core:data"))
            api(project(":core:models"))
            implementation(project(":core:common"))
            implementation(project(":core:sync"))
            implementation(project(":feature:source"))
            // SourcesSection and AddSourceBackend — used by the Sources row in SettingsScreen.
            api(project(":feature:source-ui"))
            implementation(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.androidx.lifecycle.viewmodel.savedstate)
        }
        iosTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.mockk)
        }
    }
}

compose.resources {
    packageOfResClass = "com.riffle.feature.settings.ui.generated.resources"
    // publicResClass = true so :shared and :app can call stringResource(Res.string.*) without
    // re-declaring this module's generated resources on their own classpath.
    publicResClass = true
}

// Same asset-bridging workaround as :feature:source-ui — AGP 9's KMP library plugin does not wire
// CMP's resource-copy task, so we assemble the prepared resources manually and :app registers the
// output directory as an assets srcDir. See :feature:source-ui for the detailed explanation.
val copyComposeResourcesForApk by tasks.registering(Copy::class) {
    dependsOn(tasks.matching { it.name == "prepareComposeResourcesTaskForCommonMain" })
    from(layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("composeAssetsForApk/composeResources/com.riffle.feature.settings.ui.generated.resources"))
}

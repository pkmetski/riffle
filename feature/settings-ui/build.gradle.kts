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
            api(libs.compose.ui.backhandler)
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
            // Reader settings panels (FormattingPanel, DisplayPanel, etc.) reference reader types.
            api(project(":feature:reader"))
            // Reader-UI composables: AutoScrollToggleIcon, CadenceGlyph, readerPalette, swatchBackdropColor.
            api(project(":feature:reader-ui"))
            // Listening preferences panel uses PlaybackSpeed / SkipIntervals.
            api(project(":feature:player"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.androidx.lifecycle.viewmodel.savedstate)
            // koin-compose + koin-compose-viewmodel: koinInject / koinViewModel in shared screens.
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        androidMain.dependencies {
            // DebugLogViewModel (AndroidViewModel), DiagnosticsSection (Intent/FileProvider),
            // DictionaryPacksViewModel (DownloadManager from :app).
            implementation(libs.androidx.lifecycle.runtime.ktx)
            implementation(project(":core:dictionary"))
            implementation(project(":core:logging"))
            // DictionaryPacksScreen uses formatBytes from feature:downloads.
            implementation(project(":feature:downloads"))
        }
        iosMain.dependencies {
            // IosDebugLogScreen reads InMemoryLogBuffer directly (no AndroidViewModel).
            implementation(project(":core:logging"))
            // IosDictionaryPacksScreen reads LanguageCatalog to display available packs.
            implementation(project(":core:dictionary"))
            // IosDictionaryPacksScreen uses formatBytes for size display.
            implementation(project(":feature:downloads"))
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
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
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

// The `runComposeUiTest` suites in commonTest cannot run on the Android HOST test task: Compose's
// UI-test harness needs a real Android runtime (it dereferences `android.os.Build.FINGERPRINT`,
// which is null on a bare JVM) and the repo has no Robolectric. They run for real on
// `:feature:settings-ui:iosSimulatorArm64Test`, which CI executes. Same arrangement as
// :feature:library-ui and :feature:reader-ui.
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("com.riffle.feature.settings.ui.ComicDisplaySettingsPanelTest")
    }
}

// Same asset-bridging workaround as :feature:source-ui — AGP 9's KMP library plugin does not wire
// CMP's resource-copy task, so we assemble the prepared resources manually and :app registers the
// output directory as an assets srcDir. See :feature:source-ui for the detailed explanation.
val copyComposeResourcesForApk by tasks.registering(Copy::class) {
    dependsOn(tasks.matching { it.name == "prepareComposeResourcesTaskForCommonMain" })
    from(layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("composeAssetsForApk/composeResources/com.riffle.feature.settings.ui.generated.resources"))
}

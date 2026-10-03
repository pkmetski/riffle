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
            api(libs.compose.ui.backhandler)
            api(compose.material3)
            implementation(compose.components.resources)
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

// Pin the generated Res class to the module's Kotlin package (same pattern as :feature:source-ui).
compose.resources {
    packageOfResClass = "com.riffle.feature.reader.ui.generated.resources"
    publicResClass = true
}

// AGP 9's KMP library plugin does not configure the Compose resource task's outputDirectory, so
// composeResources never reach the consuming APK. Bridge manually: same pattern as :feature:source-ui.
// :app adds this directory as an asset srcDir and wires its asset-merge tasks to depend on this task.
val copyComposeResourcesForApk by tasks.registering(Copy::class) {
    dependsOn(tasks.matching { it.name == "prepareComposeResourcesTaskForCommonMain" })
    from(layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"))
    into(layout.buildDirectory.dir("composeAssetsForApk/composeResources/com.riffle.feature.reader.ui.generated.resources"))
}

// The `runComposeUiTest` suites in commonTest cannot run on the Android HOST test task:
// Compose's UI-test harness needs a real Android runtime (it dereferences
// `android.os.Build.FINGERPRINT`, which is null on a bare JVM) and the repo has no Robolectric.
// They are not skipped — they run for real on `:feature:reader-ui:iosSimulatorArm64Test`, which
// CI executes, and Android's own rendering of these composables is covered on-device by
// `app/src/androidTest` (`ChapterRailIsolationTest`).
//
// The filter is deliberately a per-class exclusion rather than dropping `withHostTest {}`, so the
// three pure-logic suites beside them (`AutoScrollHudPillPaddingTest`,
// `ChapterMapOverlayLabelTest`, `ChapterNavigationRailTest`) keep running on the JVM as well as
// on iOS. Delete the exclusions if Robolectric is ever added.
tasks.withType<Test>().configureEach {
    filter {
        excludeTestsMatching("com.riffle.feature.reader.ui.AutoScrollHudPillTest")
        excludeTestsMatching("com.riffle.feature.reader.ui.CadenceUiTest")
        excludeTestsMatching("com.riffle.feature.reader.ui.ChapterMapOverlayRenderTest")
        // The annotate sheet and the bookmark ribbon: same constraint, same coverage story —
        // they execute for real on `iosSimulatorArm64Test`, and Android renders the ribbon
        // on-device in `app/src/androidTest` (`CornerBookmarkIndicatorTest`).
        excludeTestsMatching("com.riffle.feature.reader.ui.AnnotationActionsSheetTest")
        // New shared chrome composables (ReaderTopBar, SearchTopBar, AnnotationsPanel, etc.)
        // follow the same run-on-iOS-only policy as AnnotationActionsSheetTest above.
        excludeTestsMatching("com.riffle.feature.reader.ui.ReaderTopBarTest")
        excludeTestsMatching("com.riffle.feature.reader.ui.SearchTopBarTest")
        excludeTestsMatching("com.riffle.feature.reader.ui.AnnotationsPanelTest")
        excludeTestsMatching("com.riffle.feature.reader.ui.FootnotePopupTest")
        excludeTestsMatching("com.riffle.feature.reader.ui.ReturnToPositionCardTest")
        isFailOnNoMatchingTests = false
    }
}

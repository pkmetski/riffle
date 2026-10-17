plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:models"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // Kotlin Multiplatform port of jsoup — used by the shared Ksoup-backed DOM/CFI
            // translators (KsoupEpubCfiTranslator, KsoupEpubChapterDomOps). Replaces the former
            // parallel jvmMain org.jsoup + iosMain ksoup split.
            implementation(libs.ksoup)
        }
        jvmMain.dependencies {
            // EpubCfiTranslator.kt: extractAnchorFromCfi/extractCfiElementIds/hasElementWithId
            // are used in production (EpubReaderViewModel.kt). Kept in jvmMain until those call
            // sites are migrated to the shared ksoup equivalents.
            implementation(libs.jsoup)
            // Use-case classes carry @Inject so Hilt can wire them through the data/app graph.
            implementation("javax.inject:javax.inject:1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.jsoup)
        }
    }
}

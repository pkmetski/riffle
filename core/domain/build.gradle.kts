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
        }
        jvmMain.dependencies {
            implementation(libs.jsoup)
            // Use-case classes carry @Inject so Hilt can wire them through the data/app graph.
            implementation("javax.inject:javax.inject:1")
        }
        iosMain.dependencies {
            // Kotlin Multiplatform port of jsoup, used only by the iOS EbookCfiTranslator (issue
            // #1065) — Android keeps using org.jsoup directly (jvmMain, above); this is additive,
            // not a replacement, so Android's existing jsoup-based reader code is untouched.
            implementation(libs.ksoup)
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

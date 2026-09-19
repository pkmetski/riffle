plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:domain"))
            api(project(":core:models"))
            // AnnotationEntity's TYPE_* constants: the highlight merge/overlap logic and the
            // annotations panel both key off them, and the repo forbids re-declaring the literals
            // (issue #1066, moved here from `app` so their tests run on iOS too).
            api(project(":core:database-api"))
            api(project(":core:sync"))
            // ReaderSync/ReaderSyncFactory (issue #1065) moved here from jvmMain; they talk to
            // catalog progress-peer capabilities and log through the typed Logger channels.
            api(project(":core:catalog"))
            api(project(":core:logging"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

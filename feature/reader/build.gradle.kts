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
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            api(project(":core:catalog"))
            api(project(":core:logging"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

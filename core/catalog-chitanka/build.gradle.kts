plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":core:catalog"))
            implementation(project(":core:domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.jsoup)
            api(libs.ktor.client.core)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

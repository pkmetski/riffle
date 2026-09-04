import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

kotlin {
    val xcf = XCFramework("Riffle")
    iosArm64 {
        binaries.framework {
            baseName = "Riffle"
            isStatic = true
            xcf.add(this)
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "Riffle"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:data"))
            implementation(project(":core:logging"))
            implementation(project(":core:net"))
            implementation(project(":feature:library"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

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
            api(project(":core:catalog"))
            // HomeViewModel.StartDestination and LibrarySectionType feed the route builders.
            api(project(":feature:library"))
            // The drawer surfaces the active NowPlaying item.
            api(project(":feature:player"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.lifecycle.viewmodel)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

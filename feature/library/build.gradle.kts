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
            // BookImportManagerImpl logs through the typed Logger channels.
            implementation(project(":core:logging"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.lifecycle.viewmodel)
            // SavedStateHandle: PlaylistDetailViewModel (and the facet/annotation-search VMs)
            // read their nav arguments through it on Android and through a handle the iOS Koin
            // factory fabricates, so the key constants live on the VM and both hosts agree.
            api(libs.androidx.lifecycle.viewmodel.savedstate)
            implementation(libs.compose.runtime)  // mutableStateOf in ViewModels
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

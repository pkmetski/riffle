plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    android {
        namespace = "com.riffle.core.data"
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
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core:common"))
            implementation(project(":core:domain"))
            implementation(project(":core:models"))
            implementation(project(":core:database-api"))
        }
        androidMain.dependencies {
            implementation(project(":core:dictionary"))
            implementation(project(":core:sync"))
            implementation(project(":core:sources"))
            implementation(project(":core:network"))
            implementation(project(":core:database"))
            implementation(project(":core:catalog"))
            implementation(project(":core:catalog-chitanka"))
            implementation(project(":core:catalog-gutenberg"))
            implementation(project(":core:catalog-radio-es"))
            implementation(project(":core:catalog-komga"))
            implementation(project(":core:logging"))
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.androidx.documentfile)
            implementation(libs.androidx.security.crypto)
            implementation(libs.koin.android)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.okhttp)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.koin.core)
            implementation(project(":core:database"))
            implementation(project(":core:net"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.mockk)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    add("androidMainImplementation", platform(libs.koin.bom))
}

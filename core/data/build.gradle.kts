plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.riffle.core.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.all { testTask ->
            testTask.filter {
                if (project.hasProperty("integrationTests")) {
                    includeTestsMatching("*IntegrationTest")
                } else {
                    excludeTestsMatching("*IntegrationTest")
                }
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:domain"))
    implementation(project(":core:sources"))
    implementation(project(":core:network"))
    implementation(project(":core:database"))
    implementation(project(":core:catalog"))
    implementation(project(":core:catalog-chitanka"))
    implementation(project(":core:catalog-gutenberg"))
    implementation(project(":core:catalog-komga"))
    implementation(project(":core:logging"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.security.crypto)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.okhttp) {
        // okhttp-sse:4.x is a stale transitive dep — OkHttp 5 bundles SSE in its main artifact.
        // The 4.x jar references okhttp3.internal.Util which was removed in 5.x, breaking R8.
        exclude(group = "com.squareup.okhttp3", module = "okhttp-sse")
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.okhttp) {
        exclude(group = "com.squareup.okhttp3", module = "okhttp-sse")
    }

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockk)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.riffle.core.database.api"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)

    testImplementation(libs.junit)
}

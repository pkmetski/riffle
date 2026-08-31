plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

kotlin {
    android {
        namespace = "com.riffle.core.database"
        compileSdk = 37
        minSdk = 24

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        androidResources {
            enable = true
        }

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // Intermediate source set for Android + JVM targets only.
        // sqlite-bundled and buildRiffleDatabase() live here so the iOS XCFramework link graph
        // never picks up the bundled SQLite binary (which causes OOM in the Kotlin/Native linker).
        val nonIosMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(nonIosMain)
        jvmMain.get().dependsOn(nonIosMain)

        commonMain.dependencies {
            api(project(":core:database-api"))
            implementation(libs.androidx.room.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }
        // BundledSQLiteDriver is only used on Android/JVM — never on iOS.
        getByName("nonIosMain").dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        iosMain.dependencies {
            // System SQLite via NativeSqliteDriver: zero added link weight (uses OS SQLite).
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.junit)
            implementation(libs.androidx.junit)
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.room.testing)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
        }
    }
}

// Room KSP only for Android and JVM — iOS uses SQLDelight, not Room.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addStaticSourceDirectory("schemas")
        }
    }
}

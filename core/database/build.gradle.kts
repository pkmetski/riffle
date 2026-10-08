plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ktlint)
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

    // Room KMP on iOS uses the system SQLite (NativeSQLiteDriver). The Kotlin/Native test
    // binary has no Xcode build settings, so the linker flag must be declared here.
    // The androidx.sqlite cinterop wrapper references sqlite3_load_extension, which iOS system
    // SQLite omits (SQLITE_OMIT_LOAD_EXTENSION). Mark it as an allowed-undefined symbol so
    // the linker does not fail; the wrapper is never called at runtime for these tests.
    iosArm64 { binaries.all { linkerOpts("-lsqlite3") } }
    iosSimulatorArm64 { binaries.all { linkerOpts("-lsqlite3") } }

    sourceSets {
        // Intermediate source set for Android + JVM targets only.
        // sqlite-bundled and buildRiffleDatabase() live here so the iOS XCFramework link graph
        // never picks up the bundled SQLite binary (which causes OOM in the Kotlin/Native linker).
        val nonIosMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(nonIosMain)
        jvmMain.get().dependsOn(nonIosMain)

        // Explicitly connect iosMain into the compilation graph.
        // Adding custom dependsOn calls (nonIosMain) can prevent the default hierarchy template
        // from wiring iosMain → iosArm64Main / iosSimulatorArm64Main automatically.
        iosMain.get().dependsOn(commonMain.get())
        iosArm64Main.get().dependsOn(iosMain.get())
        iosSimulatorArm64Main.get().dependsOn(iosMain.get())

        // Same story for the test graph: without these, `iosTest` is an orphan source set that
        // is never compiled by any target, so `IosRiffleDatabaseSchemaTest` silently never ran
        // (`:core:database:iosSimulatorArm64Test` reported only the commonTest classes).
        iosTest.get().dependsOn(commonTest.get())
        iosArm64Test.get().dependsOn(iosTest.get())
        iosSimulatorArm64Test.get().dependsOn(iosTest.get())

        commonMain.dependencies {
            api(project(":core:database-api"))
            implementation(libs.kotlinx.coroutines.core)
            // Room KMP runtime — annotations + core APIs used by RiffleDatabase (commonMain).
            // The sqlite-bundled native binary stays in nonIosMain so the XCFramework link
            // graph never picks it up (that binary was the OOM cause, not Room itself).
            implementation(libs.androidx.room.runtime)
            // androidx.sqlite:sqlite is needed in commonMain for SQLiteConnection.query() /
            // SQLiteConnection.use() extensions used in migration bodies. On Android the
            // nonIosMain sqlite-bundled dependency transitively provides the runtime; this
            // commonMain dep ensures the compiler resolves the extension symbols for all targets.
            implementation(libs.androidx.sqlite)
        }
        getByName("nonIosMain").dependencies {
            // Bundled SQLite binary for Android/JVM — must not be included on iOS.
            implementation(libs.androidx.sqlite.bundled)
        }
        iosMain.dependencies {
            // Room KMP on iOS uses the system SQLite (no bundled binary, no OOM).
            implementation(libs.androidx.sqlite)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // sqlite-bundled provides sqlite3_load_extension (absent in iOS system SQLite, which is
        // compiled with SQLITE_OMIT_LOAD_EXTENSION). The androidx.sqlite cinterop wrapper
        // references this symbol even though NativeSQLiteDriver never calls it at runtime;
        // without it dyld fails on launch. This stays in iosTest only — the production
        // XCFramework must NOT link sqlite-bundled (that binary caused OOM in the KN linker).
        iosTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
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

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
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

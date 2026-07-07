import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Exclude legacy Android Support Library to prevent duplicate-class conflicts with AndroidX.
// pdfium-android:1.8.2 references android.support.v4.util.ArrayMap — that class is provided
// via the stub in app/src/main/java/android/support/v4/util/ArrayMap.java instead.
configurations.all {
    exclude(group = "com.android.support")
    // Hilt's aggregating annotation processor bundles an older kotlin-metadata-jvm that only
    // reads Kotlin metadata up to 2.3.0 and fails on Kotlin 2.4.0 output ("maximum supported
    // version is 2.3.0"). Force the matching version so the processor can read the new metadata.
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Short git SHA of the build, surfaced in the nav drawer so a tester can confirm exactly which commit
// an installed APK was built from. Appends "-dirty" when the working tree had uncommitted changes.
// Uses providers.exec (configuration-cache friendly); falls back to "unknown" outside a git checkout.
val gitSha: String = runCatching {
    val sha = providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
        .standardOutput.asText.get().trim()
    val dirty = providers.exec { commandLine("git", "status", "--porcelain") }
        .standardOutput.asText.get().isNotBlank()
    if (sha.isBlank()) "unknown" else if (dirty) "$sha-dirty" else sha
}.getOrDefault("unknown")

android {
    namespace = "com.riffle.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.riffle.app"
        minSdk = 24
        targetSdk = 36
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.0.0-dev"

        testInstrumentationRunner = "com.riffle.app.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "DEV_SERVER_URL", "\"\"")
        buildConfigField("String", "DEV_USERNAME", "\"\"")
        buildConfigField("String", "DEV_PASSWORD", "\"\"")

        // Resolved Readium version, surfaced so ReadiumVersionPinTest can flag any future bump
        // (Readium 3.2.0+ regresses the readaloud highlight — see that test).
        buildConfigField("String", "READIUM_VERSION", "\"${libs.versions.readium.get()}\"")

        // Build commit SHA shown in the nav drawer (see gitSha above). Empty by default so
        // published (release) builds carry no SHA; the debug build type fills in the real value.
        buildConfigField("String", "GIT_SHA", "\"\"")
    }
    val keystorePath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "DEV_SERVER_URL", "\"${localProps.getProperty("dev.serverUrl", "")}\"")
            buildConfigField("String", "DEV_USERNAME",   "\"${localProps.getProperty("dev.username", "")}\"")
            buildConfigField("String", "DEV_PASSWORD",   "\"${localProps.getProperty("dev.password", "")}\"")
            // SHA is debug-only — release builds keep the empty default from defaultConfig.
            buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        }
        release {
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":core:logging"))
    // Three-peer reader sync constructs SyncRemotes over the position APIs directly (issue #38).
    implementation(project(":core:network"))
    implementation(project(":core:catalog"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.coil.compose)
    implementation(libs.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.adapter.pdfium)
    // Readium 3.3.0 ships marain87's PdfiumAndroid fork (namespace `com.shockwave.pdfium`) at
    // runtime. We just need the class on the compile classpath to bind our metadata extractor,
    // so use compileOnly to avoid a manifest namespace clash between the two forks.
    compileOnly(libs.pdfium.android)

    implementation(libs.acra.core)
    implementation(libs.acra.toast)
    implementation(libs.acra.dialog)
    implementation(libs.acra.limiter)

    implementation(libs.jsoup)
    testImplementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(project(":core:network"))
    androidTestImplementation(project(":core:database"))
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

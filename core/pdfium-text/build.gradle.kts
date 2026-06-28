plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.riffle.core.pdfium.text"
    compileSdk = 37
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-Wall", "-Wextra")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    // Deliberately no compile-time dep on com.github.barteksc:pdfium-android
    // or org.readium.*. This module only needs libmodpdfium.so to be loaded
    // into the process at runtime; PdfiumTextApi.<clinit> calls
    // System.loadLibrary("modpdfium") directly from its own classloader.
    // The .so itself is supplied by barteksc's pdfium-android AAR (already
    // packaged into the APK via Readium's transitive dep in :app), so there
    // is no risk of "missing native library" at runtime — but we never need
    // to compile against barteksc's Kotlin/Java surface, which keeps this
    // module's dependency tree minimal and avoids dragging Readium's
    // core-library-desugaring requirement into a module that contains no
    // desugar-needing code.

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // Standalone module tests need libmodpdfium.so present in the test APK so
    // System.loadLibrary("modpdfium") and the dlsym path can resolve symbols.
    // In the real app, Readium pulls this in transitively; here we pin
    // barteksc directly to avoid dragging Readium's core-library-desugaring
    // requirement into this otherwise-tiny module.
    androidTestImplementation("com.github.barteksc:pdfium-android:1.8.2")
}

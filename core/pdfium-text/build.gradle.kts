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
    // We depend on barteksc's PdfiumCore so callers can hand us page pointers
    // obtained from PdfiumCore.openPage(...). The native library it loads
    // (libmodpdfium.so) is what our JNI bridge dlsyms into at runtime.
    implementation(libs.readium.adapter.pdfium)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

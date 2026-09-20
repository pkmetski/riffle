plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:catalog"))
            implementation(project(":core:domain"))
            implementation(libs.kotlinx.coroutines.core)
            // Kotlin Multiplatform HTML parser. The scrapers used to sit in jvmMain against
            // org.jsoup; ksoup is the same DOM/selector surface on every target, which is what
            // lets iOS register a ChitankaCatalogFactory at all.
            implementation(libs.ksoup)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okhttp.mockwebserver)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

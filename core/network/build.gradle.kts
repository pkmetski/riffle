plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":core:domain"))
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp) {
        // okhttp-sse:4.x is a stale transitive dep — OkHttp 5 bundles SSE in its main artifact.
        // The 4.x jar references okhttp3.internal.Util which was removed in 5.x, breaking R8.
        exclude(group = "com.squareup.okhttp3", module = "okhttp-sse")
    }
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

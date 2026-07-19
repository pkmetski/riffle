plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core:common"))
    api(project(":core:models"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    // Use-case classes carry @Inject so Hilt can wire them through the data/app graph.
    implementation("javax.inject:javax.inject:1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

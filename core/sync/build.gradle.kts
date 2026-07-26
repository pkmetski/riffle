plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:models"))
    implementation(project(":core:catalog"))
    implementation(project(":core:sources"))
    implementation(libs.kotlinx.coroutines.core)
    implementation("javax.inject:javax.inject:1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

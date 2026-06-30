plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Enforces that `Log.[dweiv]("RIFFLE_…"` literals only live in core/logging.
// Anything else: route the call through `Logger` + `LogChannel`. See #337.
// Excludes RIFFLE_TEST (androidTest tag).
tasks.register("checkRiffleLogTags") {
    group = "verification"
    description = "Fails if any RIFFLE_* log-tag literal escapes core/logging."
    notCompatibleWithConfigurationCache("reading the file system at execution time")

    doLast {
        val forbidden = Regex("""Log\.[dweiv]\("RIFFLE_(?!TEST)""")
        val allowedRoot = layout.projectDirectory.dir("core/logging/src/main").asFile.absolutePath
        val scanRoots = listOf(
            layout.projectDirectory.dir("app/src").asFile,
            layout.projectDirectory.dir("core").asFile,
        ).filter { it.exists() }

        val offenders = mutableListOf<String>()
        scanRoots
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.absolutePath.startsWith(allowedRoot) }
            .forEach { f ->
                f.useLines { lines ->
                    lines.forEachIndexed { idx, line ->
                        if (forbidden.containsMatchIn(line)) {
                            offenders += "${f.relativeTo(layout.projectDirectory.asFile)}:${idx + 1} — $line"
                        }
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "RIFFLE_* log-tag literals must live in core/logging/.../LogChannel.kt.\n" +
                    "Route these through Logger + LogChannel (see #337):\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}

// Make it part of the normal `./gradlew check` run.
allprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkRiffleLogTags"))
    }
}

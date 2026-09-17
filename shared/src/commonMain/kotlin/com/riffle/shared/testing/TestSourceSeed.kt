package com.riffle.shared.testing

import com.riffle.core.models.SourceType

/**
 * A credentialed source the app should add on launch, requested by the UI-test harness through
 * a launch argument so every test does not have to drive the add-source screens (25–40 s each on
 * CI). Seeding runs the production authenticate → commit path; only the UI is skipped.
 *
 * Argument shape: `--RIFFLE_SEED_SOURCE=<SourceType>|<url>|<username>|<password>`.
 */
data class TestSourceSeed(
    val type: SourceType,
    val url: String,
    val username: String,
    val password: String,
) {
    /** Plain-HTTP stub servers need the same insecure-connection consent the user gives in the UI. */
    val insecureAllowed: Boolean get() = url.startsWith("http://", ignoreCase = true)

    companion object {
        const val LAUNCH_ARGUMENT = "--RIFFLE_SEED_SOURCE="
        private const val SEPARATOR = '|'

        /** Returns the seed encoded in [arguments], or null when no (valid) seed argument is present. */
        fun parse(arguments: List<String>): TestSourceSeed? {
            val raw = arguments.firstOrNull { it.startsWith(LAUNCH_ARGUMENT) }
                ?.removePrefix(LAUNCH_ARGUMENT) ?: return null
            val parts = raw.split(SEPARATOR)
            if (parts.size != 4 || parts.any { it.isEmpty() }) return null
            val type = SourceType.entries.firstOrNull { it.name == parts[0] } ?: return null
            return TestSourceSeed(type = type, url = parts[1], username = parts[2], password = parts[3])
        }
    }
}

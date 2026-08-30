package com.riffle.core.data

/**
 * Single home for the per-device annotation-file naming scheme (`annotations-<deviceId>.jsonld`).
 *
 * Producers (sync controller + sweep) and consumers (WebDAV / local-directory targets) all funnel
 * through here so a future rename — versioned suffix, gzip variant, per-app-instance prefix —
 * touches one place instead of ~7. Per AGENTS.md "Always reference constants, never the literal":
 * a typo'd literal (`.json` vs `.jsonld`, missing `-`, capitalization drift) silently no-ops peer
 * discovery and looks correct in code review.
 */
internal object AnnotationFilenames {
    const val PREFIX: String = "annotations-"
    const val SUFFIX: String = ".jsonld"

    /** `annotations-<deviceId>.jsonld` — this device's annotation file for one book. */
    fun forDevice(deviceId: String): String = "$PREFIX$deviceId$SUFFIX"

    /** True when [filename] follows the per-device annotation-file scheme. */
    fun matches(filename: String): Boolean =
        filename.startsWith(PREFIX) && filename.endsWith(SUFFIX)

    /** Extract the device id from an annotation filename, or null if [filename] doesn't match. */
    fun deviceIdOf(filename: String): String? =
        if (matches(filename)) filename.removePrefix(PREFIX).removeSuffix(SUFFIX) else null
}

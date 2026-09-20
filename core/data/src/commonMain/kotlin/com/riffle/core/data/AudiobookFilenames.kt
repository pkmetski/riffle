package com.riffle.core.data

/**
 * Single home for the on-disk audiobook layout's well-known filenames (ADR 0035).
 *
 * `manifest.json` is written **last** by every downloader, so its presence is the atomic
 * completion marker: a scanner that misses it treats a finished download as partial and hides the
 * book. The literal used to be spelled out in six places across `androidMain` and `iosMain` —
 * three of them behind their own private `MANIFEST_NAME`/`AUDIOBOOK_MANIFEST_NAME` constant — which
 * is exactly the shape AGENTS.md's "always reference constants, never the literal" rule exists to
 * stop: a one-character drift on one platform reads as correct in review and silently empties the
 * downloads list on that platform only.
 */
internal object AudiobookFilenames {

    /** The completion marker for a downloaded or cached audiobook item directory. */
    const val MANIFEST: String = "manifest.json"

    /** `<itemDir>/manifest.json`. */
    fun manifestIn(itemDir: String): String = "$itemDir/$MANIFEST"
}

package com.riffle.feature.designsystem

import com.riffle.core.logging.LogChannel
import com.riffle.core.logging.Logger

/**
 * The [CoverLoadReporter] both hosts install: every cover fetch is logged to
 * [LogChannel.Covers] (`RIFFLE_COVERS`).
 *
 * This is the instrumentation that used to be `ImageRequest.Builder.instrumentCover(…)` inside
 * `app/.../LibraryItemsScreen.kt`, applied directly by Android's `BookCoverTile`. It exists to
 * tell "the cover never loaded" apart from "the cover is re-fetched from the network every
 * time" — the signal behind the offline-cover investigation. The tile now lives in this module,
 * which cannot see `android.util.Log`, so the tile reports through [CoverLoadReporter] and this
 * turns the report into a log line through the shared [Logger] seam.
 *
 * Shared rather than Android-only because iOS had no cover logging at all, which is exactly the
 * kind of gap that makes an iOS cover bug undiagnosable. `CoverLoadLoggingTest` pins the line
 * format on both platforms; `adb logcat -d | grep RIFFLE_COVERS` is unchanged.
 */
class LoggingCoverLoadReporter(private val logger: Logger) : CoverLoadReporter {
    override fun report(kind: String, key: String?, url: String?, hit: Boolean, detail: String?) {
        logger.d(LogChannel.Covers) { coverLogLine(kind, key, url, hit, detail) }
    }
}

/**
 * The `RIFFLE_COVERS` line, byte-identical to the one the Android-only extension emitted:
 *
 * ```
 * hit kind=item key=abs-1 source=DISK url=https://…
 * miss kind=item key=abs-1 err=SocketTimeoutException url=https://…
 * ```
 */
internal fun coverLogLine(kind: String, key: String?, url: String?, hit: Boolean, detail: String?): String =
    if (hit) {
        "hit kind=$kind key=$key source=$detail url=$url"
    } else {
        "miss kind=$kind key=$key err=$detail url=$url"
    }

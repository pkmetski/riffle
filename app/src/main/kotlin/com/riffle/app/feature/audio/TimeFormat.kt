package com.riffle.app.feature.audio

import com.riffle.core.domain.AudiobookChapter
import com.riffle.feature.player.formatHms as formatHmsShared
import com.riffle.feature.player.formatRemainingReadable as formatRemainingReadableShared
import com.riffle.feature.player.notificationArtistText as notificationArtistTextShared

internal fun formatHms(totalSec: Double): String = formatHmsShared(totalSec)
fun formatRemainingReadable(remainingSec: Double): String = formatRemainingReadableShared(remainingSec)
internal fun notificationArtistText(chapter: AudiobookChapter?, remainingSec: Double): String =
    notificationArtistTextShared(chapter, remainingSec)

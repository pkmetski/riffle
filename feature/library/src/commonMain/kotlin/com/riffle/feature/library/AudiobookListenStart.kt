package com.riffle.feature.library

fun listenStartAtSecForFinished(readingProgress: Float): Double? =
    if (readingProgress >= 1.0f) 0.0 else null

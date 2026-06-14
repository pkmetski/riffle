package com.riffle.app.feature.audiobook

sealed interface SleepTimerMode {
    data object None : SleepTimerMode
    data class CountDown(val remainingMs: Long) : SleepTimerMode
    data object EndOfChapter : SleepTimerMode
}

fun SleepTimerMode.formatCountdown(): String {
    if (this !is SleepTimerMode.CountDown) return ""
    val totalSec = remainingMs / 1_000L
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

package com.riffle.core.domain

data class LocalMinuteTime(val hour: Int, val minute: Int) : Comparable<LocalMinuteTime> {
    override fun compareTo(other: LocalMinuteTime): Int =
        compareValuesBy(this, other, LocalMinuteTime::hour, LocalMinuteTime::minute)

    companion object {
        fun of(hour: Int, minute: Int): LocalMinuteTime = LocalMinuteTime(hour, minute)
    }
}

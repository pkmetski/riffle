package com.riffle.core.domain

data class LocalMinuteTime(val hour: Int, val minute: Int) : Comparable<LocalMinuteTime> {
    init {
        require(hour in 0..23 && minute in 0..59) {
            "Invalid time: hour=$hour, minute=$minute"
        }
    }
    override fun compareTo(other: LocalMinuteTime): Int =
        compareValuesBy(this, other, LocalMinuteTime::hour, LocalMinuteTime::minute)

    companion object {
        fun of(hour: Int, minute: Int): LocalMinuteTime = LocalMinuteTime(hour, minute)
    }
}

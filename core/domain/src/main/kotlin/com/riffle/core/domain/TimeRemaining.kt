package com.riffle.core.domain

sealed class TimeRemaining {
    abstract val sec: Long
    data class Estimated(override val sec: Long) : TimeRemaining()
    data class Exact(override val sec: Long) : TimeRemaining()
}

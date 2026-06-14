package com.riffle.core.domain

sealed class TimeRemaining(val sec: Long) {
    class Estimated(sec: Long) : TimeRemaining(sec)
    class Exact(sec: Long) : TimeRemaining(sec)
}

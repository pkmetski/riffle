package com.riffle.app.feature.reader

import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

// Lets the reader VMs read "now" in a way tests can substitute. Production binding
// returns LocalTime.now() each call; tests inject a fake.
interface TimeProvider {
    fun nowLocalTime(): LocalTime
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowLocalTime(): LocalTime = LocalTime.now()
}

package com.riffle.core.domain

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSDate

/** Production [TimeProvider] on iOS. */
object SystemTimeProvider : TimeProvider {
    override fun nowLocalTime(): LocalMinuteTime {
        val calendar = NSCalendar.currentCalendar
        val components = calendar.components(NSCalendarUnitHour or NSCalendarUnitMinute, fromDate = NSDate())
        return LocalMinuteTime(components.hour.toInt(), components.minute.toInt())
    }
}

package com.riffle.core.sources.webdav

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The Basic-auth header every WebDAV request carries.
 *
 * One definition rather than the three identical `java.util.Base64.getEncoder()` expressions the
 * target, the progress factory and the enumerator each used to carry — `kotlin.io.encoding.Base64`
 * is multiplatform, which is what lets all three compile for Kotlin/Native unchanged. Same
 * encoding, same output: RFC 4648 standard alphabet with padding, over UTF-8 bytes.
 */
@OptIn(ExperimentalEncodingApi::class)
fun webDavBasicAuthHeader(username: String, password: String): String =
    "Basic " + Base64.encode("$username:$password".encodeToByteArray())

/** The single-quoted user agent WebDAV servers are most permissive towards. */
internal const val WEBDAV_USER_AGENT = "WebDAVFS/3.0.0 (03008000) Darwin/22.0.0 (x86_64)"

internal const val WEBDAV_XML_CONTENT_TYPE = "application/xml; charset=utf-8"

internal const val WEBDAV_PROPFIND_BODY =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/></d:prop></d:propfind>"

/**
 * Parses an RFC 1123 HTTP-date ("Mon, 18 Aug 2025 12:00:00 GMT") to epoch milliseconds, or null
 * when the value is not one.
 *
 * Replaces `SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)`, which has no
 * Kotlin/Native equivalent. Scope is deliberately exactly what a WebDAV `Last-Modified` carries:
 * RFC 1123 with a `GMT`/`UTC` zone or a numeric `±hhmm` offset. The obsolete RFC 850 and asctime
 * forms are not accepted — `SimpleDateFormat` would not have parsed them with this pattern
 * either, so nothing that used to work stops working.
 */
fun parseHttpDate(value: String): Long? {
    val text = value.trim()
    // "Mon, 18 Aug 2025 12:00:00 GMT"
    val afterComma = text.substringAfter(',', missingDelimiterValue = "").trim()
    if (afterComma.isEmpty()) return null
    val parts = afterComma.split(' ').filter { it.isNotEmpty() }
    if (parts.size < 5) return null

    val day = parts[0].toIntOrNull() ?: return null
    val month = MONTHS.indexOfFirst { it.equals(parts[1], ignoreCase = true) }.takeIf { it >= 0 } ?: return null
    val year = parts[2].toIntOrNull() ?: return null

    val time = parts[3].split(':')
    if (time.size != 3) return null
    val hour = time[0].toIntOrNull() ?: return null
    val minute = time[1].toIntOrNull() ?: return null
    val second = time[2].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null
    if (day !in 1..31) return null

    val offsetSeconds = parseZoneOffsetSeconds(parts[4]) ?: return null

    val days = daysFromCivil(year, month + 1, day)
    val secondsOfDay = hour * 3600L + minute * 60L + second
    return (days * 86_400L + secondsOfDay - offsetSeconds) * 1000L
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** "GMT"/"UTC"/"Z" → 0; "+0200"/"-0500" → the offset in seconds; anything else → null. */
private fun parseZoneOffsetSeconds(zone: String): Long? {
    if (zone.equals("GMT", ignoreCase = true) || zone.equals("UTC", ignoreCase = true) || zone == "Z") return 0L
    if (zone.length != 5) return null
    val sign = when (zone[0]) {
        '+' -> 1L
        '-' -> -1L
        else -> return null
    }
    val hours = zone.substring(1, 3).toIntOrNull() ?: return null
    val minutes = zone.substring(3, 5).toIntOrNull() ?: return null
    return sign * (hours * 3600L + minutes * 60L)
}

/**
 * Days since 1970-01-01 for a proleptic-Gregorian civil date. Howard Hinnant's `days_from_civil`,
 * the standard branch-free formulation — correct for every year the JVM's `GregorianCalendar`
 * would accept here, without a date library.
 *
 * [month] is 1-based.
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400 // [0, 399]
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1 // [0, 365]
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
    return era * 146_097 + doe - 719_468
}

package com.riffle.core.catalog.chitanka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on the JVM **and** on `iosSimulatorArm64`, which is the point: [ChitankaScraper.toAbsolute]
 * used to be built on `java.net.URI` and [parseCategories][ChitankaScraper.parseCategories] on
 * `java.text.Collator`, neither of which exists outside the JVM. These assertions pin the shared
 * replacements so a divergence on either platform fails the build.
 */
class ChitankaUrlResolutionTest {

    @Test
    fun rootRelativeHrefResolvesAgainstChitankaBase() {
        assertEquals("https://chitanka.info/book/1", ChitankaScraper.toAbsolute("/book/1"))
    }

    @Test
    fun absoluteHrefIsPassedThroughUnchanged() {
        assertEquals(
            "https://chitanka.info/book/1",
            ChitankaScraper.toAbsolute("https://chitanka.info/book/1"),
        )
    }

    @Test
    fun schemeRelativeHrefGetsHttpsScheme() {
        assertEquals(
            "https://gramofonche.chitanka.info/foo.mp3",
            ChitankaScraper.toAbsolute("//gramofonche.chitanka.info/foo.mp3"),
        )
    }

    @Test
    fun rawSpacesInRelativeHrefArePercentEncoded() {
        // Gramofonche mp3 anchors carry raw spaces in the filename. Without the space→%20
        // pre-encoding the resolver throws, the fallback yields a URL with literal spaces that
        // the HTTP client cannot parse, and every chapter renders as 0:00 in the drawer.
        assertEquals(
            "https://gramofonche.chitanka.info/prikazki/grycki-legendi/dir/1-Vremeto%20na%20prabogovete.mp3",
            ChitankaScraper.toAbsolute(
                "./dir/1-Vremeto na prabogovete.mp3",
                pageUrl = "https://gramofonche.chitanka.info/prikazki/grycki-legendi/",
            ),
        )
    }

    @Test
    fun nullAndEmptyHrefsResolveToEmptyString() {
        assertEquals("", ChitankaScraper.toAbsolute(null))
        assertEquals("", ChitankaScraper.toAbsolute(""))
    }

    @Test
    fun dotSegmentsAreRemovedFromMergedPaths() {
        assertEquals(
            "https://gramofonche.chitanka.info/prikazki/a.mp3",
            ChitankaScraper.toAbsolute(
                "../a.mp3",
                pageUrl = "https://gramofonche.chitanka.info/prikazki/grycki-legendi/",
            ),
        )
    }

    @Test
    fun cyrillicPathsArePercentEncodedAsUtf8() {
        // java.net.URI.toASCIIString() did this for free; the shared resolver has to do it by
        // hand, and getting it wrong sends raw Cyrillic bytes at the origin.
        assertEquals(
            "https://chitanka.info/%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0/1",
            ChitankaScraper.toAbsolute("/книга/1"),
        )
    }

    @Test
    fun alreadyEncodedTriplesAreNotDoubleEncoded() {
        assertEquals(
            "https://chitanka.info/a%20b/c.epub",
            ChitankaScraper.toAbsolute("/a%20b/c.epub"),
        )
    }

    @Test
    fun unparseableHrefFallsBackToBasePlusHref() {
        // A character java.net.URI refused to parse used to trip the catch and produce
        // BASE + href; the shared resolver rejects the same characters so the fallback still
        // fires instead of silently emitting a URL the old code never produced.
        assertEquals(
            "https://chitanka.info/bad|char",
            ChitankaScraper.toAbsolute("bad|char", pageUrl = "https://gramofonche.chitanka.info/prikazki/"),
        )
    }
}

/** Pins the Collator replacement used to order the category chip strip. */
class BulgarianLabelOrderTest {

    @Test
    fun ordersTheBulgarianAlphabetInItsOwnOrder() {
        val alphabet = listOf(
            "А", "Б", "В", "Г", "Д", "Е", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О",
            "П", "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ь", "Ю", "Я",
        )
        assertEquals(
            alphabet,
            alphabet.reversed().sortedWith(BULGARIAN_LABEL_ORDER),
            "Bulgarian letters must sort in alphabet order, not reverse or Latin-first order",
        )
    }

    @Test
    fun ordersRealCategoryLabels() {
        val labels = listOf("Фантастика", "Драма", "Българска литература", "Хумор", "Езотерика")
        assertEquals(
            listOf("Българска литература", "Драма", "Езотерика", "Фантастика", "Хумор"),
            labels.sortedWith(BULGARIAN_LABEL_ORDER),
        )
    }

    @Test
    fun ignoresCaseWhenComparingLetters() {
        // Collator's primary strength put "азбука" before "Български"; a naive code-point
        // compare would put every capital letter ahead of every lowercase one instead.
        assertTrue(
            BULGARIAN_LABEL_ORDER.compare("азбука", "Български") < 0,
            "case must not dominate the letter comparison",
        )
        assertTrue(BULGARIAN_LABEL_ORDER.compare("Ъгъл", "Юни") < 0)
    }

    @Test
    fun sortsDigitsAndLatinBeforeCyrillic() {
        assertEquals(
            listOf("1984", "Noir", "Поезия"),
            listOf("Поезия", "Noir", "1984").sortedWith(BULGARIAN_LABEL_ORDER),
        )
    }

    @Test
    fun isATotalOrderForLabelsDifferingOnlyInCase() {
        assertTrue(BULGARIAN_LABEL_ORDER.compare("Драма", "Драма") == 0)
        assertTrue(BULGARIAN_LABEL_ORDER.compare("Драма", "драма") != 0)
    }
}

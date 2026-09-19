package com.riffle.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins that every target's [normalizeToNfd] produces the *same* canonical decomposition.
 *
 * `OwnedItemMatcher.normalizeTitle` strips diacritics by decomposing and then deleting the
 * `U+0300..U+036F` combining block. That only works if the JVM actual
 * (`java.text.Normalizer.Form.NFD`) and the iOS actual
 * (`NSString.decomposedStringWithCanonicalMapping`) agree — if one of them ever stopped
 * decomposing, owned-item matching would silently start missing accented titles on that platform
 * only. These assertions run on both `jvmTest` and `iosSimulatorArm64Test`.
 */
class UnicodeNormalizationTest {

    @Test
    fun latinPrecomposedAccentDecomposesToBaseLetterPlusCombiningMark() {
        // é U+00E9 -> e U+0065 + combining acute U+0301
        assertEquals("é", normalizeToNfd("é"))
        // ü U+00FC -> u U+0075 + combining diaeresis U+0308
        assertEquals("ü", normalizeToNfd("ü"))
    }

    @Test
    fun cyrillicPrecomposedLettersDecomposeIntoTheCombiningBlock() {
        // The owned-item corpus is Bulgarian. й U+0439 -> и U+0438 + combining breve U+0306,
        // which sits inside the U+0300..U+036F range the matcher strips.
        assertEquals("й", normalizeToNfd("й"))
    }

    @Test
    fun charactersWithNoDecompositionArePassedThroughUnchanged() {
        assertEquals("Аладин", normalizeToNfd("Аладин"))
        assertEquals("The Hobbit", normalizeToNfd("The Hobbit"))
        assertEquals("", normalizeToNfd(""))
    }

    @Test
    fun alreadyDecomposedInputIsIdempotent() {
        val once = normalizeToNfd("Café")
        assertEquals(once, normalizeToNfd(once))
    }
}

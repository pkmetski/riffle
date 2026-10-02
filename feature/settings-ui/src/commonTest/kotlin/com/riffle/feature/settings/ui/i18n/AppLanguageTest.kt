package com.riffle.feature.settings.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun fromTagReturnsSystemForNull() = assertEquals(AppLanguage.System, AppLanguage.fromTag(null))

    @Test
    fun fromTagReturnsSystemForBlank() = assertEquals(AppLanguage.System, AppLanguage.fromTag(""))

    @Test
    fun fromTagMatchesExactTag() = assertEquals(AppLanguage.Spanish, AppLanguage.fromTag("es-ES"))

    @Test
    fun fromTagMatchesLanguageCodePrefix() = assertEquals(AppLanguage.Bulgarian, AppLanguage.fromTag("bg"))

    @Test
    fun fromTagMatchesLanguageWithRegion() = assertEquals(AppLanguage.English, AppLanguage.fromTag("en-GB"))

    @Test
    fun fromTagReturnsSystemForUnknownTag() = assertEquals(AppLanguage.System, AppLanguage.fromTag("zh-CN"))

    @Test
    fun fromTagStripsCommaVariantSuffix() = assertEquals(AppLanguage.Spanish, AppLanguage.fromTag("es-ES,en-US"))
}

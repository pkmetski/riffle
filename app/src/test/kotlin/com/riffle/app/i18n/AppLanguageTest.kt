package com.riffle.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    @Test
    fun `blank tag resolves to system`() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
    }

    @Test
    fun `supported regional tags resolve to their app language`() {
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en-US"))
        assertEquals(AppLanguage.Bulgarian, AppLanguage.fromTag("bg-BG"))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromTag("es-ES"))
    }

    @Test
    fun `unsupported tag falls back to system`() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag("fr-FR"))
    }
}

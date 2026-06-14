package com.riffle.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeTest {

    @Test
    fun `Light is never dark`() {
        assertEquals(false, AppTheme.Light.isDark(systemInDark = true))
        assertEquals(false, AppTheme.Light.isDark(systemInDark = false))
    }

    @Test
    fun `Dark is always dark`() {
        assertEquals(true, AppTheme.Dark.isDark(systemInDark = true))
        assertEquals(true, AppTheme.Dark.isDark(systemInDark = false))
    }

    @Test
    fun `System follows the OS setting`() {
        assertEquals(true, AppTheme.System.isDark(systemInDark = true))
        assertEquals(false, AppTheme.System.isDark(systemInDark = false))
    }
}

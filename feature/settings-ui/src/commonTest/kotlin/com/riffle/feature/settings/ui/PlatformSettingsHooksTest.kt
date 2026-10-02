package com.riffle.feature.settings.ui

import kotlin.test.Test
import kotlin.test.assertFalse

class PlatformSettingsHooksTest {
    @Test
    fun defaultHooksCannotInstallUpdate() {
        assertFalse(DefaultPlatformSettingsHooks.canInstallUpdate())
    }
}

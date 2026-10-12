package com.riffle.feature.settings.ui

import kotlin.test.Test
import kotlin.test.assertFalse

class PlatformSettingsHooksTest {
    @Test
    fun defaultHooksCannotInstallUpdate() {
        assertFalse(DefaultPlatformSettingsHooks.canInstallUpdate())
    }

    @Test
    fun onViewReleaseUrlIsNoOpInDefaultHooks() {
        // DefaultPlatformSettingsHooks must not crash — it is the safe fallback used when no
        // platform-specific hook overrides it (e.g. on Android, which uses the Update button
        // path and never calls onViewReleaseUrl).
        DefaultPlatformSettingsHooks.onViewReleaseUrl("https://github.com/pkmetski/riffle/releases/tag/v1.0")
    }
}

package com.riffle.shared.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.riffle.feature.settings.ui.PlatformSettingsHooks
import com.riffle.feature.settings.ui.i18n.AppLanguage
import platform.Foundation.NSUserDefaults

/**
 * iOS implementation of [PlatformSettingsHooks].
 *
 * Language switching is implemented via the standard `AppleLanguages` UserDefaults key that
 * Compose Multiplatform Resources reads on app start. The change takes effect after a relaunch;
 * the hooks shows a dialog asking the user to restart.
 */
object IosPlatformSettingsHooks : PlatformSettingsHooks {

    @Composable
    override fun LanguageRow() {
        // Language is surfaced via AppearanceSection in SettingsScreen; no extra row needed here.
    }

    override fun canInstallUpdate(): Boolean = false

    override fun currentLanguage(): AppLanguage {
        val languages = NSUserDefaults.standardUserDefaults.stringArrayForKey("AppleLanguages")
        val firstTag = languages?.firstOrNull() as? String ?: return AppLanguage.System
        return AppLanguage.fromTag(firstTag)
    }

    override fun onLanguageChanged(language: AppLanguage) {
        val defaults = NSUserDefaults.standardUserDefaults
        if (language == AppLanguage.System) {
            defaults.removeObjectForKey("AppleLanguages")
        } else {
            defaults.setObject(listOf(language.tag), "AppleLanguages")
        }
        defaults.synchronize()
        pendingRestart = true
    }

    @Composable
    override fun OnResumeEffect(block: () -> Unit) {
        // No lifecycle hook needed for this basic implementation.
    }
}

/** Set to true after a language change is committed, to trigger the restart dialog. */
private var pendingRestart = false

/**
 * Dialog shown after the user picks a new language. Must be composed somewhere visible —
 * [HomeScreen] places it at the top level so it appears regardless of which screen is active.
 */
@Composable
fun LanguageChangeRestartDialog(onDismiss: () -> Unit) {
    if (!pendingRestart) return
    var open by remember { mutableStateOf(true) }
    if (!open) return
    AlertDialog(
        onDismissRequest = {
            open = false
            pendingRestart = false
            onDismiss()
        },
        title = { Text("Restart required") },
        text = { Text("Please restart the app to apply the new language.") },
        confirmButton = {
            TextButton(onClick = {
                open = false
                pendingRestart = false
                onDismiss()
            }) {
                Text("OK")
            }
        },
    )
}

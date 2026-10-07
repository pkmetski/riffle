package com.riffle.shared.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.riffle.feature.settings.ui.PlatformSettingsHooks
import com.riffle.feature.settings.ui.i18n.AppLanguage
import com.riffle.feature.settings.ui.generated.resources.Res
import com.riffle.feature.settings.ui.generated.resources.ui_language_change_restart_body
import com.riffle.feature.settings.ui.generated.resources.ui_language_change_restart_title
import com.riffle.feature.settings.ui.generated.resources.ui_ok
import org.jetbrains.compose.resources.stringResource
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
        pendingRestart.value = true
    }

    @Composable
    override fun OnResumeEffect(block: () -> Unit) {
        // No lifecycle hook needed for this basic implementation.
    }
}

/**
 * Snapshot state flag — writing from [IosPlatformSettingsHooks.onLanguageChanged] triggers
 * recomposition of any composable that reads it (i.e. [LanguageChangeRestartDialog]).
 */
private val pendingRestart = mutableStateOf(false)

/**
 * Dialog shown after the user picks a new language. Must be composed somewhere visible —
 * [HomeScreen] places it at the top level so it appears regardless of which screen is active.
 */
@Composable
fun LanguageChangeRestartDialog(onDismiss: () -> Unit) {
    // Always read pendingRestart before any conditional so Compose registers the observation.
    val show = pendingRestart.value
    if (!show) return
    AlertDialog(
        onDismissRequest = {
            pendingRestart.value = false
            onDismiss()
        },
        title = { Text(stringResource(Res.string.ui_language_change_restart_title)) },
        text = { Text(stringResource(Res.string.ui_language_change_restart_body)) },
        confirmButton = {
            TextButton(onClick = {
                pendingRestart.value = false
                onDismiss()
            }) {
                Text(stringResource(Res.string.ui_ok))
            }
        },
    )
}

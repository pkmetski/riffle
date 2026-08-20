package com.riffle.app.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLocaleController {
    private const val PREFS = "app_locale"
    private const val KEY_LANGUAGE_TAG = "language_tag"

    fun currentLanguage(context: Context): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val platformTag = context
                .getSystemService(LocaleManager::class.java)
                ?.applicationLocales
                ?.toLanguageTags()
                .orEmpty()
            if (platformTag.isNotBlank()) return AppLanguage.fromTag(platformTag)
        }
        return AppLanguage.fromTag(storedTag(context))
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        val appContext = context.applicationContext
        appContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE_TAG, language.tag)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext
                .getSystemService(LocaleManager::class.java)
                ?.applicationLocales = LocaleList.forLanguageTags(language.tag)
        }
    }

    fun wrap(base: Context): Context {
        val tag = storedTag(base)
        if (tag.isBlank() || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val locales = LocaleList(locale)
        LocaleList.setDefault(locales)

        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(locales)
        return base.createConfigurationContext(configuration)
    }

    private fun storedTag(context: Context): String =
        context
            .applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_TAG, "")
            .orEmpty()
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

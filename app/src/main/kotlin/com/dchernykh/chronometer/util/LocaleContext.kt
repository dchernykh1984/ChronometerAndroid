package com.dchernykh.chronometer.util

import android.content.Context
import android.content.res.Configuration
import com.dchernykh.chronometer.data.AppLanguage
import java.util.Locale

/**
 * Applies the user-selected UI language by wrapping a base [Context] with an
 * overridden locale. Used from `MainActivity.attachBaseContext`, so the whole
 * activity (including Compose `stringResource`) resolves strings in that language.
 * [AppLanguage.SYSTEM] leaves the context untouched, i.e. the device locale wins.
 */
object LocaleContext {
    fun wrap(
        base: Context,
        language: AppLanguage,
    ): Context {
        if (language == AppLanguage.SYSTEM) {
            return base
        }
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

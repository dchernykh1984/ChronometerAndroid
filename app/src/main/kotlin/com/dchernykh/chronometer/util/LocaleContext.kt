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
 *
 * We deliberately do NOT touch the process-wide [Locale.getDefault]: overriding
 * only the context keeps localization scoped to the UI and avoids leaving the
 * default locale stuck on a previously chosen language after switching back to
 * SYSTEM (which would skew default-locale formatting like `String.format`).
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
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

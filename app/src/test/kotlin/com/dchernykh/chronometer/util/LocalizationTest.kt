package com.dchernykh.chronometer.util

import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

// Non-ASCII (Cyrillic) literals are banned in source by the no-non-ascii hook, so
// these tests assert that each language resolves to a *distinct* localized string
// rather than hard-coding the translated text.
@RunWith(RobolectricTestRunner::class)
class LocalizationTest {
    private fun context() = RuntimeEnvironment.getApplication()

    private fun settingsIn(language: AppLanguage): String =
        LocaleContext.wrap(context(), language).getString(R.string.settings)

    @Test
    fun englishIsTheDefaultResource() {
        assertEquals("Settings", settingsIn(AppLanguage.EN))
    }

    @Test
    fun eachLanguageResolvesToADistinctString() {
        val en = settingsIn(AppLanguage.EN)
        val ru = settingsIn(AppLanguage.RU)
        val kk = settingsIn(AppLanguage.KK)
        assertNotEquals(en, ru)
        assertNotEquals(en, kk)
        assertNotEquals(ru, kk)
        assertTrue(ru.isNotBlank())
        assertTrue(kk.isNotBlank())
    }

    @Test
    fun protocolTokensStayEnglishInEveryLocale() {
        val label = LocaleContext.wrap(context(), AppLanguage.RU).getString(R.string.finish_mode)
        assertTrue(label.contains("finish"))
        assertTrue(label.contains("nextLap"))
    }

    @Test
    fun wrapAppliesTheChosenLanguage() {
        val wrapped = LocaleContext.wrap(context(), AppLanguage.KK)
        assertEquals(
            "kk",
            wrapped.resources.configuration.locales[0]
                .language,
        )
    }

    @Test
    fun wrapWithSystemLeavesContextUnchanged() {
        val base = context()
        assertSame(base, LocaleContext.wrap(base, AppLanguage.SYSTEM))
    }

    @Test
    fun switchingBackToSystemDoesNotLeaveDefaultLocaleStuck() {
        val original = Locale.getDefault()
        // Pick manual languages, then return to SYSTEM.
        LocaleContext.wrap(context(), AppLanguage.KK)
        LocaleContext.wrap(context(), AppLanguage.RU)
        val system = LocaleContext.wrap(context(), AppLanguage.SYSTEM)

        // The process-wide default locale is never mutated by UI localization.
        assertEquals(original, Locale.getDefault())
        assertSame(context(), system)
    }
}

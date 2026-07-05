package com.dchernykh.chronometer.util

import com.dchernykh.chronometer.R
import com.dchernykh.chronometer.data.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LocalizationTest {
    private fun context() = RuntimeEnvironment.getApplication()

    @Test
    @Config(qualifiers = "en")
    fun englishIsTheDefaultResource() {
        assertEquals("Settings", context().getString(R.string.settings))
    }

    @Test
    @Config(qualifiers = "ru")
    fun russianStringsResolve() {
        assertEquals("Настройки", context().getString(R.string.settings))
    }

    @Test
    @Config(qualifiers = "kk")
    fun kazakhStringsResolve() {
        assertEquals("Баптаулар", context().getString(R.string.settings))
    }

    @Test
    @Config(qualifiers = "ru")
    fun protocolTokensStayEnglishInEveryLocale() {
        val label = context().getString(R.string.finish_mode)
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
        assertEquals("Баптаулар", wrapped.getString(R.string.settings))
    }

    @Test
    fun wrapWithSystemLeavesContextUnchanged() {
        val base = context()
        assertSame(base, LocaleContext.wrap(base, AppLanguage.SYSTEM))
    }
}

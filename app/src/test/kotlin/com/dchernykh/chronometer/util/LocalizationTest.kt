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
import java.io.File
import java.util.Locale

// Non-ASCII (Cyrillic) literals are banned in source by the no-non-ascii hook, so
// these tests assert that each language resolves to a *distinct* localized string
// rather than hard-coding the translated text.
@RunWith(RobolectricTestRunner::class)
class LocalizationTest {
    private fun context() = RuntimeEnvironment.getApplication()

    private fun settingsIn(language: AppLanguage): String =
        LocaleContext.wrap(context(), language).getString(R.string.settings)

    /** A language's strings, by name, as its strings.xml holds them. */
    private fun stringsOf(folder: String): Map<String, String> =
        STRING
            .findAll(File("src/main/res/$folder/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun everyStringExistsInEveryLanguageWithTheSamePlaceholders() {
        val english = stringsOf("values")
        assertTrue(english.size > 100)
        for (folder in listOf("values-ru", "values-kk")) {
            val other = stringsOf(folder)
            assertEquals(folder, english.keys, other.keys)
            for ((name, text) in english) {
                val placeholders = { s: String ->
                    PLACEHOLDER
                        .findAll(s)
                        .map { it.value }
                        .sorted()
                        .toList()
                }
                assertEquals("$folder $name", placeholders(text), placeholders(other.getValue(name)))
                assertTrue("$folder $name", other.getValue(name).isNotBlank())
            }
        }
    }

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
    fun uploadReasonIsLocalizedAndPreservesTheSystemDiagnostic() {
        val labels =
            listOf(AppLanguage.EN, AppLanguage.RU, AppLanguage.KK).map {
                LocaleContext.wrap(context(), it).getString(R.string.diag_upload_reason, "HTTP 403")
            }
        assertEquals(3, labels.distinct().size)
        assertTrue(labels.all { it.endsWith("HTTP 403") })
    }

    @Test
    fun deletionCopyIsLocalizedAndConfirmationIncludesTheSavedCutoff() {
        val locales = listOf(AppLanguage.EN, AppLanguage.RU, AppLanguage.KK)
        for (resource in listOf(R.string.delete_cutoff, R.string.delete_cutoff_title, R.string.delete_cutoff_failed)) {
            val labels = locales.map { LocaleContext.wrap(context(), it).getString(resource) }
            assertEquals(3, labels.distinct().size)
        }
        val confirmations =
            locales.map {
                LocaleContext.wrap(context(), it).getString(R.string.delete_cutoff_confirm, "42", "1 2:3:4.005")
            }
        assertEquals(3, confirmations.distinct().size)
        assertTrue(confirmations.all { it.contains("42") && it.contains("1 2:3:4.005") })
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

private val STRING = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
private val PLACEHOLDER = Regex("""%\d\$[sd]""")

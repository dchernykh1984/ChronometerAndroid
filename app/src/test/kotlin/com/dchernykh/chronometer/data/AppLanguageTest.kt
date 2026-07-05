package com.dchernykh.chronometer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun fromNameParsesKnownValues() {
        assertEquals(AppLanguage.KK, AppLanguage.fromName("KK"))
        assertEquals(AppLanguage.EN, AppLanguage.fromName("EN"))
    }

    @Test
    fun fromNameDefaultsToSystem() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromName(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromName("bogus"))
    }

    @Test
    fun tagsMapToBcp47Codes() {
        assertEquals("", AppLanguage.SYSTEM.tag)
        assertEquals("ru", AppLanguage.RU.tag)
        assertEquals("kk", AppLanguage.KK.tag)
        assertEquals("en", AppLanguage.EN.tag)
    }
}

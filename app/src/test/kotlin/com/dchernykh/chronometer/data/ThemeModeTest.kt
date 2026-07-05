package com.dchernykh.chronometer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun systemFollowsTheDeviceFlag() {
        assertTrue(ThemeMode.SYSTEM.resolvesToDark(systemInDark = true))
        assertFalse(ThemeMode.SYSTEM.resolvesToDark(systemInDark = false))
    }

    @Test
    fun lightIsAlwaysLight() {
        assertFalse(ThemeMode.LIGHT.resolvesToDark(systemInDark = true))
    }

    @Test
    fun darkIsAlwaysDark() {
        assertTrue(ThemeMode.DARK.resolvesToDark(systemInDark = false))
    }
}

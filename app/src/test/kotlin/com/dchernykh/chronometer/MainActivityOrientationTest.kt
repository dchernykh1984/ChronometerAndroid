package com.dchernykh.chronometer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityOrientationTest {
    // Phones (smallest width below the large-screen threshold) are locked to
    // portrait so the timing UI stays vertical-only.
    @Test
    fun phoneWidthsLockToPortrait() {
        assertTrue(isPhoneWidth(320))
        assertTrue(isPhoneWidth(411))
        assertTrue(isPhoneWidth(LARGE_SCREEN_WIDTH_DP - 1))
    }

    // Tablets / large screens are left unrestricted, matching Android's own
    // behaviour (it ignores orientation locks on large screens from API 36+).
    @Test
    fun largeScreenWidthsStayUnrestricted() {
        assertFalse(isPhoneWidth(LARGE_SCREEN_WIDTH_DP))
        assertFalse(isPhoneWidth(720))
        assertFalse(isPhoneWidth(1280))
    }
}

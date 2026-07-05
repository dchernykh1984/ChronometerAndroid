package com.dchernykh.chronometer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidationTest {
    @Test
    fun readyWhenEverythingPresent() {
        assertTrue(settings().isUploadReady)
    }

    @Test
    fun notReadyWithoutToken() {
        assertFalse(settings(token = "").isUploadReady)
    }

    @Test
    fun notReadyWithoutSiteUrl() {
        assertFalse(settings(siteUrl = "").isUploadReady)
    }

    @Test
    fun notReadyWithoutDeviceUuid() {
        assertFalse(settings(deviceUuid = "").isUploadReady)
    }

    @Test
    fun notReadyWhenSendDisabled() {
        assertFalse(settings(sendEnabled = false).isUploadReady)
    }

    @Test
    fun configuredIgnoresSendFlag() {
        assertTrue(settings(sendEnabled = false).isUploadConfigured)
    }

    @Test
    fun notConfiguredWithoutToken() {
        assertFalse(settings(token = "").isUploadConfigured)
    }

    @Test
    fun configuredWithHttpsScheme() {
        assertTrue(settings(siteUrl = "https://host").isUploadConfigured)
    }

    @Test
    fun notConfiguredWithFtpScheme() {
        assertFalse(settings(siteUrl = "ftp://host").isUploadConfigured)
    }

    @Test
    fun notConfiguredWithoutScheme() {
        assertFalse(settings(siteUrl = "host.example").isUploadConfigured)
    }

    @Test
    fun schemeCheckAcceptsOnlyHttpAndHttps() {
        assertTrue(hasAllowedUploadScheme("http://x"))
        assertTrue(hasAllowedUploadScheme("HTTPS://x"))
        assertFalse(hasAllowedUploadScheme("ftp://x"))
        assertFalse(hasAllowedUploadScheme("file:///x"))
        assertFalse(hasAllowedUploadScheme("noscheme"))
    }

    private fun settings(
        siteUrl: String = "http://site",
        token: String = "tok",
        pointNumber: Int = 0,
        deviceUuid: String = "uuid",
        sendEnabled: Boolean = true,
    ) = Settings(
        siteUrl = siteUrl,
        token = token,
        pointNumber = pointNumber,
        deviceUuid = deviceUuid,
        folderPath = "/folder",
        sendEnabled = sendEnabled,
    )
}

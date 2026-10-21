package com.dchernykh.chronometer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UploadErrorStoreTest {
    @Test
    fun noErrorBeforeTheFirstFailure() {
        assertNull(UploadErrorStore(RuntimeEnvironment.getApplication()).error.value)
    }

    @Test
    fun latestFailureIsPublishedAndSurvivesStoreRecreation() {
        val context = RuntimeEnvironment.getApplication()
        val store = UploadErrorStore(context)
        store.record("old-work", "HTTP 500")
        store.record("current-work", "HTTP 403: Invalid competition token")
        val expected = UploadError("current-work", "HTTP 403: Invalid competition token")
        assertEquals(expected, store.error.value)
        assertEquals(expected, UploadErrorStore(context).error.value)
    }
}

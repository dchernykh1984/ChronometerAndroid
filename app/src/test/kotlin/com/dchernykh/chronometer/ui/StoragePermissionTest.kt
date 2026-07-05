package com.dchernykh.chronometer.ui

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * All-files access is a deliberate decision (see docs/storage-access-decision.md);
 * these tests pin the missing/granted/revoked detection the UI relies on. SDK 29
 * exercises the runtime WRITE_EXTERNAL_STORAGE branch, which Robolectric lets us
 * grant and revoke deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StoragePermissionTest {
    @Test
    fun absentPermissionIsNotGranted() {
        assertFalse(StoragePermission.isGranted(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun grantedPermissionIsDetected() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertTrue(StoragePermission.isGranted(app))
    }

    @Test
    fun revokedPermissionIsNotGranted() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        assertFalse(StoragePermission.isGranted(app))
    }
}

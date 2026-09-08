package com.dronepeak.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    private fun release(version: String) = UpdateInfo(
        version = version,
        title = "v$version",
        changelog = "",
        downloadUrl = "https://example.invalid/DronePeak-v$version.apk",
        apkSize = 1L,
        publishedAt = "",
        sha256 = null
    )

    @Test
    fun `upstream 1_5_5 is newer than the last dp release`() {
        assertTrue(release("1.5.5").isNewerThan("1.5.3-dp.6"))
    }

    @Test
    fun `same release is not offered again`() {
        assertFalse(release("1.5.5").isNewerThan("1.5.5"))
    }

    @Test
    fun `older release is never offered`() {
        assertFalse(release("1.5.4").isNewerThan("1.5.5"))
    }
}

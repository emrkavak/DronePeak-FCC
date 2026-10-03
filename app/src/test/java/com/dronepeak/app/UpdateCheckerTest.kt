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

    /**
     * Published as v1.5.5-dp.4. Every DronePeak build shipped before it must
     * be offered the upgrade, otherwise the in-app updater silently does
     * nothing for those users.
     *
     * Version strings compare as digit groups, so the "-dp.4" suffix is what
     * makes this build rank above a plain "1.5.5": it appends a fourth group.
     */
    @Test
    fun `1_5_5_dp_4 is offered to every previously published DronePeak build`() {
        val published = release("1.5.5-dp.4")
        listOf("1.5.3-dp.2", "1.5.3-dp.6", "1.5.4", "1.5.5").forEach { installed ->
            assertTrue("$installed should be offered 1.5.5-dp.4", published.isNewerThan(installed))
        }
    }

    /** The suffix is what separates two DronePeak builds of the same upstream. */
    @Test
    fun `a later dp suffix outranks an earlier one of the same base`() {
        assertTrue(release("1.5.5-dp.4").isNewerThan("1.5.5-dp.3"))
        assertFalse(release("1.5.5-dp.4").isNewerThan("1.5.5-dp.4"))
    }
    @Test
    fun `design release upgrades existing controller installations`() {
        val published = release("1.5.5-dp.5")
        listOf("1.5.3-dp.2", "1.5.3-dp.6", "1.5.4", "1.5.5", "1.5.5-dp.4").forEach { installed ->
            assertTrue("$installed should be offered 1.5.5-dp.5", published.isNewerThan(installed))
        }
        assertFalse(published.isNewerThan("1.5.5-dp.5"))
    }

}

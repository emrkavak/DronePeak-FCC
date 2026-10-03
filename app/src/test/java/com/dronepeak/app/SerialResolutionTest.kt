package com.dronepeak.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the aircraft-serial resolution policy.
 *
 * This is the logic behind the "4G button doesn't work" report. A previous
 * revision dropped the active-query step, shortened the passive telemetry
 * window from 8s to 2s, and deleted the manual-entry path. The visible symptom
 * was 4G aborting at its serial guard while the app simultaneously showed the
 * controller as connected — the button was enabled and the app told the user to
 * tap Connect, which they had already done.
 *
 * These are pure assertions; the timings they protect live in
 * [FccViewModel] and need a real RC link to verify.
 */
class SerialResolutionTest {

    @Test
    fun `manual serial wins over every detected value`() {
        assertEquals(
            "1581FZJDL",
            SerialResolution.pick(
                manual = "1581FZJDL",
                session = "WA341",
                cached = "wa341"
            )
        )
    }

    @Test
    fun `session serial wins over the previous session cache`() {
        assertEquals(
            "WA341",
            SerialResolution.pick(manual = "", session = "WA341", cached = "WA233")
        )
    }

    @Test
    fun `falls back to the cache when nothing is in this session`() {
        assertEquals(
            "WA233",
            SerialResolution.pick(manual = "", session = "", cached = "WA233")
        )
    }

    @Test
    fun `nonempty manual entry takes precedence just as in upstream`() {
        // setManualSerial trims whitespace before storage; this helper matches upstream non-empty checks.
        assertEquals(
            "   ",
            SerialResolution.pick(manual = "   ", session = "wa341", cached = "wa341")
        )
    }

    @Test
    fun `no source at all yields empty and triggers probing`() {
        assertEquals("", SerialResolution.pick(manual = "", session = "", cached = ""))
    }

    @Test
    fun `model hint is extracted from a bare model code`() {
        assertEquals("wa341", SerialResolution.modelHint("WA341"))
        assertEquals("wm630", SerialResolution.modelHint("wm630"))
    }

    @Test
    fun `model hint is found inside a longer serial string`() {
        assertEquals("wa233", SerialResolution.modelHint("DJI-WA233-Matrice300"))
    }

    @Test
    fun `a factory serial carries no model code and that is not an error`() {
        // The probe can return the full 1581… serial, which has no W[AM]xxx in
        // it. A previous revision substituted serial.take(5) here, which turned
        // "1581FZJDL" into a bogus model id and logged it as one.
        assertNull(SerialResolution.modelHint("1581FZJDL"))
    }

    @Test
    fun `a short serial that merely looks similar is not treated as a model code`() {
        // "WM16X" is four characters; the pattern requires three digits.
        assertNull(SerialResolution.modelHint("WM16X"))
    }
}

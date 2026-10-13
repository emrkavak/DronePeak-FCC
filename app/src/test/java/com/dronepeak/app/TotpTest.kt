package com.dronepeak.app

import org.junit.Assert.*
import org.junit.Test

class TotpTest {
    private val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
    @Test fun rfcVectorsTruncatedToSixDigits() {
        listOf(59L to "287082", 1111111109L to "081804", 1111111111L to "050471",
            1234567890L to "005924", 2000000000L to "279037", 20000000000L to "353130")
            .forEach { (time, expected) -> assertEquals(expected, Totp.code(secret, time)) }
    }
    @Test fun currentPeriodOnly() {
        val previous = Totp.code(secret, 59)
        assertTrue(Totp.verify(secret, previous, 59))
        assertFalse(Totp.verify(secret, previous, 60))
        assertFalse(Totp.verify(secret, Totp.code(secret, 90), 60))
    }
    @Test fun strictInputAndSecret() {
        assertFalse(Totp.verify(secret, " 287082", 59))
        assertFalse(Totp.verify(secret, "28708", 59))
        assertFalse(Totp.verify(secret, "abcdef", 59))
        assertFalse(Totp.verify("invalid", "287082", 59))
        assertTrue(Totp.verify(secret, "005924", 1234567890L))
    }
    @Test fun activationIsVersionSpecific() {
        assertTrue(Totp.isActivated(36, 36))
        assertFalse(Totp.isActivated(35, 36))
        assertFalse(Totp.isActivated(-1, 36))
        assertFalse(Totp.isActivated(0, 0))
    }
}

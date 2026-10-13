package com.dronepeak.app

import org.junit.Assert.*
import org.junit.Test

class ShortcutInstallPolicyTest {
    @Test fun newInstallationDefaultsOnWithoutActivationDependency() {
        assertTrue(ShortcutInstallPolicy.wantsShortcut(1000, null))
    }
    @Test fun closeSurvivesReopenAndReboot() {
        repeat(3) { assertFalse(ShortcutInstallPolicy.wantsShortcut(1000, 1000)) }
    }
    @Test fun reinstallSameVersionReenablesShortcut() {
        assertTrue(ShortcutInstallPolicy.wantsShortcut(2000, 1000))
    }
    @Test fun unavailablePackageMetadataDoesNotStart() {
        assertFalse(ShortcutInstallPolicy.wantsShortcut(0, null))
    }
}

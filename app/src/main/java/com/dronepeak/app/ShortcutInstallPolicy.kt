package com.dronepeak.app

/** Closing belongs to one installation, not one version: reinstalling the same APK re-enables it. */
internal object ShortcutInstallPolicy {
    fun wantsShortcut(installedAt: Long, closedAt: Long?): Boolean =
        installedAt > 0 && closedAt != installedAt
}

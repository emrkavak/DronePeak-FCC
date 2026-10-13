package com.dronepeak.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ShortcutIssue { NONE, PERMISSION, UNAVAILABLE, CLOSED }
internal data class ShortcutState(val enabled: Boolean = false, val issue: ShortcutIssue = ShortcutIssue.NONE)

/** Navigation is available before activation; hardware authorization stays in the hardware components. */
internal object FloatingShortcutController {
    const val PREFS = "dronepeak_shortcut"
    private val mutableState = MutableStateFlow(ShortcutState())
    val state = mutableState.asStateFlow()
    var appVisible = false
        private set
    var service: FloatingShortcutService? = null
    private var starting = false
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun installTime(context: Context): Long = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    fun wanted(context: Context): Boolean {
        val preferences = prefs(context)
        val closedAt = if (preferences.contains("closed_install_time")) preferences.getLong("closed_install_time", 0) else null
        // The .7 enabled preference intentionally does not carry over to the default-on .8 policy.
        return ShortcutInstallPolicy.wantsShortcut(installTime(context), closedAt)
    }

    fun visible(context: Context, visible: Boolean) {
        appVisible = visible
        if (visible) ensureStarted(context)
        service?.refreshVisibility()
    }

    fun ensureStarted(context: Context) {
        if (!wanted(context)) {
            mutableState.value = ShortcutState(issue = if (installTime(context) > 0) ShortcutIssue.CLOSED else ShortcutIssue.UNAVAILABLE)
            context.stopService(Intent(context, FloatingShortcutService::class.java))
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            mutableState.value = ShortcutState(issue = ShortcutIssue.PERMISSION)
            context.stopService(Intent(context, FloatingShortcutService::class.java))
            return
        }
        if (service != null || starting) return
        starting = true
        runCatching { context.startForegroundService(Intent(context, FloatingShortcutService::class.java)) }
            .onFailure { failed(context) }
    }

    fun requestPermission(context: Context) {
        if (!wanted(context)) return
        if (Settings.canDrawOverlays(context)) { ensureStarted(context); return }
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
        }.onFailure { failed(context) }
    }

    fun needsPermissionIntro(context: Context): Boolean = !prefs(context).getBoolean("permission_intro_seen", false)
    fun markPermissionIntroSeen(context: Context) { prefs(context).edit().putBoolean("permission_intro_seen", true).apply() }

    /** Persist before stopping so neither a sticky restart nor BOOT_COMPLETED can resurrect a closed icon. */
    fun closeForInstallation(context: Context): Boolean {
        val stamp = installTime(context)
        if (stamp <= 0 || !prefs(context).edit().putLong("closed_install_time", stamp).commit()) {
            failed(context)
            return false
        }
        mutableState.value = ShortcutState(issue = ShortcutIssue.CLOSED)
        context.stopService(Intent(context, FloatingShortcutService::class.java))
        return true
    }

    fun ready(instance: FloatingShortcutService) {
        service = instance
        starting = false
        mutableState.value = ShortcutState(enabled = true)
        prefs(instance).edit().remove("last_error").apply()
    }
    fun stopped(instance: FloatingShortcutService) {
        if (service === instance) service = null
        starting = false
        mutableState.value = mutableState.value.copy(enabled = false)
    }
    fun failed(context: Context, issue: ShortcutIssue = ShortcutIssue.UNAVAILABLE) {
        starting = false
        mutableState.value = ShortcutState(issue = issue)
        prefs(context).edit().putString("last_error", issue.name).apply()
    }
}

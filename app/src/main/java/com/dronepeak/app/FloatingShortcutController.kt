package com.dronepeak.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class ShortcutIssue { NONE, PERMISSION, UNAVAILABLE, ACTIVATION }
internal data class ShortcutState(val enabled: Boolean = false, val issue: ShortcutIssue = ShortcutIssue.NONE)

/** User preference is separate from service liveness. BootReceiver intentionally never starts this service. */
internal object FloatingShortcutController {
    const val PREFS = "dronepeak_shortcut"
    private val mutableState = MutableStateFlow(ShortcutState())
    val state = mutableState.asStateFlow()
    var appVisible = false
        private set
    var service: FloatingShortcutService? = null
    private var starting = false

    fun visible(context: Context, visible: Boolean) {
        appVisible = visible
        if (visible) ensureStarted(context)
        service?.refreshVisibility()
    }

    fun ensureStarted(context: Context) {
        val wanted = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
        if (!wanted) { mutableState.value = ShortcutState(); return }
        if (!ActivationManager.isActivated(context)) {
            mutableState.value = ShortcutState(issue = ShortcutIssue.ACTIVATION)
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
            .onFailure { failed() }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) {
            context.stopService(Intent(context, FloatingShortcutService::class.java))
            mutableState.value = ShortcutState()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            mutableState.value = ShortcutState(issue = ShortcutIssue.PERMISSION)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")))
            }.onFailure { mutableState.value = ShortcutState(issue = ShortcutIssue.UNAVAILABLE) }
        } else ensureStarted(context)
    }

    fun ready(instance: FloatingShortcutService) {
        service = instance
        starting = false
        mutableState.value = ShortcutState(enabled = true)
    }
    fun stopped(instance: FloatingShortcutService) {
        if (service === instance) service = null
        starting = false
        mutableState.value = mutableState.value.copy(enabled = false)
    }
    fun failed(issue: ShortcutIssue = ShortcutIssue.UNAVAILABLE) {
        starting = false
        mutableState.value = ShortcutState(issue = issue)
    }
}

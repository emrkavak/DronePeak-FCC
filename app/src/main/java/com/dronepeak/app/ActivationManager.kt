package com.dronepeak.app

import android.content.Context

/** Offline convenience gate, not a device-bound license. The TOTP secret is embedded in the APK. */
internal object ActivationManager {
    private fun prefs(context: Context) = context.getSharedPreferences("dronepeak_activation", Context.MODE_PRIVATE)
    fun isActivated(context: Context): Boolean = Totp.isActivated(
        prefs(context).getInt("activated_version", -1), BuildConfig.VERSION_CODE)

    fun activate(context: Context, code: String): Boolean {
        if (!Totp.verify(BuildConfig.ACTIVATION_SECRET, code, System.currentTimeMillis() / 1000)) return false
        return prefs(context).edit().putInt("activated_version", BuildConfig.VERSION_CODE).commit()
    }
}

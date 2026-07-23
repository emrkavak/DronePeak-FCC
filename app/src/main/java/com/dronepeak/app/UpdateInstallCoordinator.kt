package com.dronepeak.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.io.FileInputStream

enum class InstallStartResult {
    STARTED,
    FAILED
}

/** Stages a verified APK through PackageInstaller so Android returns an explicit install result. */
class UpdateInstallCoordinator(private val context: Context) {
    fun start(apk: File, version: String): InstallStartResult {
        var sessionId = NO_SESSION_ID
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
            sessionId = installer.createSession(params)
            UpdateDiagnostics.record(context, "INSTALL_SESSION_CREATED id=$sessionId version=$version bytes=${apk.length()}")

            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val statusIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = UpdateInstallReceiver.ACTION_INSTALL_STATUS
                    putExtra(UpdateInstallReceiver.EXTRA_VERSION, version)
                    putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, sessionId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                session.commit(pendingIntent.intentSender)
            }
            UpdateDiagnostics.setResult(
                context,
                text("Android kurulum onayı bekleniyor.", "Waiting for Android install approval."),
                false,
                UpdateDiagnosticOutcome.WAITING_FOR_ANDROID
            )
            UpdateDiagnostics.record(context, "INSTALL_SESSION_COMMITTED id=$sessionId")
            return InstallStartResult.STARTED
        } catch (throwable: Throwable) {
            if (sessionId != NO_SESSION_ID) {
                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            UpdateDiagnostics.setResult(
                context,
                text("Kurulum hazırlanamadı: ${throwable.javaClass.simpleName}", "Installation could not be prepared: ${throwable.javaClass.simpleName}"),
                true
            )
            UpdateDiagnostics.record(context, "INSTALL_SESSION_ERROR ${throwable.javaClass.simpleName}: ${throwable.message}")
            return InstallStartResult.FAILED
        }
    }

    private companion object {
        const val NO_SESSION_ID = -1
    }

    private fun text(tr: String, en: String): String =
        if (AppLanguage.fromPref(context.getSharedPreferences("dronepeak", Context.MODE_PRIVATE).getString("language", null)) == AppLanguage.TR) tr else en
}

package com.dronepeak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
        UpdateDiagnostics.record(context, "INSTALL_STATUS id=$sessionId status=$status message=$message")

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmationIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirmationIntent == null) {
                UpdateDiagnostics.setResult(context, text(context, "Android kurulum onayı açılamadı.", "Android install approval could not be opened."), true)
                return
            }
            runCatching {
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirmationIntent)
            }.onSuccess {
                UpdateDiagnostics.setResult(
                    context,
                    text(context, "Android kurulum ekranı açık. Onayı tamamla.", "Android's install screen is open. Complete the approval."),
                    false,
                    UpdateDiagnosticOutcome.WAITING_FOR_ANDROID
                )
            }.onFailure {
                UpdateDiagnostics.setResult(context, text(context, "Android kurulum ekranı açılamadı: ${it.javaClass.simpleName}", "Android's install screen could not be opened: ${it.javaClass.simpleName}"), true)
                UpdateDiagnostics.record(context, "INSTALL_CONFIRMATION_ERROR ${it.javaClass.simpleName}: ${it.message}")
            }
            return
        }

        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                UpdateDiagnostics.setResult(context, text(context, "Güncelleme tamamlandı. DronePeak-FCC'yi yeniden açabilirsin.", "Update completed. You can reopen DronePeak-FCC."), false, UpdateDiagnosticOutcome.SUCCESS)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                UpdateDiagnostics.setResult(context, text(context, "Kurulum iptal edildi.", "Installation was cancelled."), true)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                UpdateDiagnostics.setResult(context, text(context, "Kurulum için yeterli depolama alanı yok.", "There is not enough storage space to install the update."), true)
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                UpdateDiagnostics.setResult(context, text(context, "Kurulum Android veya cihaz politikası tarafından engellendi.", "Installation was blocked by Android or device policy."), true)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                UpdateDiagnostics.setResult(context, text(context, "Kurulum imza veya paket çakışması nedeniyle reddedildi.", "Installation was rejected because of a package or signing conflict."), true)
            PackageInstaller.STATUS_FAILURE_INVALID ->
                UpdateDiagnostics.setResult(context, text(context, "İndirilen APK geçersiz veya bozuk.", "The downloaded APK is invalid or corrupted."), true)
            else ->
                UpdateDiagnostics.setResult(context, text(context, "Kurulum başarısız oldu${if (message.isNotBlank()) ": $message" else "."}", "Installation failed${if (message.isNotBlank()) ": $message" else "."}"), true)
        }
    }

    private fun text(context: Context, tr: String, en: String): String =
        if (AppLanguage.fromPref(context.getSharedPreferences("dronepeak", Context.MODE_PRIVATE).getString("language", null)) == AppLanguage.TR) tr else en

    companion object {
        const val ACTION_INSTALL_STATUS = "com.dronepeak.app.INSTALL_STATUS"
        const val EXTRA_VERSION = "version"
        const val EXTRA_SESSION_ID = "session_id"
    }
}

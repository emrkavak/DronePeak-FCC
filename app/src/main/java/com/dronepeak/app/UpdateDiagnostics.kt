package com.dronepeak.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UpdateDiagnosticReport(
    val summary: String,
    val details: String,
    val isFailure: Boolean,
    val outcome: UpdateDiagnosticOutcome
)

enum class UpdateDiagnosticOutcome {
    INFO,
    WAITING_FOR_ANDROID,
    SUCCESS,
    FAILURE
}

/** Persists a compact update trail so RC 2 diagnostics do not depend on ADB access. */
object UpdateDiagnostics {
    private const val PREFS = "dronepeak_update_diagnostics"
    private const val SUMMARY = "summary"
    private const val FAILURE = "failure"
    private const val OUTCOME = "outcome"
    private const val LOG_FILE = "update_diagnostics.log"
    private const val MAX_LINES = 80

    @Synchronized
    fun record(context: Context, event: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val file = File(context.filesDir, LOG_FILE)
        val lines = (if (file.isFile) file.readLines() else emptyList()) + "[$timestamp] $event"
        file.writeText(lines.takeLast(MAX_LINES).joinToString("\n") + "\n")
    }

    fun setResult(
        context: Context,
        summary: String,
        isFailure: Boolean,
        outcome: UpdateDiagnosticOutcome = if (isFailure) UpdateDiagnosticOutcome.FAILURE else UpdateDiagnosticOutcome.INFO
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SUMMARY, summary)
            .putBoolean(FAILURE, isFailure)
            .putString(OUTCOME, outcome.name)
            .apply()
        record(context, "RESULT outcome=$outcome failure=$isFailure summary=$summary")
    }

    fun report(context: Context): UpdateDiagnosticReport? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val summary = prefs.getString(SUMMARY, null) ?: return null
        val details = File(context.filesDir, LOG_FILE).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val outcome = runCatching {
            UpdateDiagnosticOutcome.valueOf(prefs.getString(OUTCOME, null).orEmpty())
        }.getOrElse {
            if (prefs.getBoolean(FAILURE, false)) UpdateDiagnosticOutcome.FAILURE else UpdateDiagnosticOutcome.INFO
        }
        return UpdateDiagnosticReport(summary, details, prefs.getBoolean(FAILURE, false), outcome)
    }

    fun clearResult(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(SUMMARY)
            .remove(FAILURE)
            .remove(OUTCOME)
            .apply()
    }

    fun recordUnhandledException(context: Context, throwable: Throwable) {
        val detail = throwable.stackTraceToString().lineSequence().take(24).joinToString("\n")
        setResult(context, "Uygulama beklenmedik şekilde durdu: ${throwable.javaClass.simpleName}", true)
        record(context, "UNCAUGHT_EXCEPTION\n$detail")
    }
}

package com.dronepeak.app

import android.app.Application

class DronePeakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { UpdateDiagnostics.recordUnhandledException(this, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

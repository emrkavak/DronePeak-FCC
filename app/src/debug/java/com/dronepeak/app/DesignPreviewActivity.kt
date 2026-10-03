package com.dronepeak.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent

/**
 * Renders the actual production UI from fixtures, without creating a ViewModel.
 * Hardware actions are no-ops. No controller, service or network is touched.
 * Launch with adb and --es scenario / --es language / --ei page for local QA.
 * This activity and its exported entry point exist only in debug builds.
 */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(16, 19, 21)),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(16, 19, 21))
        )
        val language = AppLanguage.fromPref(intent.getStringExtra("language"))
        val controller = if (intent.getStringExtra("controller") == "rcpro2") "DJI RC Pro 2" else "DJI RC 2"
        val connected = AppState(language = language, controllerModel = controller,
            isConnected = true, status = "connected", aircraftSerial = "1581FZJD000001",
            message = "Connected. Ready to apply FCC.")
        val state = when (intent.getStringExtra("scenario")) {
            "connected" -> connected
            "fcc" -> connected.copy(isFccEnabled = true, isKeepaliveRunning = true,
                status = "fcc_enabled", message = "FCC mode enabled — 21 frames sent")
            "busy" -> connected.copy(isFccEnabled = true, isKeepaliveRunning = true,
                isHardwareBusy = true, message = "Hardware busy — please wait for the current operation to finish.")
            "applying" -> connected.copy(isHardwareBusy = true, isBusy = true,
                status = "applying", busyProgress = 0.42f, message = "Enabling FCC mode...")
            "4g" -> connected.copy(fourGMessage = "All activation frames written successfully — check 4G status on the aircraft.")
            "4g_error" -> connected.copy(fourGMessage = "4G error: Unix socket unavailable")
            "4g_sending" -> connected.copy(is4gBusy = true, isHardwareBusy = true, busyProgress = 0.68f)
            "serial" -> connected.copy(isHardwareBusy = true, isProbingSerial = true)
            "manual" -> connected.copy(manualSerial = "1581FZJD000002")
            "info" -> connected.copy(deviceInfo = "Hardware: RC2\nBootloader: 1.0.0.1\nFirmware: 3.1.0.4\n\nRaw payload (26 bytes):\n55 01 02 03 04 05")
            "log" -> connected.copy(logMessages = listOf(
                "[14:32:08] All activation frames written successfully — check 4G status on the aircraft.",
                "[14:32:02] Sending 4G activation frames...",
                "[14:31:45] FCC mode enabled — 21 frames sent",
                "[14:31:41] Controller connected"))
            "update", "download", "install" -> connected.copy(
                updateChecked = true, updateAvailable = true,
                updateInfo = UpdateInfo("1.5.5-dp.5", "DronePeak", "Controller interface update.",
                    "https://example.invalid/DronePeak.apk", 12000000, "2026-10-02", null),
                isDownloadingUpdate = intent.getStringExtra("scenario") == "download",
                updateDownloadProgress = 0.42f,
                isUpdateDownloaded = intent.getStringExtra("scenario") == "install",
                updateStage = when (intent.getStringExtra("scenario")) {
                    "download" -> UpdateStage.DOWNLOADING
                    "install" -> UpdateStage.NEEDS_INSTALL_PERMISSION
                    else -> UpdateStage.NONE
                })
            else -> AppState(language = language, controllerModel = controller)
        }
        val page = intent.getIntExtra("page", 0).coerceIn(0, 3)
        setContent {
            DronePeakTheme {
                ControllerDashboard(state, ControllerActions(null), page)
            }
        }
    }
}

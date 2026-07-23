package com.dronepeak.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.io.File
import java.security.MessageDigest
import java.util.Date
import java.util.Locale

/**
 * Immutable UI state for the entire app.
 *
 * The ViewModel updates this via copy() and the Compose layer observes it
 * with collectAsStateWithLifecycle(). Every field here represents something
 * the UI needs to render.
 */
enum class UpdateStage {
    NONE,
    DOWNLOADING,
    VERIFYING,
    PREPARING_INSTALL,
    READY,
    NEEDS_INSTALL_PERMISSION,
    WAITING_FOR_ANDROID,
    COMPLETED,
    FAILED
}

data class AppState(
    val language: AppLanguage = AppLanguage.TR,
    val status: String = "idle",
    val message: String = "",
    val isConnected: Boolean = false,
    val isFccEnabled: Boolean = false,
    val is4gBusy: Boolean = false,
    val fourGMessage: String = "",
    val isBusy: Boolean = false,
    val isHardwareBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val controllerModel: String = "",
    val deviceInfo: String = "",
    val isQueryingInfo: Boolean = false,
    val autoFcc: Boolean = false,
    val isLedBusy: Boolean = false,
    val ledStatus: String = "",
    val logMessages: List<String> = emptyList(),
    // Update state
    val updateInfo: UpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val isUpdateDownloaded: Boolean = false,
    val profileUpdateMessage: String = "",
    val updateStage: UpdateStage = UpdateStage.NONE,
    val updateDiagnosticSummary: String = "",
    val updateDiagnosticDetails: String = "",
    val updateDiagnosticFailure: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateChecked: Boolean = false,
    // Keepalive state
    val isKeepaliveRunning: Boolean = false
)

/**
 * Manages all app state and business logic.
 *
 * The UI never touches the transport layer directly. It calls methods on
 * this ViewModel, which runs operations on a background thread (Dispatchers.IO)
 * and updates the observable [state] flow. The UI reacts to state changes
 * automatically via Compose's collectAsStateWithLifecycle().
 *
 * @param app The Application context, used for SharedPreferences and asset loading
 */
class FccViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        const val APP_VERSION = "1.5.3-dp.5"

        /**
         * Aircraft model codes known to support DJI Cellular Dongle 2 / 4G.
         * The Mini series (wa150, wa140, wm16x) does NOT support 4G — the
         * cellular module is enterprise hardware only. Sending 4G frames to a
         * non-4G aircraft wastes the user's time and produces a confusing
         * "frames written but 4G didn't activate" message.
         *
         * Sources: DJI product list, captured profiles (only wa341 confirmed
         * working on real hardware). wa233/wa234 = Matrice 300/350 series,
         * wm630 = Inspire 3, wa341 = Mavic 4 Pro. All are DJI enterprise models
         * that ship with or accept the Cellular Dongle 2.
         */
        private val MODELS_WITH_4G = setOf("wa341", "wa233", "wa234", "wm630", "wa140")
    }

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val transport = DumlTransport()
    private val prefs = app.getSharedPreferences("dronepeak", Context.MODE_PRIVATE)
    private val updateDownloadIdKey = "update_download_id"
    private val updateApkPathKey = "update_apk_path"
    private val updateVersionKey = "update_version"
    private val updateSha256Key = "update_sha256"
    private val text: UiText
        get() = TextCatalog.ui(_state.value.language)

    init {
        // MainActivity.onCreate() calls init() below on every Activity re-creation
        // (e.g. config change), but this class init{} runs exactly once per
        // ViewModel instance — the collector must live here, not in init().
        viewModelScope.launch {
            HardwareLock.busy.collect { busy -> update { copy(isHardwareBusy = busy) } }
        }
        // Restore the cached aircraft serial from a previous session so the
        // user does not have to re-probe before 4G if the drone is the same.
        val cachedSerial = prefs.getString("aircraft_serial", "").orEmpty()
        val language = AppLanguage.fromPref(prefs.getString("language", null))
        update { copy(language = language) }
        if (cachedSerial.isNotEmpty()) {
            update { copy(aircraftSerial = cachedSerial) }
        }
        restoreUpdateDiagnostics()
        restorePendingUpdate()
    }

    /** Claims the shared hardware lock for one operation. Returns false if another (including the keepalive service) is already running. */
    private fun beginHardwareOp(): Boolean = HardwareLock.tryBegin()

    /** Releases the shared hardware lock. Must run in a finally block covering every exit path. */
    private fun endHardwareOp() = HardwareLock.end()

    fun setLanguage(language: AppLanguage) {
        prefs.edit().putString("language", language.prefValue).apply()
        update { copy(language = language) }
        log(if (language == AppLanguage.TR) "Dil Türkçe olarak ayarlandı" else "Language set to English")
    }

    fun init() {
        val model = try { Build.DEVICE } catch (_: Exception) { "unknown" }
        val autoEnabled = prefs.getBoolean("auto_fcc", false)
        // Sync the keepalive toggle with the persistent flag so the UI is
        // correct after a process restart (e.g. low-memory kill + sticky restart).
        val keepaliveRunning = FccKeepaliveService.isRunningFlagSet(app)
        update { copy(controllerModel = model, status = "disconnected", autoFcc = autoEnabled, isKeepaliveRunning = keepaliveRunning) }

        if (autoEnabled) {
            log(if (_state.value.language == AppLanguage.TR) "Auto-FCC açık — bağlanıyor ve uygulanıyor..." else "Auto-FCC enabled — connecting and applying...")
            autoConnectAndApply()
        }

        checkForUpdates()
    }

    /** Refreshes persistent DownloadManager state when returning from Android Settings or installer. */
    fun onAppResumed() {
        restoreUpdateDiagnostics()
        restorePendingUpdate()
    }

    // --- Auto-FCC ---

    /**
     * Toggles auto-FCC on or off. When enabled, the app will automatically
     * connect to the controller and apply FCC mode every time it launches.
     * The setting is saved to SharedPreferences and persists across restarts.
     */
    fun toggleAutoFcc() {
        val newValue = !_state.value.autoFcc
        prefs.edit().putBoolean("auto_fcc", newValue).apply()
        update { copy(autoFcc = newValue) }
        log(
            if (_state.value.language == AppLanguage.TR) {
                if (newValue) "Auto-FCC açıldı — sonraki açılışta otomatik bağlanacak" else "Auto-FCC kapatıldı"
            } else {
                if (newValue) "Auto-FCC enabled — will auto-connect on next launch" else "Auto-FCC disabled"
            }
        )
    }

    /**
     * Connects to the controller and applies FCC mode automatically.
     * Waits for connection, then sends the FCC profile, starts the keepalive
     * service, and launches DJI Fly.
     */
    private fun autoConnectAndApply() {
        if (!beginHardwareOp()) {
            log(if (_state.value.language == AppLanguage.TR) "Auto-FCC atlandı — başka bir donanım işlemi çalışıyor" else "Auto-FCC skipped — another hardware operation is already running")
            return
        }
        val language = _state.value.language
        runOnIO {
            try {
                // Wait a moment for the UI to render
                delay(1000)

                // Try to connect — scans all known ports
                update { copy(status = "connecting", message = if (language == AppLanguage.TR) "Otomatik bağlanıyor..." else "Auto-connecting...") }
                if (!transport.connect()) {
                    log(if (language == AppLanguage.TR) "Auto-FCC: kumanda bulunamadı — drone açık mı?" else "Auto-FCC: controller not found — is the drone powered on?")
                    update { copy(status = "disconnected", message = if (language == AppLanguage.TR) "Kumanda bulunamadı. Bağlan'a bastığında tekrar deneyebilirsin." else "Controller not found. Auto-FCC will retry when you tap Connect.") }
                    return@runOnIO
                }

                log(if (language == AppLanguage.TR) "Auto-FCC: kumanda bağlandı" else "Auto-FCC: controller connected")
                val detectedPort = transport.getDetectedPort()
                if (detectedPort > 0) {
                    log(if (language == AppLanguage.TR) "DUML portu algılandı: $detectedPort" else "DUML port detected: $detectedPort")
                }
                val serial = transport.probeSerial(1500)
                if (serial.isNotEmpty()) {
                    prefs.edit().putString("aircraft_serial", serial).apply()
                }
                update {
                    copy(
                        status = "connected",
                        isConnected = true,
                        aircraftSerial = serial,
                        message = if (language == AppLanguage.TR) "Bağlandı. FCC otomatik uygulanıyor..." else "Connected. Auto-applying FCC..."
                    )
                }
                if (serial.isNotEmpty()) log(if (language == AppLanguage.TR) "Hava aracı seri no: $serial" else "Aircraft serial: $serial")

                // Apply FCC
                delay(500)
                update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = if (language == AppLanguage.TR) "FCC modu uygulanıyor..." else "Applying FCC mode...") }
                log(if (language == AppLanguage.TR) "Auto-FCC: FCC modu uygulanıyor..." else "Auto-FCC: applying FCC mode...")

                val profile = Profiles.load(app, "fcc.json")
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = profile.port
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            status = "fcc_enabled",
                            message = if (language == AppLanguage.TR) "FCC açıldı. Keepalive başlatılıyor..." else "FCC enabled. Starting keepalive...",
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log(if (language == AppLanguage.TR) "Auto-FCC: FCC modu açıldı" else "Auto-FCC: FCC mode enabled")

                    // Auto-start keepalive
                    delay(500)
                    update { copy(isKeepaliveRunning = true) }
                    FccKeepaliveService.start(app)
                    log(if (language == AppLanguage.TR) "Auto-FCC: Keepalive başladı (2 saniyede bir tekrar uygulanıyor)" else "Auto-FCC: keepalive started (re-applying every 2s)")

                    // Auto-launch DJI Fly
                    delay(500)
                    update { copy(message = if (language == AppLanguage.TR) "FCC aktif. DJI Fly açılıyor..." else "FCC active. Launching DJI Fly...") }
                    log(if (language == AppLanguage.TR) "Auto-FCC: DJI Fly açılıyor" else "Auto-FCC: launching DJI Fly")
                    launchDjiFly()
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = if (language == AppLanguage.TR) "Auto-FCC başarısız — manuel dene" else "Auto-FCC failed — try manually",
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log(if (language == AppLanguage.TR) "Auto-FCC: uygulama başarısız — manuel dene" else "Auto-FCC: apply failed — try manually")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "Auto-FCC hatası: ${e.message}" else "Auto-FCC error: ${e.message}")
                update { copy(status = "disconnected", message = if (language == AppLanguage.TR) "Auto-FCC hatası: ${e.message}" else "Auto-FCC error: ${e.message}", isBusy = false, busyProgress = 0f) }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- Connection ---

    /**
     * Connects to the DUML proxy, auto-detecting the correct port.
     * Probes for the aircraft serial number after connecting.
     */
    fun connect() {
        if (!beginHardwareOp()) {
            log(if (_state.value.language == AppLanguage.TR) "Donanım meşgul — mevcut işlemin bitmesini bekle." else "Hardware busy — please wait for the current operation to finish.")
            return
        }
        val language = _state.value.language
        update { copy(status = "connecting", message = if (language == AppLanguage.TR) "Kumandaya bağlanılıyor..." else "Connecting to controller...") }
        log(if (language == AppLanguage.TR) "Kumandaya bağlanılıyor..." else "Connecting to controller...")

        runOnIO {
            try {
                if (transport.connect()) {
                    log(if (language == AppLanguage.TR) "Kumanda bağlandı" else "Controller connected")
                    val detectedPort = transport.getDetectedPort()
                    if (detectedPort > 0) {
                        log(if (language == AppLanguage.TR) "DUML portu algılandı: $detectedPort" else "DUML port detected: $detectedPort")
                    }
                    val serial = transport.probeSerial(1500)
                    if (serial.isNotEmpty()) {
                        prefs.edit().putString("aircraft_serial", serial).apply()
                    }
                    update {
                        copy(
                            status = "connected",
                            message = if (serial.isNotEmpty()) {
                                if (language == AppLanguage.TR) "Bağlandı — $serial" else "Connected — $serial"
                            } else {
                                if (language == AppLanguage.TR) "Bağlandı. FCC uygulamaya hazır." else "Connected. Ready to apply FCC."
                            },
                            isConnected = true,
                            aircraftSerial = serial
                        )
                    }
                    if (serial.isNotEmpty()) log(if (language == AppLanguage.TR) "Hava aracı seri no: $serial" else "Aircraft serial: $serial")
                } else {
                    update {
                        copy(
                            status = "disconnected",
                            message = if (language == AppLanguage.TR) "Kumanda bulunamadı. Drone açık ve bağlı olmalı." else "Controller not found. Make sure the drone is powered on and linked.",
                            isConnected = false
                        )
                    }
                    log(if (language == AppLanguage.TR) "Bağlantı başarısız — drone açık mı?" else "Connection failed — is the drone powered on?")
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- FCC ---

    /**
     * Sends the 21-frame FCC unlock profile (2 rounds, 150ms between frames).
     * The profile already runs 2 rounds internally for reliability.
     */
    fun enableFcc() {
        if (!beginHardwareOp()) {
            log(if (_state.value.language == AppLanguage.TR) "Donanım meşgul — mevcut işlemin bitmesini bekle." else "Hardware busy — please wait for the current operation to finish.")
            return
        }
        val language = _state.value.language
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = if (language == AppLanguage.TR) "FCC modu açılıyor..." else "Enabling FCC mode...") }
        log(if (language == AppLanguage.TR) "FCC modu açılıyor..." else "Enabling FCC mode...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "fcc.json")
                log(if (language == AppLanguage.TR) "FCC profili yüklendi: ${profile.frames.size} frame, ${profile.rounds} tur" else "Loaded FCC profile: ${profile.frames.size} frames, ${profile.rounds} rounds")

                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = profile.port
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            status = "fcc_enabled",
                            message = if (language == AppLanguage.TR) "FCC modu açıldı" else "FCC mode enabled",
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log(if (language == AppLanguage.TR) "FCC modu açıldı — ${profile.frames.size} frame gönderildi" else "FCC mode enabled — ${profile.frames.size} frames sent")
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = if (language == AppLanguage.TR) "FCC uygulanamadı — RC bağlantısı yok. Drone açık ve bağlı olmalı." else "FCC apply failed — RC link unreachable. Make sure the drone is on and linked.",
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log(if (language == AppLanguage.TR) "FCC uygulama başarısız — yazma işlemleri başarısız" else "FCC apply failed — writes failed")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "FCC uygulama hatası: ${e.message}" else "FCC apply error: ${e.message}")
                update { copy(status = "connected", message = if (language == AppLanguage.TR) "FCC uygulama hatası: ${e.message}" else "FCC apply error: ${e.message}", isBusy = false, busyProgress = 0f) }
            } finally {
                endHardwareOp()
            }
        }
    }

    /** Sends the CE restore command: a single frame that resets to factory region. */
    fun disableFcc() {
        if (!beginHardwareOp()) {
            log(if (_state.value.language == AppLanguage.TR) "Donanım meşgul — mevcut işlemin bitmesini bekle." else "Hardware busy — please wait for the current operation to finish.")
            return
        }
        val language = _state.value.language
        // Stop keepalive first — otherwise it re-applies FCC 2 seconds after
        // we restore CE, undoing the user's intent.
        if (_state.value.isKeepaliveRunning) {
            stopKeepalive()
        }
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = if (language == AppLanguage.TR) "CE modu geri yükleniyor..." else "Restoring CE mode...") }
        log(if (language == AppLanguage.TR) "CE modu geri yükleniyor..." else "Restoring CE mode...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "ce_restore.json")
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    readWindowMs = profile.readWindowMs
                )

                if (success) {
                    update { copy(status = "connected", message = if (language == AppLanguage.TR) "CE modu geri yüklendi" else "CE mode restored", isFccEnabled = false, isBusy = false) }
                    log(if (language == AppLanguage.TR) "CE modu geri yüklendi" else "CE mode restored")
                } else {
                    update { copy(status = "connected", message = if (language == AppLanguage.TR) "CE geri yüklenemedi — RC bağlantısı yok" else "CE restore failed — RC link unreachable", isBusy = false) }
                    log(if (language == AppLanguage.TR) "CE geri yükleme başarısız" else "CE restore failed")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "CE geri yükleme hatası: ${e.message}" else "CE restore error: ${e.message}")
                update { copy(status = "connected", message = if (language == AppLanguage.TR) "CE geri yükleme hatası: ${e.message}" else "CE restore error: ${e.message}", isBusy = false) }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- FCC Keepalive ---

    /**
     * Starts a foreground service that re-applies the FCC profile every 2 seconds.
     * This prevents DJI Fly from resetting the radio back to CE mode when it
     * connects to the drone. The service runs independently of the Activity
     * lifecycle so it keeps working when the user switches to DJI Fly.
     */
    fun startKeepalive() {
        if (_state.value.isKeepaliveRunning) {
            log(if (_state.value.language == AppLanguage.TR) "Keepalive zaten çalışıyor" else "Keepalive already running")
            return
        }
        update { copy(isKeepaliveRunning = true) }
        FccKeepaliveService.start(app)
        log(if (_state.value.language == AppLanguage.TR) "FCC Keepalive başlatıldı — CE resetini önlemek için 2 saniyede bir uygulanıyor" else "Started FCC keepalive — re-applying every 2s to prevent CE reset")
    }

    /** Stops the keepalive foreground service. */
    fun stopKeepalive() {
        FccKeepaliveService.stop(app)
        update { copy(isKeepaliveRunning = false) }
        log(if (_state.value.language == AppLanguage.TR) "FCC Keepalive durduruldu" else "FCC keepalive stopped")
    }

    // --- Launch DJI Fly ---

    /**
     * Launches the DJI Fly app (dji.go.v5) so the user can continue flying
     * with FCC mode active. The keepalive service keeps re-applying FCC in the
     * background while DJI Fly runs.
     */
    fun launchDjiFly() {
        val pm = app.packageManager
        // Try the standard launch intent first
        var intent = pm.getLaunchIntentForPackage("dji.go.v5")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log(if (_state.value.language == AppLanguage.TR) "DJI Fly açıldı" else "Launched DJI Fly")
                return
            } catch (_: Exception) {}
        }

        // Fallback: try explicit component — DJI Fly's main activity
        for (activityName in listOf(
            "dji.pilot2.lite.LauncherActivity",
            "dji.go.v5.MainActivity",
            "dji.pilot2.lite.LiteLauncherActivity",
            "dji.go.v5.SplashActivity"
        )) {
            val explicitIntent = android.content.Intent().apply {
                component = android.content.ComponentName("dji.go.v5", activityName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                app.startActivity(explicitIntent)
                log(if (_state.value.language == AppLanguage.TR) "DJI Fly açıldı" else "Launched DJI Fly")
                return
            } catch (_: Exception) {}
        }

        // Fallback 2: try dji.go.v4
        intent = pm.getLaunchIntentForPackage("dji.go.v4")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log(if (_state.value.language == AppLanguage.TR) "DJI Go 4 açıldı" else "Launched DJI Go 4")
                return
            } catch (_: Exception) {}
        }

        log(if (_state.value.language == AppLanguage.TR) "DJI Fly kurulu değil veya bu kumandada açılamıyor" else "DJI Fly not installed or cannot launch on this controller")
    }

    // --- 4G ---

    /**
     * Sends the 128-frame 4G activation profile.
     * The aircraft serial is embedded in each frame's payload at runtime.
     * 4G frames are sent via Unix domain socket (/duss/mb/0x205), not TCP.
     *
     * The socket does not respond, so this can only confirm the frames were
     * written — never confirm the aircraft actually activated 4G. There is
     * no "off" action: no send-only command exists to reliably deactivate it.
     *
     * Guards (added to stop the most common failure modes early):
     * 1. Aircraft serial must be non-empty AND at least 6 chars. A 6-char
     *    W[AM]xxx model code (WA341, WM630) is the minimum the 4G payload
     *    format needs — a shorter string would produce a malformed payload.
     * 2. Model code must be in the 4G-capable set. The Mini series rejects
     *    4G at the firmware level; we tell the user up front rather than
     *    fail after writing 128 frames.
     * 3. The 4G dongle must be present (the abstract socket must be
     *    connectable). If `/duss/mb/0x205` does not exist, the cellular
     *    module is not attached and no frame can succeed.
     */
    fun send4gActivationFrames() {
        if (!beginHardwareOp()) {
            log(if (_state.value.language == AppLanguage.TR) "Donanım meşgul — mevcut işlemin bitmesini bekle." else "Hardware busy — please wait for the current operation to finish.")
            return
        }
        val language = _state.value.language
        update { copy(is4gBusy = true, busyProgress = 0f, fourGMessage = "") }
        log(if (language == AppLanguage.TR) "4G aktivasyon frameleri gönderiliyor..." else "Sending 4G activation frames...")

        runOnIO {
            try {
                val serial = getOrProbeSerial()

                // Guard 1: serial present and long enough for the 4G payload format.
                if (serial.length < 6) {
                    update {
                        copy(is4gBusy = false, fourGMessage = if (language == AppLanguage.TR) "4G için hava aracı bağlı olmalı. Drone'u aç, link kur ve önce Bağlan'a bas." else "4G needs the aircraft connected. Power on the drone, link it, and tap Connect first.")
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon başarısız — seri çok kısa ('$serial'); en az W[AM]xxx model kodu gerekli" else "4G activation failed — aircraft serial too short ('$serial'); need at least a W[AM]xxx model code")
                    return@runOnIO
                }

                // Guard 2: model must be in the 4G-capable set.
                // The model code is the W[AM]xxx prefix (first 5-6 chars).
                val modelCode = serial.take(5).lowercase()
                if (modelCode !in MODELS_WITH_4G) {
                    update {
                        copy(is4gBusy = false, fourGMessage = if (language == AppLanguage.TR) "$modelCode modelinde 4G desteklenmiyor. DJI Cellular Dongle 2 gerekir; bu destek Mavic 4 Pro / Matrice / Inspire 3 tarafındadır." else "4G is not supported on $modelCode. It requires a DJI Cellular Dongle 2, which is only available on Mavic 4 Pro / Matrice / Inspire 3.")
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon iptal — $modelCode 4G destekli listede değil $MODELS_WITH_4G" else "4G activation aborted — model $modelCode is not in the 4G-capable set $MODELS_WITH_4G")
                    return@runOnIO
                }

                // Guard 3: dongle pre-check — fast-fail if the socket does not exist.
                if (!transport.is4gDonglePresent()) {
                    update {
                        copy(is4gBusy = false, fourGMessage = if (language == AppLanguage.TR) "4G dongle algılanmadı. DJI Cellular Dongle 2'yi hava aracına bağlayıp tekrar dene." else "4G dongle not detected. Connect a DJI Cellular Dongle 2 to the aircraft and try again.")
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon iptal — /duss/mb/0x205 soketine bağlanılamıyor (dongle yok?)" else "4G activation aborted — 4G socket /duss/mb/0x205 not connectable (no dongle?)")
                    return@runOnIO
                }

                val profile = Profiles.load4g(app, serial)
                log(if (language == AppLanguage.TR) "4G profili yüklendi: ${profile.frames.size} frame (seri: $serial, model: $modelCode)" else "Loaded 4G profile: ${profile.frames.size} frames (serial: $serial, model: $modelCode)")

                // 4G uses Unix domain socket, not TCP
                val success = transport.sendFramesUnix(
                    frames = profile.frames,
                    interFrameDelayMs = profile.interFrameDelay
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            is4gBusy = false,
                            busyProgress = 0f,
                            fourGMessage = if (language == AppLanguage.TR) "Tüm aktivasyon frameleri yazıldı — 4G durumunu hava aracında kontrol et." else "All activation frames written successfully — check 4G status on the aircraft."
                        )
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon: ${profile.frames.size} frame Unix soketine yazıldı" else "4G activation: all ${profile.frames.size} frames written successfully via Unix socket")
                } else {
                    update { copy(is4gBusy = false, fourGMessage = if (language == AppLanguage.TR) "4G uygulanamadı — 4G dongle bağlı mı?" else "4G apply failed — is the 4G dongle connected?") }
                    log(if (language == AppLanguage.TR) "4G aktivasyon başarısız — Unix soketinde en az bir frame yazılamadı" else "4G activation failed — at least one frame write failed on the Unix socket")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "4G aktivasyon hatası: ${e.message}" else "4G activation error: ${e.message}")
                update { copy(is4gBusy = false, fourGMessage = if (language == AppLanguage.TR) "4G hatası: ${e.message}" else "4G error: ${e.message}") }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- LED ---

    /**
     * Turns the aircraft arm LEDs on or off.
     * Uses port 40007 (different from the standard 40009 DUML port).
     * Requires DJI Fly running with the aircraft connected.
     *
     * Sends the LED command in 2 bursts of 5 writes each (10 total), with
     * 100ms between writes — matching the reference app's pattern for
     * reliability.
     *
     * **Does NOT hold HardwareLock.** The LED command targets port 40007
     * (camera/LED subsystem) while the FCC keepalive targets port 40009
     * (radio subsystem). They use different ports and different subsystems,
     * so they can run concurrently without conflict. Holding the lock during
     * the LED command would block the keepalive for ~1.5s, creating a gap
     * where DJI Fly could reset the radio to CE. By not holding the lock,
     * the keepalive continues re-applying FCC throughout the LED command.
     * Only the [isLedBusy] UI flag prevents double-taps.
     *
     * @param on true for LED ON, false for LED OFF
     */
    fun setLed(on: Boolean) {
        if (_state.value.isLedBusy) {
            log(if (_state.value.language == AppLanguage.TR) "LED meşgul — lütfen bekle." else "LED busy — please wait.")
            return
        }
        val language = _state.value.language
        update { copy(isLedBusy = true, ledStatus = if (language == AppLanguage.TR) { if (on) "LED'ler açılıyor..." else "LED'ler kapatılıyor..." } else { if (on) "Turning LEDs on..." else "Turning LEDs off..." }) }
        log(if (language == AppLanguage.TR) { if (on) "LED'ler açılıyor..." else "LED'ler kapatılıyor..." } else { if (on) "Turning LEDs on..." else "Turning LEDs off..." })

        runOnIO {
            try {
                val fileName = if (on) "led_on.json" else "led_off.json"
                val profile = Profiles.load(app, fileName)
                log(if (language == AppLanguage.TR) "LED profili yüklendi: ${profile.frames.size} frame (port ${profile.port})" else "Loaded LED profile: ${profile.frames.size} frames (port ${profile.port})")

                // Separate transport instance — the LED command on port 40007
                // must not share state with the FCC transport on port 40009.
                val ledTransport = DumlTransport()

                var anySuccess = false

                // 2 connection bursts × 5 writes each = 10 total sends, with
                // 100ms between writes and 100ms between bursts. Matches the
                // reference app's reliability pattern.
                for (attempt in 0 until 2) {
                    if (attempt > 0) delay(100)

                    val success = ledTransport.sendFrames(
                        frames = profile.frames,
                        rounds = 5,
                        interFrameDelayMs = 100,
                        interRoundDelayMs = 0,
                        readWindowMs = 100,
                        port = profile.port
                    )

                    if (success) anySuccess = true
                }

                if (anySuccess) {
                    update { copy(isLedBusy = false, ledStatus = if (on) "ON" else "OFF") }
                    log(if (language == AppLanguage.TR) { if (on) "LED'ler açıldı" else "LED'ler kapatıldı" } else { if (on) "LEDs turned on" else "LEDs turned off" })
                } else {
                    update { copy(isLedBusy = false, ledStatus = if (language == AppLanguage.TR) "Başarısız — DJI Fly çalışıyor mu?" else "Failed — is DJI Fly running?") }
                    log(if (language == AppLanguage.TR) "LED komutu başarısız — DJI Fly açık ve hava aracı bağlı olmalı" else "LED command failed — make sure DJI Fly is running with aircraft connected")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "LED hatası: ${e.message}" else "LED error: ${e.message}")
                update { copy(isLedBusy = false, ledStatus = if (language == AppLanguage.TR) "Hata: ${e.message}" else "Error: ${e.message}") }
            }
        }
    }

    // --- Device Info ---

    /**
     * Queries the controller for hardware version, bootloader version, and
     * firmware version via the GENERAL VersionInquiry command
     * (cmd_set=0, cmd_id=1). Uses sendAndReceive to capture the response.
     */
    fun queryDeviceInfo() {
        if (!isControllerReachable()) return
        if (!beginHardwareOp()) {
            log(if (_state.value.language == AppLanguage.TR) "Donanım meşgul — mevcut işlemin bitmesini bekle." else "Hardware busy — please wait for the current operation to finish.")
            return
        }

        val language = _state.value.language
        update { copy(isQueryingInfo = true) }
        log(if (language == AppLanguage.TR) "Cihaz bilgisi sorgulanıyor..." else "Querying device info...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "device_info.json")
                if (profile.frames.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = if (language == AppLanguage.TR) "device_info.json boş" else "device_info.json is empty") }
                    log(if (language == AppLanguage.TR) "Cihaz bilgisi: profilde frame yok" else "Device info: profile has no frames")
                    return@runOnIO
                }
                val frame = profile.frames.first()

                val response = transport.sendAndReceive(frame, profile.readWindowMs)

                if (response == null || response.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = if (language == AppLanguage.TR) "Kumandadan yanıt yok" else "No response from controller") }
                    log(if (language == AppLanguage.TR) "Cihaz bilgisi: yanıt yok" else "Device info: no response")
                    return@runOnIO
                }

                val info = formatVersionResponse(response)
                update { copy(isQueryingInfo = false, deviceInfo = info) }
                log(if (language == AppLanguage.TR) "Cihaz bilgisi alındı: ${response.size} bayt" else "Device info received: ${response.size} bytes")
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "Cihaz bilgisi hatası: ${e.message}" else "Device info error: ${e.message}")
                update { copy(isQueryingInfo = false, deviceInfo = if (language == AppLanguage.TR) "Hata: ${e.message}" else "Error: ${e.message}") }
            } finally {
                endHardwareOp()
            }
        }
    }

    fun probeSerial() {
        if (!beginHardwareOp()) {
            log(if (_state.value.language == AppLanguage.TR) "Donanım meşgul — mevcut işlemin bitmesini bekle." else "Hardware busy — please wait for the current operation to finish.")
            return
        }
        val language = _state.value.language
        log(if (language == AppLanguage.TR) "Hava aracı seri numarası aranıyor..." else "Probing for aircraft serial...")
        runOnIO {
            try {
                val serial = transport.probeSerial(2000)
                if (serial.isNotEmpty()) {
                    update { copy(aircraftSerial = serial) }
                    prefs.edit().putString("aircraft_serial", serial).apply()
                    log(if (language == AppLanguage.TR) "Hava aracı seri no: $serial (önbelleğe alındı)" else "Aircraft serial: $serial (cached)")
                } else {
                    log(if (language == AppLanguage.TR) "Seri numarası algılanmadı — hava aracı açık mı?" else "No serial detected — is the aircraft powered on?")
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- Updates ---

    fun checkForUpdates(force: Boolean = false) {
        // Rate-limit: don't hit GitHub API more than once per hour.
        // Unauthenticated limit is 60 requests/hour per IP.
        // The timestamp is saved ONLY on success — a failed check does NOT
        // consume the rate-limit window, so the user can retry immediately.
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60 * 60 * 1000 && _state.value.updateChecked && _state.value.updateInfo != null) {
            return
        }
        update { copy(isCheckingUpdate = true, profileUpdateMessage = "") }
        val language = _state.value.language
        log(if (language == AppLanguage.TR) "DronePeak-FCC güncellemesi kontrol ediliyor..." else "Checking DronePeak-FCC updates...")

        runOnIO {
            val info = UpdateChecker.fetchLatest()
            if (info == null) {
                // Don't save lastCheck on failure — let the user retry immediately.
                update { copy(isCheckingUpdate = false, updateChecked = true) }
                log(if (language == AppLanguage.TR) "Güncelleme kontrolü başarısız — internet yok veya GitHub erişilemiyor. Tekrar deneyebilirsin." else "Update check failed — no internet or GitHub unreachable. Tap Retry to try again.")
                return@runOnIO
            }

            // Save the timestamp only on success.
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()

            val isNewer = info.isNewerThan(APP_VERSION)
            update {
                copy(
                    updateInfo = info,
                    isCheckingUpdate = false,
                    updateChecked = true,
                    updateAvailable = isNewer
                )
            }
            if (isNewer) {
                log(if (language == AppLanguage.TR) "DronePeak-FCC güncellemesi var: v${info.version}" else "DronePeak-FCC update available: v${info.version}")
            } else {
                log(if (language == AppLanguage.TR) "DronePeak-FCC güncel (v$APP_VERSION)" else "DronePeak-FCC is up to date (v$APP_VERSION)")
            }
        }
    }

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: run {
            log(if (_state.value.language == AppLanguage.TR) "Önce DronePeak-FCC güncelleme kontrolü yap." else "Check DronePeak-FCC updates first.")
            return
        }
        if (_state.value.updateStage == UpdateStage.DOWNLOADING) return
        if (info.downloadUrl.isBlank()) {
            update {
                copy(
                    profileUpdateMessage = if (_state.value.language == AppLanguage.TR) {
                        "Bu sürüm için indirilebilir APK bulunamadı."
                    } else {
                        "No downloadable APK was found for this version."
                    }
                )
            }
            log(if (_state.value.language == AppLanguage.TR) "Güncelleme APK'sı bulunamadı." else "Update APK missing.")
            return
        }
        val language = _state.value.language
        clearPendingUpdate(removeDownload = true)
        UpdateDiagnostics.clearResult(app)
        val download = UpdateChecker.startApkDownload(app, info)
        if (download == null) {
            UpdateDiagnostics.setResult(app, updateMessage("download_failed", language), true)
            UpdateDiagnostics.record(app, "DOWNLOAD_START_FAILED version=${info.version}")
            update {
                copy(
                    isDownloadingUpdate = false,
                    isUpdateDownloaded = false,
                    updateStage = UpdateStage.FAILED,
                    profileUpdateMessage = updateMessage("download_failed", language)
                )
            }
            log(if (language == AppLanguage.TR) "Güncelleme indirme başlatılamadı" else "Could not start update download")
            return
        }
        persistPendingUpdate(download.first, download.second, info)
        UpdateDiagnostics.record(app, "DOWNLOAD_STARTED id=${download.first} version=${info.version} file=${download.second.name}")
        update {
            copy(
                isDownloadingUpdate = true,
                updateDownloadProgress = 0f,
                isUpdateDownloaded = false,
                updateStage = UpdateStage.DOWNLOADING,
                updateDiagnosticSummary = "",
                updateDiagnosticDetails = "",
                updateDiagnosticFailure = false,
                profileUpdateMessage = if (language == AppLanguage.TR) "Güncelleme indiriliyor..." else "Downloading update..."
            )
        }
        log(if (language == AppLanguage.TR) "Sistem güncelleme indirmesi başlatıldı: ${download.first}" else "System update download started: ${download.first}")
        monitorPendingDownload()
    }

    fun downloadProfileUpdate() {
        val info = _state.value.updateInfo ?: run {
            log(if (_state.value.language == AppLanguage.TR) "Önce güncelleme kontrolü yap." else "Check updates first.")
            return
        }
        if (_state.value.isDownloadingUpdate) return
        val language = _state.value.language
        update {
            copy(
                isDownloadingUpdate = true,
                updateDownloadProgress = 0f,
                profileUpdateMessage = if (language == AppLanguage.TR) "Profil dosyaları indiriliyor..." else "Downloading profile files..."
            )
        }
        log(if (language == AppLanguage.TR) "Profil dosyaları indiriliyor..." else "Downloading profile files...")

        runOnIO {
            val result = UpdateChecker.downloadProfiles(app, info) { progress ->
                update { copy(updateDownloadProgress = progress) }
            }
            update {
                copy(
                    isDownloadingUpdate = false,
                    updateDownloadProgress = if (result.success) 1f else 0f,
                    profileUpdateMessage = if (result.success) {
                        if (language == AppLanguage.TR) {
                            "Profil dosyaları güncellendi (${result.updatedCount} dosya)."
                        } else {
                            "Profile files updated (${result.updatedCount} files)."
                        }
                    } else {
                        if (language == AppLanguage.TR) {
                            "Profil güncellemesi başarısız: ${result.message}. Bundled profiller kullanılmaya devam edecek."
                        } else {
                            "Profile update failed: ${result.message}. Bundled profiles remain active."
                        }
                    }
                )
            }
            log(
                if (result.success) {
                    if (language == AppLanguage.TR) "Profil dosyaları güncellendi: ${result.updatedCount} dosya" else "Profile files updated: ${result.updatedCount} files"
                } else {
                    if (language == AppLanguage.TR) "Profil güncellemesi başarısız: ${result.message}" else "Profile update failed: ${result.message}"
                }
            )
        }
    }

    /** Re-downloads the update after a failed install. Resets the downloaded state first. */
    fun reDownloadUpdate() {
        if (_state.value.updateStage == UpdateStage.DOWNLOADING) return
        clearPendingUpdate(removeDownload = true)
        update { copy(isUpdateDownloaded = false, updateStage = UpdateStage.NONE) }
        downloadUpdate()
    }

    fun cancelUpdateDownload() {
        if (_state.value.updateStage != UpdateStage.DOWNLOADING) return
        val language = _state.value.language
        clearPendingUpdate(removeDownload = true)
        UpdateDiagnostics.setResult(app, updateMessage("download_cancelled", language), false)
        UpdateDiagnostics.record(app, "DOWNLOAD_CANCELLED")
        update {
            copy(
                isDownloadingUpdate = false,
                updateDownloadProgress = 0f,
                isUpdateDownloaded = false,
                updateStage = UpdateStage.NONE,
                profileUpdateMessage = updateMessage("download_cancelled", language)
            )
        }
        log(if (language == AppLanguage.TR) "Güncelleme indirmesi iptal edildi" else "Update download cancelled")
    }

    fun installUpdate() {
        val language = _state.value.language
        if (_state.value.updateStage == UpdateStage.VERIFYING || _state.value.updateStage == UpdateStage.PREPARING_INSTALL) return
        val apk = pendingApkFile() ?: run {
            update {
                copy(
                    isUpdateDownloaded = false,
                    updateStage = UpdateStage.FAILED,
                    profileUpdateMessage = updateMessage("apk_missing", language)
                )
            }
            log(if (language == AppLanguage.TR) "Kurulacak güncelleme dosyası bulunamadı" else "No downloaded update file found")
            return
        }
        update {
            copy(
                updateStage = UpdateStage.VERIFYING,
                profileUpdateMessage = TextCatalog.ui(language).preparingInstallation
            )
        }
        UpdateDiagnostics.record(app, "INSTALL_REQUESTED version=${prefs.getString(updateVersionKey, "")}")
        runOnIO {
            val verificationError = runCatching { verifyDownloadedApk(apk) }.getOrElse {
                UpdateDiagnostics.record(app, "VERIFY_EXCEPTION ${it.javaClass.simpleName}: ${it.message}")
                "verification_failed"
            }
            if (verificationError != null) {
                UpdateDiagnostics.setResult(app, updateMessage(verificationError, language), true)
                update {
                    copy(
                        isUpdateDownloaded = false,
                        updateStage = UpdateStage.FAILED,
                        profileUpdateMessage = updateMessage(verificationError, language)
                    )
                }
                log("Update APK verification failed: $verificationError")
                return@runOnIO
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
                update {
                    copy(
                        updateStage = UpdateStage.NEEDS_INSTALL_PERMISSION,
                        profileUpdateMessage = updateMessage("install_permission", language)
                    )
                }
                UpdateDiagnostics.setResult(app, updateMessage("install_permission", language), false)
                openInstallPermissionSettings(language)
                return@runOnIO
            }
            update {
                copy(
                    updateStage = UpdateStage.PREPARING_INSTALL,
                    profileUpdateMessage = TextCatalog.ui(language).preparingInstallation
                )
            }
            val version = prefs.getString(updateVersionKey, "").orEmpty()
            when (UpdateInstallCoordinator(app).start(apk, version)) {
                InstallStartResult.STARTED -> update {
                    copy(
                        updateStage = UpdateStage.WAITING_FOR_ANDROID,
                        profileUpdateMessage = TextCatalog.ui(language).waitingForAndroidApproval
                    )
                }
                InstallStartResult.FAILED -> update {
                    copy(
                        updateStage = UpdateStage.FAILED,
                        profileUpdateMessage = updateMessage("installer_unavailable", language)
                    )
                }
            }
        }
    }

    private fun openInstallPermissionSettings(language: AppLanguage) {
        try {
            app.startActivity(android.content.Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            UpdateDiagnostics.record(app, "INSTALL_PERMISSION_SETTINGS_OPENED")
            log(if (language == AppLanguage.TR) "Android kurulum izni ayarları açıldı" else "Opened Android install permission settings")
        } catch (e: Exception) {
            update {
                copy(
                    updateStage = UpdateStage.FAILED,
                    profileUpdateMessage = updateMessage("installer_unavailable", language)
                )
            }
            UpdateDiagnostics.setResult(app, updateMessage("installer_unavailable", language), true)
            UpdateDiagnostics.record(app, "INSTALL_PERMISSION_SETTINGS_ERROR ${e.javaClass.simpleName}: ${e.message}")
            log("Could not open install permission settings: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun restorePendingUpdate() {
        val downloadId = prefs.getLong(updateDownloadIdKey, -1L)
        if (downloadId <= 0L) return
        runOnIO {
            applyDownloadSnapshot(downloadId, UpdateChecker.queryApkDownload(app, downloadId, pendingApkFile()))
        }
    }

    private fun restoreUpdateDiagnostics() {
        val report = UpdateDiagnostics.report(app) ?: return
        val stage = when (report.outcome) {
            UpdateDiagnosticOutcome.FAILURE -> UpdateStage.FAILED
            UpdateDiagnosticOutcome.WAITING_FOR_ANDROID -> UpdateStage.WAITING_FOR_ANDROID
            UpdateDiagnosticOutcome.SUCCESS -> UpdateStage.COMPLETED
            UpdateDiagnosticOutcome.INFO -> _state.value.updateStage
        }
        update {
            copy(
                updateStage = stage,
                profileUpdateMessage = report.summary,
                updateDiagnosticSummary = report.summary,
                updateDiagnosticDetails = report.details.lineSequence().toList().takeLast(8).joinToString("\n"),
                updateDiagnosticFailure = report.isFailure
            )
        }
    }

    private fun monitorPendingDownload() {
        val downloadId = prefs.getLong(updateDownloadIdKey, -1L)
        if (downloadId <= 0L) return
        runOnIO {
            while (prefs.getLong(updateDownloadIdKey, -1L) == downloadId) {
                val snapshot = UpdateChecker.queryApkDownload(app, downloadId, pendingApkFile())
                applyDownloadSnapshot(downloadId, snapshot)
                if (snapshot.state != DownloadState.DOWNLOADING) return@runOnIO
                delay(750)
            }
        }
    }

    private fun applyDownloadSnapshot(downloadId: Long, snapshot: DownloadSnapshot) {
        if (prefs.getLong(updateDownloadIdKey, -1L) != downloadId) return
        if (snapshot.state == DownloadState.READY && _state.value.updateStage in setOf(
                UpdateStage.WAITING_FOR_ANDROID,
                UpdateStage.COMPLETED,
                UpdateStage.FAILED
            )
        ) return
        val language = _state.value.language
        when (snapshot.state) {
            DownloadState.DOWNLOADING -> update {
                copy(
                    isDownloadingUpdate = true,
                    isUpdateDownloaded = false,
                    updateStage = UpdateStage.DOWNLOADING,
                    updateDownloadProgress = snapshot.progress,
                    profileUpdateMessage = if (language == AppLanguage.TR) "Güncelleme indiriliyor..." else "Downloading update..."
                )
            }
            DownloadState.READY -> {
                val apk = snapshot.file
                val verificationError = if (apk == null) "apk_missing" else verifyDownloadedApk(apk)
                if (verificationError == null) {
                    UpdateDiagnostics.record(app, "DOWNLOAD_READY id=$downloadId file=${apk?.name}")
                    update {
                        copy(
                            isDownloadingUpdate = false,
                            isUpdateDownloaded = true,
                            updateStage = UpdateStage.READY,
                            updateDownloadProgress = 1f,
                            profileUpdateMessage = updateMessage("ready", language)
                        )
                    }
                    log(if (language == AppLanguage.TR) "Güncelleme indirildi ve doğrulandı: ${apk!!.name}" else "Update downloaded and verified: ${apk!!.name}")
                } else {
                    UpdateDiagnostics.setResult(app, updateMessage(verificationError, language), true)
                    update {
                        copy(
                            isDownloadingUpdate = false,
                            isUpdateDownloaded = false,
                            updateStage = UpdateStage.FAILED,
                            updateDownloadProgress = 0f,
                            profileUpdateMessage = updateMessage(verificationError, language)
                        )
                    }
                    log("Update download verification failed: $verificationError")
                }
            }
            DownloadState.FAILED -> {
                UpdateDiagnostics.setResult(app, updateMessage("download_failed", language), true)
                update {
                    copy(
                        isDownloadingUpdate = false,
                        isUpdateDownloaded = false,
                        updateStage = UpdateStage.FAILED,
                        updateDownloadProgress = 0f,
                        profileUpdateMessage = updateMessage("download_failed", language)
                    )
                }
                log("Update download failed: ${snapshot.message}")
            }
            DownloadState.NONE -> Unit
        }
    }

    private fun persistPendingUpdate(downloadId: Long, apk: File, info: UpdateInfo) {
        prefs.edit()
            .putLong(updateDownloadIdKey, downloadId)
            .putString(updateApkPathKey, apk.absolutePath)
            .putString(updateVersionKey, info.version)
            .putString(updateSha256Key, info.sha256.orEmpty())
            .apply()
    }

    private fun clearPendingUpdate(removeDownload: Boolean) {
        val downloadId = prefs.getLong(updateDownloadIdKey, -1L)
        if (removeDownload) {
            UpdateChecker.cancelApkDownload(app, downloadId)
            pendingApkFile()?.takeIf { isUpdateFile(it) }?.let { file ->
                runCatching { file.delete() }
            }
        }
        prefs.edit()
            .remove(updateDownloadIdKey)
            .remove(updateApkPathKey)
            .remove(updateVersionKey)
            .remove(updateSha256Key)
            .apply()
    }

    private fun pendingApkFile(): File? {
        val path = prefs.getString(updateApkPathKey, null) ?: return null
        return File(path).takeIf { isUpdateFile(it) }
    }

    private fun isUpdateFile(file: File): Boolean = runCatching {
        val updatesDir = File(app.getExternalFilesDir(null), "updates").canonicalFile
        val candidate = file.canonicalFile
        candidate.parentFile == updatesDir && candidate.extension.equals("apk", ignoreCase = true)
    }.getOrDefault(false)

    /** Verifies the exact artifact before handing it to Android's package installer. */
    @Suppress("DEPRECATION")
    private fun verifyDownloadedApk(apk: File): String? {
        if (!apk.isFile || apk.length() == 0L) return "apk_missing"
        val expectedHash = prefs.getString(updateSha256Key, "").orEmpty()
        if (expectedHash.isNotEmpty() && !UpdateChecker.sha256(apk).equals(expectedHash, ignoreCase = true)) {
            return "verification_failed"
        }
        val archive = app.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "verification_failed"
        if (archive.packageName != app.packageName) return "verification_failed"
        if (archive.versionName != prefs.getString(updateVersionKey, "")) return "verification_failed"
        val installed = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= installed.longVersionCode) return "verification_failed"
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty() || archiveSigners.intersect(installedSigners).isEmpty()) {
            return "verification_failed"
        }
        return null
    }

    private fun signerDigests(packageInfo: android.content.pm.PackageInfo): Set<String> =
        packageInfo.signingInfo?.apkContentsSigners?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }?.toSet().orEmpty()

    private fun updateMessage(key: String, language: AppLanguage): String {
        val ui = TextCatalog.ui(language)
        return when (key) {
            "ready" -> ui.updateReadyToInstall
            "install_permission" -> ui.installPermissionRequired
            "installer_open" -> ui.installerOpen
            "download_cancelled" -> ui.updateCancelled
            "download_failed" -> ui.updateDownloadFailed
            "verification_failed", "apk_missing" -> ui.updateVerificationFailed
            "installer_unavailable" -> ui.installerUnavailable
            else -> ui.updateDownloadFailed
        }
    }

    // --- Helpers ---

    /** Returns true if the controller is connected, logs a hint if not. */
    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected) return true
        log(if (_state.value.language == AppLanguage.TR) "Önce kumandaya bağlan" else "Connect to the controller first")
        return false
    }

    /** Returns the cached serial, or probes the controller if not yet known. Caches the result in SharedPreferences. */
    private fun getOrProbeSerial(): String {
        var serial = _state.value.aircraftSerial
        if (serial.isEmpty()) {
            // Fall back to a serial persisted in a previous session.
            serial = prefs.getString("aircraft_serial", "").orEmpty()
            if (serial.isNotEmpty()) {
                update { copy(aircraftSerial = serial) }
            }
        }
        if (serial.isEmpty()) {
            log(if (_state.value.language == AppLanguage.TR) "Hava aracı seri numarası aranıyor..." else "Probing for aircraft serial...")
            serial = transport.probeSerial(2000)
            if (serial.isNotEmpty()) {
                update { copy(aircraftSerial = serial) }
                prefs.edit().putString("aircraft_serial", serial).apply()
                log(if (_state.value.language == AppLanguage.TR) "Hava aracı seri no: $serial (önbelleğe alındı)" else "Aircraft serial: $serial (cached)")
            }
        }
        return serial
    }

    /**
     * Parses a DUML VersionInquiry response payload into a human-readable string.
     *
     * Response layout (from dji-firmware-tools DJIPayload_General_VersionInquiryRe):
     *   byte  0-1    unknown
     *   bytes 2-17   hardware version (16-char ASCII string)
     *   bytes 18-21  bootloader version (uint32 LE)
     *   bytes 22-25  firmware version (uint32 LE)
     */
    private fun formatVersionResponse(payload: ByteArray): String {
        val lines = mutableListOf<String>()
        val ui = text

        if (payload.size >= 18) {
            val hwVersion = String(payload, 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
            lines.add("${ui.hardware}: $hwVersion")
        }

        if (payload.size >= 22) {
            val ldrVersion = readUInt32LE(payload, 18)
            lines.add("${ui.bootloader}: ${formatVersion(ldrVersion)}")
        }

        if (payload.size >= 26) {
            val appVersion = readUInt32LE(payload, 22)
            lines.add("${ui.firmware}: ${formatVersion(appVersion)}")
        }

        lines.add("")
        lines.add(ui.rawPayload(payload.size))
        lines.add(payload.joinToString(" ") { "%02x".format(it) })

        return lines.joinToString("\n")
    }

    /** Reads a 32-bit little-endian unsigned integer from a byte array. */
    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF)) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** Formats a DJI firmware version uint32 as major.minor.patch.build. */
    private fun formatVersion(version: Long): String {
        val major = (version shr 24) and 0xFF
        val minor = (version shr 16) and 0xFF
        val patch = (version shr 8) and 0xFF
        val build = version and 0xFF
        return "$major.$minor.$patch.$build"
    }

    /** Atomically updates the state via a copy() block. */
    private fun update(block: AppState.() -> AppState) {
        _state.value = _state.value.block()
    }

    /** Adds a timestamped entry to the activity log (most recent first, max 50). */
    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $message"
        update { copy(logMessages = (listOf(entry) + logMessages).take(50)) }
    }

    /** Launches a coroutine on Dispatchers.IO for network operations. */
    private fun runOnIO(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}

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

/**
 * Severity of [AppState.message] and the panel status lines.
 *
 * The UI used to decide colour by substring-matching translated prose
 * ("does it contain 'failed' / 'başarısız' / 'yok'?"), which silently
 * mis-coloured real failures — "4G error: …" matched nothing. Tone is set
 * here, where the outcome is actually known, so the UI never has to guess.
 */
enum class Tone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER }

/**
 * Outcome of the last 4G activation attempt.
 *
 * WRITTEN is deliberately distinct from SUCCESS: the 4G socket never
 * acknowledges, so a completed run only proves the 128 datagrams left the
 * app — it does not prove the aircraft activated 4G.
 */
enum class FourGOutcome {
    IDLE,
    RUNNING,
    WRITTEN,
    NO_SERIAL,
    NO_DONGLE,
    WRITE_FAILED,
    ERROR
}

data class AppState(
    val language: AppLanguage = AppLanguage.TR,
    val status: String = "idle",
    val message: String = "",
    val messageTone: Tone = Tone.NEUTRAL,
    val isConnected: Boolean = false,
    val isFccEnabled: Boolean = false,
    val is4gBusy: Boolean = false,
    val fourGMessage: String = "",
    val fourGOutcome: FourGOutcome = FourGOutcome.IDLE,
    val isBusy: Boolean = false,
    val isHardwareBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val manualSerial: String = "",
    val isProbingSerial: Boolean = false,
    val controllerModel: String = "",
    val deviceInfo: String = "",
    val isQueryingInfo: Boolean = false,
    val autoFcc: Boolean = false,
    val isLedBusy: Boolean = false,
    val ledStatus: String = "",
    val ledTone: Tone = Tone.NEUTRAL,
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
 * Pure aircraft-serial resolution policy.
 *
 * Split out of [FccViewModel] so it can be unit-tested without an Android
 * Context, and so the priority order is stated in one place. This logic
 * regressed once already: a revision deleted the active-query step and
 * shortened the passive window, which made 4G abort on a controller that was
 * already connected.
 */
internal object SerialResolution {

    /**
     * Highest-priority non-blank source wins. A manually-entered serial beats
     * every detected value — the user typed it on purpose, so trust its format
     * whatever it looks like.
     */
    fun pick(manual: String, session: String, cached: String): String =
        manual.ifBlank { session.ifBlank { cached } }

    /**
     * Pulls a W[AM]xxx model code out of a serial when one is present.
     *
     * A full 1581… factory serial contains no model code, so null is the
     * expected answer there — not a failure. Advisory only: the 4G dongle
     * probe is the authoritative gate, never this value.
     */
    fun modelHint(serial: String): String? =
        Regex("[wW][aAmM][0-9]{3}").find(serial)?.value?.lowercase()
}

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

        /** Active VersionInquiry window. A direct query answers well inside this. */
        const val ACTIVE_QUERY_MS = 800

        /**
         * Passive telemetry listen. Long on purpose: it only succeeds if the
         * aircraft happens to broadcast a serial-bearing frame, so the window
         * has to cover a normal telemetry cadence.
         */
        const val PASSIVE_LISTEN_MS = 8000
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
        val cachedManual = prefs.getString("manual_aircraft_sn", "").orEmpty()
        val language = AppLanguage.fromPref(prefs.getString("language", null))
        update { copy(language = language) }
        if (cachedManual.isNotEmpty()) {
            update { copy(manualSerial = cachedManual) }
        }
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

    /**
     * Claims the hardware lock, or fails *visibly*.
     *
     * Every busy path used to call [beginHardwareOp] directly and return on
     * false after only a `log()` — which lands on the Log tab, three taps away.
     * With keepalive re-applying FCC every 2 seconds that window is open a
     * noticeable fraction of the time, so a tap looked like a dead button.
     * Now the contention is reported in [AppState.message], which the status
     * strip renders in place.
     *
     * @param pending localized name of the operation the caller wanted to run
     */
    private fun claimHardwareOp(pending: String): Boolean {
        if (beginHardwareOp()) return true
        val tr = _state.value.language == AppLanguage.TR
        val message = if (tr) {
            "Donanım meşgul — \"$pending\" başlatılamadı. Bir saniye sonra tekrar dene."
        } else {
            "Hardware busy — \"$pending\" could not start. Try again in a moment."
        }
        update { copy(message = message, messageTone = Tone.WARNING) }
        log(message)
        return false
    }

    /** Short localized label for an operation, used in busy/contention messages. */
    private fun label(tr: String, en: String): String =
        if (_state.value.language == AppLanguage.TR) tr else en

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
        if (!claimHardwareOp(label("Otomatik FCC", "Auto-FCC"))) return
        val language = _state.value.language
        runOnIO {
            try {
                // Wait a moment for the UI to render
                delay(1000)

                // Try to connect — scans all known ports
                update { copy(status = "connecting", message = if (language == AppLanguage.TR) "Otomatik bağlanıyor..." else "Auto-connecting...", messageTone = Tone.INFO) }
                if (!transport.connect()) {
                    log(if (language == AppLanguage.TR) "Auto-FCC: kumanda bulunamadı — drone açık mı?" else "Auto-FCC: controller not found — is the drone powered on?")
                    update { copy(status = "disconnected", message = if (language == AppLanguage.TR) "Kumanda bulunamadı. Bağlan'a bastığında tekrar deneyebilirsin." else "Controller not found. Auto-FCC will retry when you tap Connect.", messageTone = Tone.DANGER) }
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
                        message = if (language == AppLanguage.TR) "Bağlandı. FCC otomatik uygulanıyor..." else "Connected. Auto-applying FCC...",
                        messageTone = Tone.SUCCESS
                    )
                }
                if (serial.isNotEmpty()) log(if (language == AppLanguage.TR) "Hava aracı seri no: $serial" else "Aircraft serial: $serial")

                // Apply FCC
                delay(500)
                update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = if (language == AppLanguage.TR) "FCC modu uygulanıyor..." else "Applying FCC mode...", messageTone = Tone.INFO) }
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
                            messageTone = Tone.SUCCESS,
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
                    update { copy(message = if (language == AppLanguage.TR) "FCC aktif. DJI Fly açılıyor..." else "FCC active. Launching DJI Fly...", messageTone = Tone.SUCCESS) }
                    log(if (language == AppLanguage.TR) "Auto-FCC: DJI Fly açılıyor" else "Auto-FCC: launching DJI Fly")
                    launchDjiFly()
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = if (language == AppLanguage.TR) "Auto-FCC başarısız — manuel dene" else "Auto-FCC failed — try manually",
                            messageTone = Tone.DANGER,
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log(if (language == AppLanguage.TR) "Auto-FCC: uygulama başarısız — manuel dene" else "Auto-FCC: apply failed — try manually")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "Auto-FCC hatası: ${e.message}" else "Auto-FCC error: ${e.message}")
                update { copy(status = "disconnected", message = if (language == AppLanguage.TR) "Auto-FCC hatası: ${e.message}" else "Auto-FCC error: ${e.message}", messageTone = Tone.DANGER, isBusy = false, busyProgress = 0f) }
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
        if (!claimHardwareOp(label("Bağlan", "Connect"))) return
        val language = _state.value.language
        update { copy(status = "connecting", message = if (language == AppLanguage.TR) "Kumandaya bağlanılıyor..." else "Connecting to controller...", messageTone = Tone.INFO) }
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
                            messageTone = Tone.SUCCESS,
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
                            isConnected = false,
                            messageTone = Tone.DANGER
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
        if (!claimHardwareOp(label("FCC", "FCC"))) return
        val language = _state.value.language
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = if (language == AppLanguage.TR) "FCC modu açılıyor..." else "Enabling FCC mode...", messageTone = Tone.INFO, fourGMessage = "", fourGOutcome = FourGOutcome.IDLE) }
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
                            messageTone = Tone.SUCCESS,
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
                            messageTone = Tone.DANGER,
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log(if (language == AppLanguage.TR) "FCC uygulama başarısız — yazma işlemleri başarısız" else "FCC apply failed — writes failed")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "FCC uygulama hatası: ${e.message}" else "FCC apply error: ${e.message}")
                update { copy(status = "connected", message = if (language == AppLanguage.TR) "FCC uygulama hatası: ${e.message}" else "FCC apply error: ${e.message}", messageTone = Tone.DANGER, isBusy = false, busyProgress = 0f) }
            } finally {
                endHardwareOp()
            }
        }
    }

    /** Sends the CE restore command: a single frame that resets to factory region. */
    fun disableFcc() {
        if (!claimHardwareOp(label("CE geri yükleme", "CE restore"))) return
        val language = _state.value.language
        // Stop keepalive first — otherwise it re-applies FCC 2 seconds after
        // we restore CE, undoing the user's intent.
        if (_state.value.isKeepaliveRunning) {
            stopKeepalive()
        }
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = if (language == AppLanguage.TR) "CE modu geri yükleniyor..." else "Restoring CE mode...", messageTone = Tone.INFO, fourGMessage = "", fourGOutcome = FourGOutcome.IDLE) }
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
                    update { copy(status = "connected", message = if (language == AppLanguage.TR) "CE modu geri yüklendi" else "CE mode restored", messageTone = Tone.SUCCESS, isFccEnabled = false, isBusy = false) }
                    log(if (language == AppLanguage.TR) "CE modu geri yüklendi" else "CE mode restored")
                } else {
                    update { copy(status = "connected", message = if (language == AppLanguage.TR) "CE geri yüklenemedi — RC bağlantısı yok" else "CE restore failed — RC link unreachable", messageTone = Tone.DANGER, isBusy = false) }
                    log(if (language == AppLanguage.TR) "CE geri yükleme başarısız" else "CE restore failed")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "CE geri yükleme hatası: ${e.message}" else "CE restore error: ${e.message}")
                update { copy(status = "connected", message = if (language == AppLanguage.TR) "CE geri yükleme hatası: ${e.message}" else "CE restore error: ${e.message}", messageTone = Tone.DANGER, isBusy = false) }
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
     * 4G frames go over the abstract Unix socket `/duss/mb/0x205`, not TCP.
     *
     * That socket never acknowledges, so a completed run proves only that the
     * 128 datagrams left the app — it does **not** prove the aircraft
     * activated 4G. There is no reverse command either. The UI therefore
     * reports [FourGOutcome.WRITTEN], never a success badge.
     *
     * Guards:
     * 1. Aircraft serial must be present — it is embedded in every payload.
     *    No length check: the probe may return either the full 1581… factory
     *    serial or a short W[AM]xxx model code, and both build a valid frame.
     * 2. The 4G dongle must be present — this is the authoritative gate. If
     *    `/duss/mb/0x205` is not connectable, no cellular module is attached
     *    and all 128 frames would be dropped, so we stop before writing them.
     * 3. Model code is advisory only, logged when it is not a known-4G model.
     *    A full 1581… serial carries no model code to check and the list is
     *    not authoritative — DJI ships the Cellular Dongle 2 for the Mini 4 Pro
     *    as well. Blocking on it would reject working hardware.
     *
     * Ordering note: FCC keepalive re-asserts the FCC radio profile every 2s.
     * If it is running, 4G activation competes with it for [HardwareLock] and
     * the keepalive may re-assert FCC immediately afterwards. The UI warns
     * about this rather than silently interleaving the two.
     */
    fun send4gActivationFrames() {
        if (!claimHardwareOp(label("4G aktivasyonu", "4G activation"))) return
        val language = _state.value.language
        update { copy(is4gBusy = true, busyProgress = 0f, fourGMessage = "", fourGOutcome = FourGOutcome.RUNNING) }
        log(if (language == AppLanguage.TR) "4G aktivasyon frameleri gönderiliyor..." else "Sending 4G activation frames...")

        runOnIO {
            try {
                val serial = getOrProbeSerial()

                // Guard 1: we need *some* serial to embed in the payload.
                if (serial.isEmpty()) {
                    update {
                        copy(
                            is4gBusy = false,
                            fourGOutcome = FourGOutcome.NO_SERIAL,
                            fourGMessage = if (language == AppLanguage.TR) {
                                "Hava aracı seri numarası okunamadı. Canlı görüntü açıkken drone'u aç ve link et, ya da Bilgi sayfasından seriyi elle gir."
                            } else {
                                "Could not read the aircraft serial. Power on and link the aircraft with the live view up, or type the serial on the Info tab."
                            }
                        )
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon başarısız — hava aracı seri numarası alınamadı" else "4G activation failed — no aircraft serial could be read")
                    return@runOnIO
                }

                // Advisory only: pull a W[AM]xxx model code from anywhere in the
                // serial (a full 1581… serial won't contain one). Never blocks.
                val modelHint = SerialResolution.modelHint(serial)
                if (modelHint != null && modelHint !in MODELS_WITH_4G) {
                    log(
                        if (language == AppLanguage.TR) {
                            "Not: model '$modelHint' bilinen 4G listesinde değil; yine de deneniyor. 4G aktive olmazsa bu hava aracı Cellular Dongle 2'yi kabul etmiyor olabilir."
                        } else {
                            "Note: model '$modelHint' isn't in the known-4G list, but a dongle check follows — trying anyway. If 4G doesn't activate, this aircraft may not accept the Cellular Dongle 2."
                        }
                    )
                }

                // Guard 2 (authoritative): dongle pre-check — fast-fail if the socket does not exist.
                if (!transport.is4gDonglePresent()) {
                    update {
                        copy(
                            is4gBusy = false,
                            fourGOutcome = FourGOutcome.NO_DONGLE,
                            fourGMessage = if (language == AppLanguage.TR) {
                                "4G dongle algılanmadı. DJI Cellular Dongle 2'yi hava aracına bağla (Mini 4 Pro için 4G montaj kiti gerekir) ve tekrar dene."
                            } else {
                                "4G dongle not detected. Connect a DJI Cellular Dongle 2 to the aircraft (Mini 4 Pro also needs its 4G mounting kit) and try again."
                            }
                        )
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon iptal — /duss/mb/0x205 soketine bağlanılamıyor (dongle yok?)" else "4G activation aborted — 4G socket /duss/mb/0x205 not connectable (no dongle?)")
                    return@runOnIO
                }

                val profile = Profiles.load4g(app, serial)
                log(if (language == AppLanguage.TR) "4G profili yüklendi: ${profile.frames.size} frame (seri: $serial, model: ${modelHint ?: "bilinmiyor"})" else "Loaded 4G profile: ${profile.frames.size} frames (serial: $serial, model: ${modelHint ?: "unknown"})")

                // 4G uses Unix domain socket, not TCP
                val success = transport.sendFramesUnix(
                    frames = profile.frames,
                    interFrameDelayMs = profile.interFrameDelay
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    val written = if (language == AppLanguage.TR) {
                        "${profile.frames.size} aktivasyon frame'i yazıldı. Sokak yanıt vermediği için bu, 4G'nin gerçekten aktive olduğunu kanıtlamaz — hava aracından doğrula."
                    } else {
                        "${profile.frames.size} activation frames written. The socket never acknowledges, so this does not prove 4G is active — confirm it on the aircraft."
                    }
                    val conflicting = _state.value.isKeepaliveRunning
                    update {
                        copy(
                            is4gBusy = false,
                            busyProgress = 0f,
                            fourGOutcome = FourGOutcome.WRITTEN,
                            fourGMessage = if (conflicting) {
                                (if (language == AppLanguage.TR) {
                                    "$written Uyarı: FCC keepalive her 2 saniyede bir yeniden uyguluyor ve 4G aktivasyonunu geçersiz kılabilir — doğruladıktan sonra keepalive'i durdur."
                                } else {
                                    "$written Warning: FCC keepalive re-applies every 2s and can undo the 4G activation — stop keepalive once you have confirmed it."
                                })
                            } else written
                        )
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon: ${profile.frames.size} frame Unix soketine yazıldı" else "4G activation: all ${profile.frames.size} frames written successfully via Unix socket")
                    if (conflicting) {
                        log(if (language == AppLanguage.TR) "4G/FCC çakışma riski — keepalive çalışıyor, her 2 saniyede bir FCC yeniden uygulanıyor" else "4G/FCC conflict risk — keepalive is running and re-applies FCC every 2s")
                    }
                } else {
                    update {
                        copy(
                            is4gBusy = false,
                            fourGOutcome = FourGOutcome.WRITE_FAILED,
                            fourGMessage = if (language == AppLanguage.TR) "En az bir frame Unix soketine yazılamadı. 4G dongle bağlı ve açık mı?" else "At least one frame could not be written to the Unix socket. Is the 4G dongle attached and powered?"
                        )
                    }
                    log(if (language == AppLanguage.TR) "4G aktivasyon başarısız — Unix soketinde en az bir frame yazılamadı" else "4G activation failed — at least one frame write failed on the Unix socket")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "4G aktivasyon hatası: ${e.message}" else "4G activation error: ${e.message}")
                update {
                    copy(
                        is4gBusy = false,
                        fourGOutcome = FourGOutcome.ERROR,
                        fourGMessage = if (language == AppLanguage.TR) "4G hatası: ${e.message}" else "4G error: ${e.message}"
                    )
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    /**
     * True when a background FCC re-apply can interfere with a 4G activation
     * attempt. Pure so the UI can show the warning without duplicating logic.
     */
    fun is4gAtRiskFromKeepalive(): Boolean = _state.value.isKeepaliveRunning

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
        update { copy(isLedBusy = true, ledTone = Tone.INFO, ledStatus = if (language == AppLanguage.TR) { if (on) "LED'ler açılıyor..." else "LED'ler kapatılıyor..." } else { if (on) "Turning LEDs on..." else "Turning LEDs off..." }) }
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
                    update { copy(isLedBusy = false, ledTone = Tone.SUCCESS, ledStatus = if (on) "ON" else "OFF") }
                    log(if (language == AppLanguage.TR) { if (on) "LED'ler açıldı" else "LED'ler kapatıldı" } else { if (on) "LEDs turned on" else "LEDs turned off" })
                } else {
                    update { copy(isLedBusy = false, ledTone = Tone.DANGER, ledStatus = if (language == AppLanguage.TR) "Başarısız — DJI Fly çalışıyor mu?" else "Failed — is DJI Fly running?") }
                    log(if (language == AppLanguage.TR) "LED komutu başarısız — DJI Fly açık ve hava aracı bağlı olmalı" else "LED command failed — make sure DJI Fly is running with aircraft connected")
                }
            } catch (e: Exception) {
                log(if (language == AppLanguage.TR) "LED hatası: ${e.message}" else "LED error: ${e.message}")
                update { copy(isLedBusy = false, ledTone = Tone.DANGER, ledStatus = if (language == AppLanguage.TR) "Hata: ${e.message}" else "Error: ${e.message}") }
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
        val language = _state.value.language
        if (!isControllerReachable()) {
            update {
                copy(
                    isQueryingInfo = false,
                    deviceInfo = if (language == AppLanguage.TR) {
                        "Önce ana ekrandan kumandaya bağlan."
                    } else {
                        "Connect to the controller from the home screen first."
                    }
                )
            }
            return
        }
        if (!claimHardwareOp(label("Cihaz bilgisi", "Device info"))) {
            update {
                copy(deviceInfo = if (language == AppLanguage.TR) "Kumanda meşgul. Birkaç saniye sonra tekrar dene." else "Controller is busy. Try again in a few seconds.")
            }
            return
        }

        update { copy(isQueryingInfo = true, deviceInfo = "") }
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

                var response: ByteArray? = null
                repeat(2) {
                    if (response == null) response = transport.sendAndReceive(frame, profile.readWindowMs)
                }

                if (response == null || response.isEmpty()) {
                    update {
                        copy(
                            isQueryingInfo = false,
                            deviceInfo = if (language == AppLanguage.TR) {
                                "Kumandadan sürüm yanıtı alınamadı. Drone ve DJI Fly bağlantısını kontrol edip tekrar dene."
                            } else {
                                "No version response. Check the aircraft and DJI Fly connection, then try again."
                            }
                        )
                    }
                    log(if (language == AppLanguage.TR) "Cihaz bilgisi: yanıt yok" else "Device info: no response")
                    return@runOnIO
                }

                val info = formatVersionResponse(response!!)
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

    /** User-facing re-scan of the aircraft serial. Active query first, then a longer passive listen. */
    fun probeSerial() {
        if (!claimHardwareOp(label("Seri numarası taraması", "Serial scan"))) return
        val language = _state.value.language
        update { copy(isProbingSerial = true) }
        log(if (language == AppLanguage.TR) "Hava aracı seri numarası aranıyor..." else "Reading aircraft serial...")
        runOnIO {
            try {
                detectAndCacheSerial()
            } finally {
                update { copy(isProbingSerial = false) }
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
        if (force) {
            UpdateDiagnostics.clearResult(app)
            update { copy(
                updateDiagnosticSummary = "",
                updateDiagnosticDetails = "",
                updateDiagnosticFailure = false,
                updateStage = UpdateStage.NONE
            ) }
        }
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

            val currentVersion = BuildConfig.VERSION_NAME
            val isNewer = info.isNewerThan(currentVersion)
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
                log(if (language == AppLanguage.TR) "DronePeak-FCC güncel (v$currentVersion)" else "DronePeak-FCC is up to date (v$currentVersion)")
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

    /**
     * Resolves the aircraft serial, in priority order.
     *
     * 1. A manually-entered serial — always wins, whatever its format.
     * 2. The serial from this session or a previous one (SharedPreferences).
     * 3. An *active* VersionInquiry query (fast, deterministic while linked).
     * 4. A longer passive telemetry listen.
     *
     * Steps 3 and 4 matter more than they look. [DumlTransport.probeSerialActive]
     * asks the aircraft directly, so it returns an answer whenever the RC link
     * is up. [DumlTransport.probeSerial] only sniffs broadcast telemetry frames,
     * so it returns nothing unless the aircraft happens to be transmitting the
     * right bytes inside the window. A previous revision dropped the active
     * query and shortened the passive window from 8s to 2s, which made 4G abort
     * at its serial guard on a controller that was in fact connected — the app
     * told the user to tap Connect while Connect was already done.
     */
    private fun getOrProbeSerial(): String {
        val known = SerialResolution.pick(
            manual = _state.value.manualSerial.ifEmpty { prefs.getString("manual_aircraft_sn", "").orEmpty() },
            session = _state.value.aircraftSerial,
            cached = prefs.getString("aircraft_serial", "").orEmpty()
        )
        if (known.isNotEmpty()) {
            update { copy(aircraftSerial = known) }
            return known
        }
        return detectAndCacheSerial()
    }

    /**
     * Runs the two-stage detection (active query, then passive listen) and
     * caches a hit. Shared by [getOrProbeSerial] and the user-facing
     * [probeSerial] action so both paths behave identically.
     */
    private fun detectAndCacheSerial(): String {
        val tr = _state.value.language == AppLanguage.TR
        log(if (tr) "Hava aracı seri numarası aranıyor..." else "Reading aircraft serial...")

        var serial = transport.probeSerialActive(ACTIVE_QUERY_MS)
        if (serial.isEmpty()) {
            log(
                if (tr) "Seri sorgusuna yanıt yok — telemetri dinleniyor (en fazla ${PASSIVE_LISTEN_MS / 1000} sn)..."
                else "No reply to the serial query — listening for telemetry (up to ${PASSIVE_LISTEN_MS / 1000}s)..."
            )
            serial = transport.probeSerial(PASSIVE_LISTEN_MS)
        }

        if (serial.isNotEmpty()) {
            update { copy(aircraftSerial = serial) }
            prefs.edit().putString("aircraft_serial", serial).apply()
            log(if (tr) "Hava aracı seri no: $serial (önbelleğe alındı)" else "Aircraft serial: $serial (cached)")
        } else {
            log(
                if (tr) "Seri numarası algılanmadı — canlı görüntü açıkken hava aracını aç ve link et, ya da seriyi elle gir."
                else "No serial detected — power on and link the aircraft with the live view up, or enter it manually."
            )
        }
        return serial
    }

    /**
     * Stores a manually-entered aircraft serial. A manual serial takes priority
     * over every detected value, so a controller that cannot be probed still
     * has a working path to 4G activation.
     */
    fun setManualSerial(serial: String) {
        val trimmed = serial.trim()
        prefs.edit().putString("manual_aircraft_sn", trimmed).apply()
        update { copy(manualSerial = trimmed, aircraftSerial = trimmed) }
        log(
            if (_state.value.language == AppLanguage.TR) {
                if (trimmed.isEmpty()) "Elle girilen seri temizlendi" else "Elle girilen seri kaydedildi: $trimmed"
            } else {
                if (trimmed.isEmpty()) "Manual serial cleared" else "Manual serial saved: $trimmed"
            }
        )
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

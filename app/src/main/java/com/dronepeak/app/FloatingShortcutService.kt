package com.dronepeak.app

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.res.Configuration
import android.graphics.*
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import kotlin.math.hypot

/** Overlay navigation only. No DUML transport or dependency on FCC keepalive lifetime. */
class FloatingShortcutService : Service() {
    private val prefs by lazy { getSharedPreferences(FloatingShortcutController.PREFS, MODE_PRIVATE) }
    private val manager by lazy { getSystemService(WindowManager::class.java) }
    private var bubble: Bubble? = null
    private var attached = false
    private val permissionListener = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (operation == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == this.packageName) {
            Handler(Looper.getMainLooper()).post { refreshVisibility() }
        }
    }
    private val safe = Rect()
    private var position = ShortcutPosition(0.85f, 0.5f)
    private lateinit var params: WindowManager.LayoutParams
    private val density get() = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val maxX get() = (safe.width() - dp(48)).coerceAtLeast(0).toFloat()
    private val maxY get() = (safe.height() - dp(48)).coerceAtLeast(0).toFloat()
    private fun ui() = TextCatalog.ui(AppLanguage.fromPref(getSharedPreferences("dronepeak", MODE_PRIVATE).getString("language", null)))

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(NotificationChannel("floating_shortcut", ui().shortcutTitle,
            NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        val stop = PendingIntent.getService(this, 9020,
            Intent(this, FloatingShortcutService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 9021, controlIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "floating_shortcut")
            .setSmallIcon(R.drawable.ic_floating_shortcut).setContentTitle(ui().shortcutTitle)
            .setContentText(ui().shortcutDetail).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, ui().shortcutStop, stop).build()).build()
        startForeground(9020, notification)
        getSystemService(AppOpsManager::class.java).startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
    }

    @SuppressLint("RtlHardcoded") // Coordinates are physical screen edges, independent of text direction.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            prefs.edit().putBoolean("enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.getBoolean("enabled", false) || !ActivationManager.isActivated(this) || !Settings.canDrawOverlays(this)) {
            if (!ActivationManager.isActivated(this)) FloatingShortcutController.failed(ShortcutIssue.ACTIVATION)
            else if (!Settings.canDrawOverlays(this)) FloatingShortcutController.failed(ShortcutIssue.PERMISSION)
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) {
            position = ShortcutPosition(prefs.getFloat("x", 0.85f).coerceIn(0f, 1f),
                prefs.getFloat("y", 0.5f).coerceIn(0f, 1f),
                runCatching { ShortcutEdge.valueOf(prefs.getString("edge", "NONE")!!) }.getOrDefault(ShortcutEdge.NONE))
            params = WindowManager.LayoutParams(dp(48), dp(48), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                title = "DronePeak floating shortcut"
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            }
            updateBounds()
            bubble = Bubble()
        }
        FloatingShortcutController.ready(this)
        refreshVisibility()
        return if (bubble != null) START_STICKY else START_NOT_STICKY
    }

    private fun controlIntent() = Intent(this, MainActivity::class.java)
        .putExtra("open_control", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    internal fun refreshVisibility() {
        if (!Settings.canDrawOverlays(this) || !ActivationManager.isActivated(this)) {
            FloatingShortcutController.failed(if (!Settings.canDrawOverlays(this)) ShortcutIssue.PERMISSION else ShortcutIssue.ACTIVATION)
            stopSelf()
            return
        }
        val view = bubble ?: return
        if (FloatingShortcutController.appVisible) {
            if (attached) { runCatching { manager.removeView(view) }; attached = false }
        } else if (!attached) {
            updateBounds()
            updateCoordinates()
            runCatching { manager.addView(view, params); attached = true }.onFailure {
                FloatingShortcutController.failed()
                stopSelf()
            }
        }
    }

    private fun updateBounds() {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safe.set(metrics.bounds)
            safe.set(safe.left + insets.left, safe.top + insets.top,
                safe.right - insets.right, safe.bottom - insets.bottom)
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealSize(size)
            safe.set(0, 0, size.x, size.y)
            // API 29 has no WindowMetrics; root insets become available after attachment.
            bubble?.rootWindowInsets?.let {
                @Suppress("DEPRECATION")
                safe.set(safe.left + it.systemWindowInsetLeft, safe.top + it.systemWindowInsetTop,
                    safe.right - it.systemWindowInsetRight, safe.bottom - it.systemWindowInsetBottom)
            }
        }
    }

    private fun updateCoordinates() {
        params.x = safe.left + (position.x * maxX).toInt()
        params.y = safe.top + (position.y * maxY).toInt()
        bubble?.contentDescription = if (position.edge == ShortcutEdge.NONE) ui().shortcutOpen else ui().shortcutReveal
        bubble?.invalidate()
        if (attached) runCatching { manager.updateViewLayout(bubble, params) }.onFailure {
            FloatingShortcutController.failed(); stopSelf()
        }
    }
    private fun savePosition() {
        prefs.edit().putFloat("x", position.x).putFloat("y", position.y)
            .putString("edge", position.edge.name).apply()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (bubble != null) { updateBounds(); updateCoordinates() }
    }
    override fun onDestroy() {
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        if (attached) runCatching { manager.removeView(bubble) }
        attached = false
        bubble = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        FloatingShortcutController.stopped(this)
        super.onDestroy()
    }

    private inner class Bubble : View(this@FloatingShortcutService) {
        private val icon = getDrawable(R.drawable.dronepeak_icon)!!
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrow = Path()
        private var downX = 0f
        private var downY = 0f
        private var originalX = 0f
        private var originalY = 0f
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        init {
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnApplyWindowInsetsListener { _, insets ->
                updateBounds(); updateCoordinates(); insets
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (position.edge == ShortcutEdge.NONE) {
                val inset = dp(8)
                icon.setBounds(inset, inset, width - inset, height - inset)
                icon.draw(canvas)
            } else {
                canvas.save()
                canvas.translate(width / 2f, height / 2f)
                canvas.rotate(when (position.edge) {
                    ShortcutEdge.LEFT -> 0f
                    ShortcutEdge.RIGHT -> 180f
                    ShortcutEdge.TOP -> 90f
                    else -> 270f
                })
                paint.color = Color.argb(89, 242, 245, 246)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density * 2
                paint.strokeCap = Paint.Cap.ROUND
                val half = dp(6).toFloat()
                arrow.reset()
                arrow.moveTo(-half / 2, -half)
                arrow.lineTo(half / 2, 0f)
                arrow.lineTo(-half / 2, half)
                canvas.drawPath(arrow, paint)
                canvas.restore()
            }
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    originalX = position.x * maxX; originalY = position.y * maxY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (hypot(dx, dy) > slop) dragging = true
                    if (dragging) {
                        position = ShortcutGeometry.clamp(originalX + dx, originalY + dy, maxX, maxY)
                        updateCoordinates()
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        position = ShortcutGeometry.dock(position.x * maxX, position.y * maxY, maxX, maxY, dp(24).toFloat())
                        updateCoordinates(); savePosition()
                    } else performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { savePosition(); return true }
            }
            return true
        }
        override fun performClick(): Boolean {
            super.performClick()
            if (position.edge != ShortcutEdge.NONE) {
                position = ShortcutGeometry.reveal(position, maxX, maxY, dp(32).toFloat())
                updateCoordinates(); savePosition()
            } else runCatching { startActivity(controlIntent()) }.onFailure { FloatingShortcutController.failed() }
            return true
        }
    }
}

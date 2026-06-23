package com.rotationbutton

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class RotationService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private var buttonView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var touchSlop = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) repositionOverlay()
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(DisplayManager::class.java)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        startAsForeground()
        addOverlayButton()
        displayManager.registerDisplayListener(displayListener, null)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        removeOverlayButton()
        isRunning = false
        super.onDestroy()
    }

    private fun addOverlayButton() {
        if (buttonView != null) return
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        val savedFraction = loadSavedFraction()
        val navH = navBarHeight()
        val screenW = resources.displayMetrics.widthPixels
        // Estimate initial x so the button renders at the correct position on the first frame.
        // Actual correction happens in view.post{} once the real width is known.
        val estimatedW = (48 * resources.displayMetrics.density).toInt()
        val initialX = ((screenW - estimatedW) * savedFraction).toInt()

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            navH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = initialX
            y = 0
        }

        view.setOnTouchListener(DragClickListener())
        runCatching { windowManager.addView(view, params) }.onFailure { stopSelf(); return }
        buttonView = view

        view.post {
            // Correct x using the real measured width
            val iconW = view.width.takeIf { it > 0 } ?: return@post
            params.x = ((screenW - iconW) * savedFraction).toInt()
                .coerceIn(0, screenW - iconW)
            runCatching { windowManager.updateViewLayout(view, params) }
            updateGestureExclusion(view)
        }
    }

    // On rotation: reload saved fraction and re-apply with new screen dimensions.
    private fun repositionOverlay() {
        val view = buttonView ?: return
        val navH = navBarHeight()
        val screenW = resources.displayMetrics.widthPixels
        val iconW = view.width.takeIf { it > 0 } ?: return
        val fraction = loadSavedFraction()
        params.height = navH
        params.x = ((screenW - iconW) * fraction).toInt().coerceIn(0, screenW - iconW)
        params.y = 0
        runCatching { windowManager.updateViewLayout(view, params) }
        updateGestureExclusion(view)
    }

    private fun updateGestureExclusion(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val r = Rect(0, 0, view.width, view.height)
            view.systemGestureExclusionRects = listOf(r)
        }
    }

    private fun removeOverlayButton() {
        buttonView?.let { runCatching { windowManager.removeView(it) } }
        buttonView = null
    }

    private inner class DragClickListener : View.OnTouchListener {
        private var initialX = 0
        private var downRawX = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    downRawX = event.rawX
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    if (abs(dx) > touchSlop) moved = true
                    val screenW = resources.displayMetrics.widthPixels
                    val iconW = v.width.takeIf { it > 0 } ?: 1
                    params.x = (initialX + dx).coerceIn(0, screenW - iconW)
                    windowManager.updateViewLayout(v, params)
                    updateGestureExclusion(v)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) savePosition(v) else onButtonClicked()
                    return true
                }
            }
            return false
        }
    }

    private fun onButtonClicked() {
        RotationManager.rotateNext(this)
    }

    private fun savePosition(view: View) {
        val screenW = resources.displayMetrics.widthPixels
        val iconW = view.width.takeIf { it > 0 } ?: return
        val maxX = (screenW - iconW).toFloat()
        val fraction = if (maxX > 0f) (params.x / maxX).coerceIn(0f, 1f) else 1f
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_X_FRACTION, fraction)
            .apply()
    }

    // Returns 0.0 (left) … 1.0 (right). Default 1.0 = right side.
    private fun loadSavedFraction(): Float {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentVersion = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        }.getOrDefault(0)

        val savedVersion = prefs.getInt(KEY_VERSION, -1)
        return if (savedVersion != currentVersion) {
            prefs.edit().remove(KEY_X_FRACTION).putInt(KEY_VERSION, currentVersion).apply()
            1f
        } else {
            prefs.getFloat(KEY_X_FRACTION, 1f)
        }
    }

    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (48 * resources.displayMetrics.density).toInt()
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun startAsForeground() {
        createNotificationChannel()

        val stopIntent = Intent(this, RotationService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_rotate)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .addAction(0, getString(R.string.notif_stop), stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.rotationbutton.action.STOP"

        private const val CHANNEL_ID = "rotation_button_channel"
        private const val NOTIF_ID = 1001
        private const val PREFS = "rotation_button_prefs"
        private const val KEY_X_FRACTION = "pos_x_fraction"
        private const val KEY_VERSION = "version_code"

        @JvmStatic
        var isRunning: Boolean = false
            private set

        fun start(context: Context) =
            context.startForegroundService(Intent(context, RotationService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, RotationService::class.java).apply {
                action = ACTION_STOP
            })
    }
}

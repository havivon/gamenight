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
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
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
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class RotationService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private var rootView: FrameLayout? = null
    private var iconView: View? = null
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
        if (rootView != null) return
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        val savedFraction = loadSavedFraction()
        val navH = navBarHeight()
        val screenH = screenHeight()

        val root = FrameLayout(this)

        val icon = LayoutInflater.from(this).inflate(R.layout.overlay_button, root, false)
        // Center icon horizontally; translationX slides it left/right within the full-width window
        root.addView(icon, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER_HORIZONTAL
        ))

        // Gravity.TOP + y=(screenH - navH) places the window at the exact nav-bar row,
        // regardless of how FLAG_LAYOUT_IN_SCREEN resolves the BOTTOM anchor on each device.
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            navH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = screenH - navH
        }

        icon.setOnTouchListener(DragClickListener(icon))

        runCatching { windowManager.addView(root, params) }.onFailure { stopSelf(); return }
        rootView = root
        iconView = icon

        root.post {
            applyFraction(savedFraction, icon)
            setupTouchPassthrough(root, icon)
            updateGestureExclusion(icon)
        }
    }

    // Uses reflection to access the @hide InternalInsetsInfo API at runtime.
    // TOUCHABLE_INSETS_REGION (=3): only the icon rect receives touches;
    // the rest of the full-width window passes touches to the nav bar below.
    private fun setupTouchPassthrough(root: View, icon: View) {
        try {
            val infoClass = Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")
            val setInsets = infoClass.getMethod("setTouchableInsets", Int::class.java)
            val regionField = infoClass.getField("touchableRegion")
            val listenerIface = Class.forName(
                "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener"
            )
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerIface.classLoader, arrayOf(listenerIface)
            ) { _, _, args ->
                val info = args?.getOrNull(0) ?: return@newProxyInstance null
                val r = Rect()
                icon.getGlobalVisibleRect(r)
                setInsets.invoke(info, 3 /* TOUCHABLE_INSETS_REGION */)
                (regionField.get(info) as Region).set(r)
                null
            }
            val addListener = ViewTreeObserver::class.java
                .getMethod("addOnComputeInternalInsetsListener", listenerIface)
            addListener.invoke(root.viewTreeObserver, proxy)
        } catch (_: Exception) {
            // If reflection is blocked the overlay still works; HOME/BACK/RECENTS
            // remain functional because the icon doesn't cover those button positions.
        }
    }

    private fun updateGestureExclusion(icon: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val r = Rect()
            icon.getGlobalVisibleRect(r)
            rootView?.systemGestureExclusionRects = listOf(r)
        }
    }

    private fun screenHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val realSize = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(realSize)
            realSize.y
        }
    }

    // Returns 0.0 (left edge) … 1.0 (right edge). Default is 1.0 (right).
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
            1f  // fresh install → right side
        } else {
            prefs.getFloat(KEY_X_FRACTION, 1f)  // default right
        }
    }

    // fraction 0.0 = left edge, 1.0 = right edge
    private fun applyFraction(fraction: Float, icon: View = iconView ?: return) {
        val screenW = resources.displayMetrics.widthPixels
        val iconW = icon.width.takeIf { it > 0 } ?: return
        val maxTx = (screenW - iconW) / 2f
        icon.translationX = (-maxTx + 2 * maxTx * fraction.coerceIn(0f, 1f))
    }

    private fun currentFraction(): Float {
        val icon = iconView ?: return 1f
        val screenW = resources.displayMetrics.widthPixels
        val iconW = icon.width.takeIf { it > 0 } ?: return 1f
        val maxTx = (screenW - iconW) / 2f
        return if (maxTx > 0f) ((icon.translationX + maxTx) / (2 * maxTx)).coerceIn(0f, 1f) else 1f
    }

    private fun removeOverlayButton() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        iconView = null
    }

    // Called on every display change (rotation, resolution). Recalculates the window's
    // absolute Y so it stays on the nav bar; preserves the icon's relative (fractional)
    // position so it stays at the same side of the screen after rotation.
    private fun repositionOverlay() {
        val root = rootView ?: return
        val icon = iconView ?: return
        val fraction = currentFraction()          // capture before dims change
        val navH = navBarHeight()
        val screenH = screenHeight()
        params.y = screenH - navH
        params.height = navH
        runCatching { windowManager.updateViewLayout(root, params) }
        root.post {
            applyFraction(fraction, icon)
            updateGestureExclusion(icon)
        }
    }

    private inner class DragClickListener(private val icon: View) : View.OnTouchListener {
        private var initialTx = 0f
        private var downRawX = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialTx = icon.translationX
                    downRawX = event.rawX
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    if (abs(dx) > touchSlop) moved = true
                    // Clamp so the icon never leaves the screen edges
                    val screenW = resources.displayMetrics.widthPixels
                    val iconW = icon.width.takeIf { it > 0 } ?: 1
                    val maxTx = (screenW - iconW) / 2f
                    icon.translationX = (initialTx + dx).coerceIn(-maxTx, maxTx)
                    updateGestureExclusion(icon)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) savePosition() else onButtonClicked()
                    return true
                }
            }
            return false
        }
    }

    private fun onButtonClicked() {
        RotationManager.rotateNext(this)
    }

    private fun savePosition() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_X_FRACTION, currentFraction())
            .apply()
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

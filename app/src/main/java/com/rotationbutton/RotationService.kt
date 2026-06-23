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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
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
    private var rootView: FrameLayout? = null
    private var iconView: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var touchSlop = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        startAsForeground()
        addOverlayButton()
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
        removeOverlayButton()
        isRunning = false
        super.onDestroy()
    }

    private fun addOverlayButton() {
        if (rootView != null) return

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val savedX = loadSavedX()
        val navH = navBarHeight()

        // Full-width transparent root covers the entire nav bar row so our window
        // has input priority over TYPE_NAVIGATION_BAR. Touches outside the icon
        // are passed through via TOUCHABLE_INSETS_REGION.
        val root = FrameLayout(this)
        root.setBackgroundColor(0x00000000)

        val icon = LayoutInflater.from(this).inflate(R.layout.overlay_button, root, false)
        root.addView(icon)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            navH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = 0
        }

        icon.setOnTouchListener(DragClickListener(icon))

        // Only the icon rect receives touches; the rest is passed to system nav bar.
        root.viewTreeObserver.addOnComputeInternalInsetsListener { info ->
            val iconRect = Rect()
            icon.getGlobalVisibleRect(iconRect)
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
            info.touchableRegion.set(iconRect)
        }

        runCatching { windowManager.addView(root, params) }.onFailure { stopSelf(); return }

        rootView = root
        iconView = icon

        root.post {
            icon.translationX = savedX.toFloat()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val r = Rect()
                icon.getGlobalVisibleRect(r)
                root.systemGestureExclusionRects = listOf(r)
            }
        }
    }

    private fun loadSavedX(): Int {
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
            prefs.edit().remove(KEY_X).putInt(KEY_VERSION, currentVersion).apply()
            0
        } else {
            prefs.getInt(KEY_X, 0)
        }
    }

    private fun removeOverlayButton() {
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        iconView = null
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
                    icon.translationX = initialTx + dx
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val r = Rect()
                        icon.getGlobalVisibleRect(r)
                        rootView?.systemGestureExclusionRects = listOf(r)
                    }
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
            .putInt(KEY_X, iconView?.translationX?.toInt() ?: 0)
            .apply()
    }

    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) {
            resources.getDimensionPixelSize(id)
        } else {
            (48 * resources.displayMetrics.density).toInt()
        }
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun startAsForeground() {
        createNotificationChannel()

        val stopIntent = Intent(this, RotationService::class.java).apply {
            action = ACTION_STOP
        }
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
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
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
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP = "com.rotationbutton.action.STOP"

        private const val CHANNEL_ID = "rotation_button_channel"
        private const val NOTIF_ID = 1001
        private const val PREFS = "rotation_button_prefs"
        private const val KEY_X = "pos_x"
        private const val KEY_VERSION = "version_code"

        @JvmStatic
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, RotationService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RotationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

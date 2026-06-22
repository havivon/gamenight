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
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * שירות חזית המציג כפתור צף קבוע מעל סרגל הניווט.
 * לחיצה מסובבת את המסך במחזור 0°→90°→180°→270°.
 * ניתן לגרור את הכפתור למיקום מותאם אישית (המיקום נשמר).
 */
class RotationService : Service() {

    private lateinit var windowManager: WindowManager
    private var buttonView: View? = null
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

    // ---------- שכבת-העל ----------

    private fun addOverlayButton() {
        if (buttonView != null) return

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val (posX, posY) = loadPosition()
        val navH = navBarHeight()

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)

        // גובה הכפתור = גובה שורת הניווט; אנו מציבים אותו ממש מעל השורה (y=navH)
        // כך שאירועי המגע מגיעים לשכבת-העל שלנו ולא נבלעים על-ידי חלון הניווט
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            navH,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = posX
            y = posY
        }

        view.setOnTouchListener(DragClickListener())
        runCatching { windowManager.addView(view, params) }.onFailure { stopSelf(); return }
        buttonView = view

        // ב-Android 10+ מסמנים את שטח הכפתור כמוחרג ממחוות הניווט,
        // כך שגרירה לא מתפרשת כ"חזרה" או "דף הבית"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.post {
                view.systemGestureExclusionRects =
                    listOf(android.graphics.Rect(0, 0, view.width, view.height))
            }
        }
    }

    private fun loadPosition(): Pair<Int, Int> {
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
            prefs.edit().remove(KEY_X).remove(KEY_Y).putInt(KEY_VERSION, currentVersion).apply()
            // ברירת מחדל: y = navBarHeight כדי לשבת ממש מעל שורת הניווט
            Pair(0, navBarHeight())
        } else {
            Pair(prefs.getInt(KEY_X, 0), prefs.getInt(KEY_Y, navBarHeight()))
        }
    }

    private fun removeOverlayButton() {
        buttonView?.let {
            runCatching { windowManager.removeView(it) }
        }
        buttonView = null
    }

    /** מטפל בגרירה (drag) מול לחיצה (click) על אותו כפתור */
    private inner class DragClickListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    params.x = initialX + dx
                    // גרביטציה תחתונה: תנועה למעלה מגדילה y; מינימום navBarHeight כדי לא
                    // לצנוח לתוך אזור הניווט (שם touches נבלעים על-ידי חלון המערכת)
                    params.y = (initialY - dy).coerceAtLeast(navBarHeight())
                    windowManager.updateViewLayout(v, params)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePosition()
                    } else {
                        onButtonClicked()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun onButtonClicked() {
        val rotation = RotationManager.rotateNext(this)
        Toast.makeText(
            this,
            getString(R.string.toast_rotated, RotationManager.degreesOf(rotation)),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun savePosition() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
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

    // ---------- שירות חזית ----------

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
            NotificationManager.IMPORTANCE_LOW
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
        private const val KEY_Y = "pos_y"
        private const val KEY_VERSION = "version_code"

        /** האם השירות פעיל כעת (לעדכון מצב הכפתורים ב-MainActivity) */
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

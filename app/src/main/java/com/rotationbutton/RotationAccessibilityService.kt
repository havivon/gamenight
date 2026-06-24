package com.rotationbutton

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.abs

/**
 * שירות נגישות שמארח את כפתור הסיבוב הצף.
 *
 * הטריק המרכזי: חלון מסוג TYPE_ACCESSIBILITY_OVERLAY יושב *מעל* שורת
 * הניווט (TYPE_NAVIGATION_BAR) בשכבות ה-z. רק כך אפשר להציב אלמנט
 * אינטראקטיבי אמיתי שהוא חלק מהשורה — האייקון נמצא פיזית על השורה וגם
 * מקבל מגע. שכבת-על רגילה (TYPE_APPLICATION_OVERLAY) נמצאת מתחת לשורה,
 * ולכן שם המגע נבלע על ידי שורת הניווט.
 *
 * יתרון נוסף: אין צורך בשירות חזית או בהרשאת "הצגה מעל אפליקציות",
 * ולכן נעלמות שתי ההתראות שהופיעו בגישה הקודמת.
 */
class RotationAccessibilityService : AccessibilityService() {

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(DisplayManager::class.java)
        touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        addOverlayButton()
        displayManager.registerDisplayListener(displayListener, null)
        instance = this
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        removeOverlayButton()
        if (instance === this) instance = null
        isRunning = false
    }

    private fun addOverlayButton() {
        if (buttonView != null) return

        val savedFraction = loadSavedFraction()
        val navH = navBarHeight()
        val screenW = resources.displayMetrics.widthPixels
        // אומדן ראשוני של מיקום ה-x עד שנדע את הרוחב האמיתי ב-view.post{}
        val estimatedW = (48 * resources.displayMetrics.density).toInt()
        val initialX = ((screenW - estimatedW) * savedFraction).toInt()

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_button, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            navH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // החלון יושב בדיוק על שורת הניווט (תחתית המסך), בגובה השורה.
            gravity = Gravity.BOTTOM or Gravity.START
            x = initialX
            y = 0
        }

        view.setOnTouchListener(DragClickListener())
        runCatching { windowManager.addView(view, params) }.onFailure { return }
        buttonView = view

        view.post {
            val iconW = view.width.takeIf { it > 0 } ?: return@post
            params.x = ((screenW - iconW) * savedFraction).toInt()
                .coerceIn(0, screenW - iconW)
            runCatching { windowManager.updateViewLayout(view, params) }
            updateGestureExclusion(view)
        }
    }

    // בסיבוב מסך: טוענים מחדש את היחס השמור ומיישמים עם מימדי המסך החדשים.
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

    // מחזיר 0.0 (שמאל) … 1.0 (ימין). ברירת מחדל 1.0 = צד ימין.
    private fun loadSavedFraction(): Float =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_X_FRACTION, 1f)

    private fun navBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (48 * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val PREFS = "rotation_button_prefs"
        private const val KEY_X_FRACTION = "pos_x_fraction"

        @JvmStatic
        var isRunning: Boolean = false
            private set

        private var instance: RotationAccessibilityService? = null

        /** כיבוי הכפתור: השירות מנטרל את עצמו (זמין מ-API 24). */
        fun stop() {
            instance?.disableSelf()
        }
    }
}

package com.rotationbutton

import android.content.Context
import android.provider.Settings
import android.view.Surface

/**
 * אחראי על קריאה ושינוי של סיבוב המסך דרך הגדרות המערכת.
 *
 * הסיבוב הידני (USER_ROTATION) נכנס לתוקף רק כאשר הסיבוב האוטומטי
 * (ACCELEROMETER_ROTATION) כבוי. לכן בכל סיבוב ידני אנו מכבים תחילה את
 * הסיבוב האוטומטי ואז כופים את הזווית הרצויה.
 *
 * שינוי ההגדרות הללו דורש את ההרשאה WRITE_SETTINGS.
 */
object RotationManager {

    /** ערך USER_ROTATION הנוכחי (Surface.ROTATION_0..3) */
    fun currentRotation(context: Context): Int =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.USER_ROTATION,
            Surface.ROTATION_0
        )

    /** האם הסיבוב האוטומטי (חיישן) מופעל */
    fun isAutoRotateEnabled(context: Context): Boolean =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1

    /** הפעלה/כיבוי של הסיבוב האוטומטי */
    fun setAutoRotate(context: Context, enabled: Boolean) {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            if (enabled) 1 else 0
        )
    }

    /**
     * מחליף (toggle) בין מצב רגיל (0°) לבין סיבוב של 90° וכופה אותו ידנית:
     * לחיצה ראשונה → 90°, לחיצה נוספת → חזרה ל-0°.
     * מחזיר את ערך הסיבוב החדש (Surface.ROTATION_*).
     */
    fun rotateNext(context: Context): Int {
        // כאשר המשתמש משבית סיבוב אוטומטי – אנו כופים סיבוב ידני.
        setAutoRotate(context, false)

        val current = currentRotation(context)
        val next = if (current == Surface.ROTATION_0) {
            Surface.ROTATION_90
        } else {
            Surface.ROTATION_0
        }

        Settings.System.putInt(
            context.contentResolver,
            Settings.System.USER_ROTATION,
            next
        )
        return next
    }

    /** המרת ערך Surface.ROTATION_* למעלות לצורך תצוגה */
    fun degreesOf(rotation: Int): Int = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

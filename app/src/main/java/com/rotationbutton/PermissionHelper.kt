package com.rotationbutton

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * עוזר לבדיקת והענקת ההרשאות המיוחדות הנדרשות לאפליקציה:
 * - SYSTEM_ALERT_WINDOW  (ציור שכבת-על)
 * - WRITE_SETTINGS       (שינוי סיבוב המסך)
 *
 * שתי ההרשאות הללו אינן ניתנות דרך דיאלוג רגיל אלא דורשות מעבר
 * למסך הגדרות ייעודי.
 */
object PermissionHelper {

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun canWriteSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    fun allGranted(context: Context): Boolean =
        canDrawOverlays(context) && canWriteSettings(context)

    /** Intent למסך הענקת הרשאת "הצגה מעל אפליקציות אחרות" */
    fun overlayIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /** Intent למסך הענקת הרשאת "שינוי הגדרות מערכת" */
    fun writeSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
}

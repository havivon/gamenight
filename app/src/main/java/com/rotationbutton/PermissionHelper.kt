package com.rotationbutton

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils

/**
 * עוזר לבדיקת והענקת ההרשאות הנדרשות לאפליקציה:
 * - שירות נגישות (כדי לארח את הכפתור הצף מעל שורת הניווט)
 * - WRITE_SETTINGS  (שינוי סיבוב המסך)
 *
 * שתי ההרשאות אינן ניתנות דרך דיאלוג רגיל אלא דורשות מעבר למסך הגדרות ייעודי.
 */
object PermissionHelper {

    fun canWriteSettings(context: Context): Boolean =
        Settings.System.canWrite(context)

    /** האם שירות הנגישות שלנו מופעל בהגדרות המכשיר */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, RotationAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val component = ComponentName.unflattenFromString(splitter.next())
            if (component != null && component == expected) return true
        }
        return false
    }

    fun allGranted(context: Context): Boolean =
        isAccessibilityEnabled(context) && canWriteSettings(context)

    /** Intent למסך הגדרות הנגישות */
    fun accessibilityIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** Intent למסך הענקת הרשאת "שינוי הגדרות מערכת" */
    fun writeSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
}

# RotationButton — כפתור סיבוב מסך

אפליקציית אנדרואיד ב-Kotlin המציגה **כפתור צף קבוע** בתחתית המסך, צמוד לסרגל
הניווט ובאותו צבע וגובה — כך שייראה כחלק ממנו. לחיצה על הכפתור מסובבת את המסך
במחזור: **0° → 90° → 180° → 270° → 0°**.

הכפתור גריר (drag) למיקום מותאם אישית, והממשק כולו בעברית עם תמיכת RTL.

---

## תכונות

- כפתור צף קבוע מעל סרגל הניווט (overlay) באמצעות `WindowManager`.
- צבע וגובה תואמים לסרגל הניווט (גובה נלקח מ-`navigation_bar_height` של המערכת).
- לחיצה מסובבת את המסך באופן מחזורי 0°→90°→180°→270°.
- גרירה למיקום מותאם — המיקום נשמר ב-`SharedPreferences`.
- כאשר הסיבוב האוטומטי מושבת, הכפתור **כופה סיבוב ידני** (`USER_ROTATION`).
- שירות חזית (Foreground Service) עם התראה קבועה וכפתור כיבוי מהיר.
- מסך ראשי עם בקרת הפעלה/כיבוי ובדיקת הרשאות.

---

## דרישות מערכת

| פרמטר | ערך |
|------|-----|
| MinSDK | 26 (Android 8.0) |
| TargetSDK / CompileSDK | 34 (Android 14) |
| שפה | Kotlin |
| JDK לבנייה | 17 ומעלה |

---

## הרשאות

| הרשאה | תפקיד |
|-------|-------|
| `SYSTEM_ALERT_WINDOW` | ציור הכפתור הצף מעל אפליקציות אחרות |
| `WRITE_SETTINGS` | שינוי סיבוב המסך (`USER_ROTATION`, `ACCELEROMETER_ROTATION`) |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | הרצת שירות החזית (Android 14) |
| `POST_NOTIFICATIONS` | הצגת התראת שירות החזית (Android 13+) |

> שתי ההרשאות `SYSTEM_ALERT_WINDOW` ו-`WRITE_SETTINGS` הן הרשאות מיוחדות
> שאינן ניתנות בדיאלוג רגיל — האפליקציה מפנה למסך ההגדרות הייעודי של כל אחת.

---

## מבנה הפרויקט

```
.
├── build.gradle                 # הגדרת פלאגינים ברמת השורש
├── settings.gradle              # מאגרים והכללת מודול app
├── gradle.properties
├── gradlew / gradlew.bat        # Gradle Wrapper
├── gradle/wrapper/…             # gradle-wrapper.jar + properties (Gradle 8.7)
└── app/
    ├── build.gradle             # תצורת המודול (SDK, תלויות)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/rotationbutton/
        │   ├── MainActivity.kt        # מסך ראשי: הפעלה/כיבוי + הרשאות
        │   ├── RotationService.kt     # שירות חזית + הכפתור הצף (overlay)
        │   ├── RotationManager.kt      # לוגיקת סיבוב המסך
        │   └── PermissionHelper.kt     # בדיקת/הענקת הרשאות
        └── res/
            ├── layout/activity_main.xml
            ├── layout/overlay_button.xml
            ├── drawable/…             # אייקונים וקטוריים ורקעים
            ├── mipmap-anydpi-v26/…    # אייקון משגר אדפטיבי
            └── values/                # strings (עברית), colors, themes
```

---

## בנייה

### דרך שורת הפקודה

נדרש Android SDK מותקן עם הפניה אליו דרך `local.properties` או משתנה הסביבה
`ANDROID_HOME`.

1. צור קובץ `local.properties` בשורש הפרויקט (אם אינו קיים):

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

2. בנה את ה-APK:

   ```bash
   ./gradlew assembleDebug
   ```

   ה-APK ייווצר בנתיב:
   `app/build/outputs/apk/debug/app-debug.apk`

3. התקנה למכשיר מחובר:

   ```bash
   ./gradlew installDebug
   ```

### דרך Android Studio

פתחו את תיקיית הפרויקט ב-Android Studio (Hedgehog ומעלה), המתינו לסנכרון
Gradle, ולחצו **Run**.

---

## שימוש

1. פתחו את האפליקציה והעניקו את שתי ההרשאות במסך הראשי:
   - **הצגה מעל אפליקציות אחרות**
   - **שינוי הגדרות מערכת**
2. לחצו על **הפעלת הכפתור** — כפתור צף יופיע בתחתית המסך.
3. לחיצה על הכפתור מסובבת את המסך במחזור 0°→90°→180°→270°.
4. גרירה ארוכה מזיזה את הכפתור למיקום הרצוי (המיקום נשמר).
5. לכיבוי: כפתור **כיבוי הכפתור** במסך הראשי, או פעולת **כיבוי** בהתראה.

---

## הערות טכניות

- כפיית הסיבוב נעשית על-ידי כיבוי `ACCELEROMETER_ROTATION` והגדרת
  `USER_ROTATION` לערך `Surface.ROTATION_0..270`. הסיבוב הידני נכנס לתוקף רק
  כשהסיבוב האוטומטי כבוי, ולכן השירות מכבה אותו אוטומטית בכל לחיצה.
- הכפתור משתמש בסוג חלון `TYPE_APPLICATION_OVERLAY` (זמין מ-API 26).
- בחלק מהיצרנים (למשל סמסונג/שיאומי) ייתכן שיידרש אישור הרשאות נוסף
  או שהתנהגות `USER_ROTATION` תהיה מוגבלת בקושחות מסוימות.
- שירות החזית מוגדר כ-`specialUse` בהתאם לדרישות Android 14.

package com.rotationbutton

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.rotationbutton.databinding.ActivityMainBinding

/**
 * מסך הבית: הפעלה/כיבוי של הכפתור הצף ובדיקת ההרשאות הנדרשות.
 *
 * הכפתור הצף מתארח בשירות נגישות (TYPE_ACCESSIBILITY_OVERLAY) כדי שיוכל
 * לשבת מעל שורת הניווט. לכן ה"הפעלה" משמעה הפעלת שירות הנגישות בהגדרות,
 * וה"כיבוי" מנטרל את השירות.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // חזרה ממסך הגדרות הרשאה – פשוט מרעננים את התצוגה
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener {
            settingsLauncher.launch(PermissionHelper.accessibilityIntent())
        }

        binding.btnWriteSettingsPermission.setOnClickListener {
            settingsLauncher.launch(PermissionHelper.writeSettingsIntent(this))
        }

        binding.btnStart.setOnClickListener { startButton() }
        binding.btnStop.setOnClickListener { stopButton() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun startButton() {
        if (!PermissionHelper.canWriteSettings(this)) {
            Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_SHORT).show()
            refreshUi()
            return
        }
        // הפעלת הכפתור = הפעלת שירות הנגישות בהגדרות.
        settingsLauncher.launch(PermissionHelper.accessibilityIntent())
    }

    private fun stopButton() {
        RotationAccessibilityService.stop()
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ refreshUi() }, 300)
    }

    /** מעדכן את חיווי ההרשאות ומצב הכפתורים */
    private fun refreshUi() {
        val accessibility = PermissionHelper.isAccessibilityEnabled(this)
        val write = PermissionHelper.canWriteSettings(this)

        binding.statusOverlay.text = getString(
            R.string.status_accessibility,
            granted(accessibility)
        )
        binding.statusWriteSettings.text = getString(
            R.string.status_write_settings,
            granted(write)
        )

        binding.btnOverlayPermission.isEnabled = !accessibility
        binding.btnWriteSettingsPermission.isEnabled = !write

        val running = RotationAccessibilityService.isRunning
        binding.btnStart.isEnabled = write && !running
        binding.btnStop.isEnabled = running

        binding.statusService.text = getString(
            R.string.status_service,
            getString(if (running) R.string.state_on else R.string.state_off)
        )
    }

    private fun granted(value: Boolean): String =
        getString(if (value) R.string.state_granted else R.string.state_denied)
}

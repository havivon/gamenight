package com.rotationbutton

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rotationbutton.databinding.ActivityMainBinding

/**
 * מסך הבית: הפעלה/כיבוי של הכפתור הצף ובדיקת ההרשאות הנדרשות.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // חזרה ממסך הגדרות הרשאה – פשוט מרעננים את התצוגה
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshUi()
        }

    private val notificationsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOverlayPermission.setOnClickListener {
            settingsLauncher.launch(PermissionHelper.overlayIntent(this))
        }

        binding.btnWriteSettingsPermission.setOnClickListener {
            settingsLauncher.launch(PermissionHelper.writeSettingsIntent(this))
        }

        binding.btnStart.setOnClickListener { startButtonService() }
        binding.btnStop.setOnClickListener { stopButtonService() }

        requestNotificationsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun startButtonService() {
        if (!PermissionHelper.allGranted(this)) {
            Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_SHORT).show()
            refreshUi()
            return
        }
        RotationService.start(this)
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show()
        // השהיה קצרה כדי לאפשר לשירות לעדכן את מצבו לפני הרענון
        binding.root.postDelayed({ refreshUi() }, 300)
    }

    private fun stopButtonService() {
        RotationService.stop(this)
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        binding.root.postDelayed({ refreshUi() }, 300)
    }

    private fun requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /** מעדכן את חיווי ההרשאות ומצב הכפתורים */
    private fun refreshUi() {
        val overlay = PermissionHelper.canDrawOverlays(this)
        val write = PermissionHelper.canWriteSettings(this)

        binding.statusOverlay.text = getString(
            R.string.status_overlay,
            granted(overlay)
        )
        binding.statusWriteSettings.text = getString(
            R.string.status_write_settings,
            granted(write)
        )

        binding.btnOverlayPermission.isEnabled = !overlay
        binding.btnWriteSettingsPermission.isEnabled = !write

        val running = RotationService.isRunning
        binding.btnStart.isEnabled = overlay && write && !running
        binding.btnStop.isEnabled = running

        binding.statusService.text = getString(
            R.string.status_service,
            getString(if (running) R.string.state_on else R.string.state_off)
        )
    }

    private fun granted(value: Boolean): String =
        getString(if (value) R.string.state_granted else R.string.state_denied)
}

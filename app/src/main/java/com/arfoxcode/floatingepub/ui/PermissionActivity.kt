package com.arfoxcode.floatingepub.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arfoxcode.floatingepub.R
import com.arfoxcode.floatingepub.data.AppThemePrefs
import com.arfoxcode.floatingepub.overlay.OverlayService
import com.arfoxcode.floatingepub.util.BatteryOptimizationHelper
import com.arfoxcode.floatingepub.util.DeviceAdminHelper
import com.arfoxcode.floatingepub.util.OverlayPermissionHelper
import com.arfoxcode.floatingepub.util.StoragePermissionHelper
import com.arfoxcode.floatingepub.util.VendorBatteryHelper

class PermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        applyTheme()
        setupVendorGuide()

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnEnableOverlay).setOnClickListener {
            if (OverlayPermissionHelper.hasOverlayPermission(this)) {
                startBubbleService()
            } else {
                OverlayPermissionHelper.requestOverlayPermission(this)
            }
        }

        findViewById<Button>(R.id.btnEnableStorage).setOnClickListener {
            if (StoragePermissionHelper.hasStoragePermission(this)) {
                Toast.makeText(this, "Storage permission already granted", Toast.LENGTH_SHORT).show()
            } else {
                StoragePermissionHelper.requestStoragePermission(this)
            }
        }

        findViewById<Button>(R.id.btnDisableBatteryOptimization).setOnClickListener {
            if (BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) {
                Toast.makeText(this, "Already running without battery optimization", Toast.LENGTH_SHORT).show()
            } else {
                BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(this)
            }
        }

        findViewById<Button>(R.id.btnDeviceAdmin).setOnClickListener {
            if (DeviceAdminHelper.isAdminActive(this)) {
                Toast.makeText(this, "Device admin already enabled", Toast.LENGTH_SHORT).show()
            } else {
                DeviceAdminHelper.requestAdmin(this)
            }
        }
    }

    private fun setupVendorGuide() {
        val guide = VendorBatteryHelper.getGuideForCurrentDevice()
        findViewById<TextView>(R.id.txtVendorName).text = guide.manufacturerLabel
        findViewById<TextView>(R.id.txtVendorInstructions).text = guide.instructions
        findViewById<Button>(R.id.btnOpenVendorSettings).setOnClickListener {
            VendorBatteryHelper.openVendorSettings(this)
        }
    }

    private fun applyTheme() {
        val prefs = AppThemePrefs(this)
        val bgColor = prefs.effectiveBackgroundColor(this)
        val textColor = prefs.effectiveTextColor(this)
        val secondaryColor = prefs.effectiveSecondaryTextColor(this)

        findViewById<View>(R.id.rootLayout).setBackgroundColor(bgColor)
        findViewById<TextView>(R.id.txtTitle).setTextColor(textColor)
        findViewById<ImageView>(R.id.btnBack).imageTintList = ColorStateList.valueOf(textColor)

        findViewById<TextView>(R.id.txtVendorSectionLabel).setTextColor(secondaryColor)
        findViewById<TextView>(R.id.txtVendorIntro).setTextColor(secondaryColor)
        findViewById<TextView>(R.id.txtVendorName).setTextColor(textColor)
        findViewById<TextView>(R.id.txtVendorInstructions).setTextColor(secondaryColor)
    }

    private fun startBubbleService() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_BUBBLE
        }
        startService(intent)
        Toast.makeText(this, "Floating bubble active", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == StoragePermissionHelper.REQUEST_CODE_STORAGE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            val message = if (granted) "Storage permission granted" else "Storage permission denied"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Kept because OverlayPermissionHelper still uses startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OverlayPermissionHelper.REQUEST_CODE_OVERLAY) {
            if (OverlayPermissionHelper.hasOverlayPermission(this)) {
                startBubbleService()
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

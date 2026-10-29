package com.arfoxcode.floatingepub.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Helper untuk mengecek dan meminta izin "Display over other apps"
 * (SYSTEM_ALERT_WINDOW), yang wajib ada sebelum OverlayService dijalankan.
 */
object OverlayPermissionHelper {

    const val REQUEST_CODE_OVERLAY = 5501

    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // di bawah Marshmallow, izin ini otomatis granted
        }
    }

    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivityForResult(intent, REQUEST_CODE_OVERLAY)
    }
}

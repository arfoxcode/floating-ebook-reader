package com.arfoxcode.floatingepub.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Helper untuk cek & minta izin akses penyimpanan.
 *
 * Android 11+ (R) pakai MANAGE_EXTERNAL_STORAGE (all-files access, lewat halaman Settings khusus).
 * Android 10 ke bawah (sampai API 32 sebelum dibatasi) pakai izin runtime READ_EXTERNAL_STORAGE biasa.
 */
object StoragePermissionHelper {

    const val REQUEST_CODE_STORAGE = 5502

    fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestStoragePermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            } catch (e: Exception) {
                // Fallback kalau device tidak punya halaman izin per-app (jarang terjadi)
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE
            )
        }
    }
}

package com.arfoxcode.floatingepub.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Admin Receiver minimal - tidak meminta kebijakan apa pun (tidak lock screen,
 * tidak wipe data, dst). Cuma dipakai supaya app terdaftar sebagai Device Admin sesuai
 * permintaan, tanpa fungsi tambahan yang tidak dipakai.
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}

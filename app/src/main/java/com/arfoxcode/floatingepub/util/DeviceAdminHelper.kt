package com.arfoxcode.floatingepub.util

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.arfoxcode.floatingepub.admin.AppDeviceAdminReceiver

object DeviceAdminHelper {

    private fun adminComponent(context: Context) =
        ComponentName(context, AppDeviceAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent(context))
    }

    fun requestAdmin(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(activity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allows the app to stay registered as a device admin."
            )
        }
        activity.startActivity(intent)
    }
}

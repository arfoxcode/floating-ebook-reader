package com.arfoxcode.floatingepub.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Banyak HP Indonesia (Xiaomi/MIUI, Oppo/Realme/ColorOS, Vivo/FuntouchOS, dll) punya
 * "battery saver" / autostart manager sendiri DI LUAR Android standar, yang bisa tetap
 * mematikan foreground service walau app sudah di-whitelist lewat
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS. Tidak ada API resmi untuk itu - satu-satunya
 * jalan adalah arahkan user ke halaman pengaturan spesifik merek tersebut secara manual.
 */
object VendorBatteryHelper {

    data class VendorGuide(
        val manufacturerLabel: String,
        val instructions: String,
        val intent: Intent?
    )

    fun getGuideForCurrentDevice(): VendorGuide {
        val manufacturer = Build.MANUFACTURER.lowercase()

        return when {
            manufacturer.contains("xiaomi") -> VendorGuide(
                "Xiaomi / Redmi / POCO (MIUI)",
                "Open Security app → Manage apps → find this app → set to \"No restrictions\", " +
                    "then also enable Autostart for this app.",
                buildComponentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            manufacturer.contains("oppo") -> VendorGuide(
                "Oppo (ColorOS)",
                "Open Settings → Battery → App battery management → find this app → " +
                    "set to \"Allow background activity\".",
                buildComponentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            )
            manufacturer.contains("realme") -> VendorGuide(
                "Realme (realme UI)",
                "Open Settings → Battery → App battery management → find this app → " +
                    "set to \"Allow background activity\".",
                buildComponentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            )
            manufacturer.contains("vivo") -> VendorGuide(
                "Vivo (Funtouch OS / OriginOS)",
                "Open i Manager → Battery → High background power consumption → " +
                    "whitelist this app so it's excluded from auto-cleanup.",
                buildComponentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> VendorGuide(
                "Huawei / Honor (EMUI)",
                "Open Phone Manager → App launch → find this app → switch to Manual, " +
                    "then enable all three toggles (auto-launch, secondary launch, run in background).",
                buildComponentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
            manufacturer.contains("samsung") -> VendorGuide(
                "Samsung (One UI)",
                "Open Settings → Apps → find this app → Battery → set to \"Unrestricted\".",
                null
            )
            manufacturer.contains("oneplus") -> VendorGuide(
                "OnePlus (OxygenOS)",
                "Open Settings → Battery → Battery optimization → find this app → " +
                    "set to \"Don't optimize\".",
                null
            )
            manufacturer.contains("asus") -> VendorGuide(
                "Asus (ZenUI)",
                "Open Mobile Manager → Power Master → find this app in the auto-start list → enable it.",
                null
            )
            else -> VendorGuide(
                Build.MANUFACTURER.ifBlank { "This device" },
                "Open your phone's battery or app settings and allow this app to run in " +
                    "the background without restrictions.",
                null
            )
        }
    }

    private fun buildComponentIntent(packageName: String, className: String): Intent =
        Intent().apply { component = ComponentName(packageName, className) }

    /** Coba buka pengaturan spesifik vendor; kalau tidak ada di device ini, fallback ke halaman App Info */
    fun openVendorSettings(context: Context) {
        val intent = getGuideForCurrentDevice().intent
        if (intent != null) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                // Intent ini tidak ada di ROM/versi device ini - lanjut ke fallback di bawah
            }
        }
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (e: Exception) {
            // Tidak ada halaman App Info yang bisa dibuka - abaikan, biarkan user cari manual
        }
    }
}

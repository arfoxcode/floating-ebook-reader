package com.arfoxcode.floatingepub.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Preferensi tampilan aplikasi (bukan tema baca EPUB - itu ada di ReaderPrefs).
 * customBackgroundColor/customItemColor null berarti pakai warna bawaan sesuai
 * mode terang/gelap yang sedang aktif.
 */
class AppThemePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: AppThemeMode
        get() = runCatching { AppThemeMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(AppThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var customBackgroundColor: String?
        get() = prefs.getString(KEY_BG, null)
        set(value) = prefs.edit().putString(KEY_BG, value).apply()

    var customItemColor: String?
        get() = prefs.getString(KEY_ITEM, null)
        set(value) = prefs.edit().putString(KEY_ITEM, value).apply()

    /** Path absolut file gambar latar di internal storage; null = pakai warna solid biasa */
    var backgroundImagePath: String?
        get() = prefs.getString(KEY_BG_IMAGE, null)
        set(value) = prefs.edit().putString(KEY_BG_IMAGE, value).apply()

    fun resetColors() {
        prefs.edit().remove(KEY_BG).remove(KEY_ITEM).apply()
    }

    fun clearBackgroundImage() {
        prefs.edit().remove(KEY_BG_IMAGE).apply()
    }

    /** Apakah mode gelap sedang aktif, dengan mempertimbangkan pilihan SYSTEM */
    fun isDarkActive(context: Context): Boolean = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun effectiveBackgroundColor(context: Context): Int {
        val hex = customBackgroundColor ?: if (isDarkActive(context)) DARK_BG else LIGHT_BG
        return Color.parseColor(hex)
    }

    fun effectiveItemColor(context: Context): Int {
        val hex = customItemColor ?: if (isDarkActive(context)) DARK_ITEM else LIGHT_ITEM
        return Color.parseColor(hex)
    }

    fun effectiveTextColor(context: Context): Int =
        Color.parseColor(if (isDarkActive(context)) "#FFFFFF" else "#1A1A1A")

    fun effectiveSecondaryTextColor(context: Context): Int =
        Color.parseColor(if (isDarkActive(context)) "#AAAAAA" else "#666666")

    companion object {
        private const val PREFS_NAME = "app_theme_prefs"
        private const val KEY_MODE = "theme_mode"
        private const val KEY_BG = "custom_bg_color"
        private const val KEY_ITEM = "custom_item_color"
        private const val KEY_BG_IMAGE = "custom_bg_image_path"

        const val DARK_BG = "#14141C"
        const val DARK_ITEM = "#1E1E2A"
        const val LIGHT_BG = "#F2F2F5"
        const val LIGHT_ITEM = "#FFFFFF"

        val BACKGROUND_SWATCHES = listOf(
            "#14141C", "#1A1A24", "#0D1B2A", "#1B1B1B", "#F2F2F5", "#FFF8E7", "#FFFFFF"
        )
        val ITEM_SWATCHES = listOf(
            "#1E1E2A", "#22283A", "#12263A", "#2A2A2A", "#FFFFFF", "#FFF3D6", "#E8F0FE"
        )
    }
}

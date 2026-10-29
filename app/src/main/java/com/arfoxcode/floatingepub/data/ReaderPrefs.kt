package com.arfoxcode.floatingepub.data

import android.content.Context

/** Warna latar & teks untuk tiap mode baca */
enum class ReadingTheme(val bgColor: String, val textColor: String) {
    DAY("#FFFFFF", "#1A1A1A"),
    NIGHT("#121212", "#E0E0E0"),
    SEPIA("#F4ECD8", "#5B4636")
}

/** cssValue kosong = pakai font bawaan EPUB, tidak di-override */
enum class ReaderFont(val cssValue: String, val label: String) {
    DEFAULT("", "Bawaan EPUB"),
    SANS("sans-serif", "Sans-serif"),
    SERIF("serif", "Serif"),
    MONO("monospace", "Monospace")
}

/** Preferensi ringan (tema/font/ukuran), pakai SharedPreferences - tidak butuh database */
class ReaderPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var theme: ReadingTheme
        get() = runCatching { ReadingTheme.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ReadingTheme.NIGHT)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var font: ReaderFont
        get() = runCatching { ReaderFont.valueOf(prefs.getString(KEY_FONT, null) ?: "") }
            .getOrDefault(ReaderFont.DEFAULT)
        set(value) = prefs.edit().putString(KEY_FONT, value.name).apply()

    var fontSizePx: Int
        get() = prefs.getInt(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
        set(value) = prefs.edit().putInt(KEY_FONT_SIZE, value.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)).apply()

    // -1 (belum pernah diset) -> pemanggil pakai ukuran default berdasarkan layar
    var windowWidth: Int
        get() = prefs.getInt(KEY_WIDTH, -1)
        set(value) = prefs.edit().putInt(KEY_WIDTH, value).apply()

    var windowHeight: Int
        get() = prefs.getInt(KEY_HEIGHT, -1)
        set(value) = prefs.edit().putInt(KEY_HEIGHT, value).apply()

    // Int.MIN_VALUE (belum pernah diset) -> pemanggil pakai posisi tengah layar
    var windowX: Int
        get() = prefs.getInt(KEY_X, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_X, value).apply()

    var windowY: Int
        get() = prefs.getInt(KEY_Y, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_Y, value).apply()

    /** Ingat apakah user terakhir kali baca dalam mode fullscreen (bukan floating) */
    var isFullscreen: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, false)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    // Posisi terakhir bubble floating digeser (Int.MIN_VALUE = belum pernah, pakai default)
    var bubbleX: Int
        get() = prefs.getInt(KEY_BUBBLE_X, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_X, value).apply()

    var bubbleY: Int
        get() = prefs.getInt(KEY_BUBBLE_Y, Int.MIN_VALUE)
        set(value) = prefs.edit().putInt(KEY_BUBBLE_Y, value).apply()

    companion object {
        private const val PREFS_NAME = "reader_prefs"
        private const val KEY_THEME = "theme"
        private const val KEY_FONT = "font"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_WIDTH = "window_width"
        private const val KEY_HEIGHT = "window_height"
        private const val KEY_X = "window_x"
        private const val KEY_Y = "window_y"
        private const val KEY_FULLSCREEN = "is_fullscreen"
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"

        const val DEFAULT_FONT_SIZE = 18
        const val MIN_FONT_SIZE = 12
        const val MAX_FONT_SIZE = 32
        const val FONT_SIZE_STEP = 2
    }
}

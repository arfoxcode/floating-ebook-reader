package com.arfoxcode.floatingepub.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arfoxcode.floatingepub.R
import com.arfoxcode.floatingepub.data.AppThemeMode
import com.arfoxcode.floatingepub.data.AppThemePrefs
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppThemePrefs

    // Dikembalikan ke MainActivity supaya tahu perlu recreate() atau tidak
    private var themeChanged = false

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { saveBackgroundImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = AppThemePrefs(this)
        applyTheme()

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finishAndReturn() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAndReturn()
        })

        setupModeButtons()
        refreshBackgroundSwatches()
        refreshItemSwatches()
        refreshBackgroundImagePreview()

        findViewById<Button>(R.id.btnResetColors).setOnClickListener {
            prefs.resetColors()
            themeChanged = true
            refreshBackgroundSwatches()
            refreshItemSwatches()
            applyTheme()
        }

        findViewById<Button>(R.id.btnPickBackgroundImage).setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.btnRemoveBackgroundImage).setOnClickListener {
            prefs.backgroundImagePath?.let { File(it).delete() }
            prefs.clearBackgroundImage()
            themeChanged = true
            refreshBackgroundImagePreview()
        }
    }

    private fun applyTheme() {
        val bgColor = prefs.effectiveBackgroundColor(this)
        val textColor = prefs.effectiveTextColor(this)

        findViewById<View>(R.id.rootLayout).setBackgroundColor(bgColor)
        findViewById<android.widget.TextView>(R.id.txtTitle).setTextColor(textColor)
        findViewById<ImageView>(R.id.btnBack).imageTintList = ColorStateList.valueOf(textColor)
    }

    private fun saveBackgroundImage(uri: Uri) {
        try {
            val destFile = File(filesDir, "custom_background.img")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Tidak bisa membaca gambar yang dipilih")

            prefs.backgroundImagePath = destFile.absolutePath
            themeChanged = true
            refreshBackgroundImagePreview()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshBackgroundImagePreview() {
        val preview = findViewById<ImageView>(R.id.imgBackgroundPreview)
        val path = prefs.backgroundImagePath
        if (path != null && File(path).exists()) {
            preview.visibility = View.VISIBLE
            preview.setImageDrawable(android.graphics.drawable.Drawable.createFromPath(path))
        } else {
            preview.visibility = View.GONE
            preview.setImageDrawable(null)
        }
    }

    private fun setupModeButtons() {
        val buttons = mapOf(
            AppThemeMode.SYSTEM to findViewById<Button>(R.id.btnModeSystem),
            AppThemeMode.LIGHT to findViewById(R.id.btnModeLight),
            AppThemeMode.DARK to findViewById(R.id.btnModeDark)
        )

        fun refreshSelection() {
            buttons.forEach { (mode, btn) ->
                val selected = mode == prefs.themeMode
                btn.backgroundTintList = ColorStateList.valueOf(
                    Color.parseColor(if (selected) "#4A90E2" else "#2D2D3A")
                )
            }
        }
        refreshSelection()

        buttons.forEach { (mode, btn) ->
            btn.setOnClickListener {
                prefs.themeMode = mode
                themeChanged = true
                refreshSelection()
                applyTheme() // live preview: langsung terlihat begitu mode diganti
            }
        }
    }

    private fun refreshBackgroundSwatches() {
        setupSwatches(
            container = findViewById(R.id.backgroundSwatchContainer),
            colors = AppThemePrefs.BACKGROUND_SWATCHES,
            currentSelection = prefs.customBackgroundColor
        ) { hex ->
            prefs.customBackgroundColor = hex
            themeChanged = true
            refreshBackgroundSwatches()
            applyTheme()
        }
    }

    private fun refreshItemSwatches() {
        setupSwatches(
            container = findViewById(R.id.itemSwatchContainer),
            colors = AppThemePrefs.ITEM_SWATCHES,
            currentSelection = prefs.customItemColor
        ) { hex ->
            prefs.customItemColor = hex
            themeChanged = true
            refreshItemSwatches()
        }
    }

    private fun setupSwatches(
        container: LinearLayout,
        colors: List<String>,
        currentSelection: String?,
        onSelect: (String) -> Unit
    ) {
        container.removeAllViews()
        val scale = resources.displayMetrics.density
        val sizePx = (40 * scale).toInt()
        val marginPx = (6 * scale).toInt()

        colors.forEach { hex ->
            val swatch = android.view.View(this)
            val params = LinearLayout.LayoutParams(sizePx, sizePx)
            params.setMargins(marginPx, marginPx, marginPx, marginPx)
            swatch.layoutParams = params

            val isSelected = hex.equals(currentSelection, ignoreCase = true)
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(hex))
                setStroke(
                    ((if (isSelected) 3 else 1) * scale).toInt(),
                    Color.parseColor(if (isSelected) "#4A90E2" else "#555555")
                )
            }
            swatch.setOnClickListener { onSelect(hex) }
            container.addView(swatch)
        }
    }

    private fun finishAndReturn() {
        setResult(if (themeChanged) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}

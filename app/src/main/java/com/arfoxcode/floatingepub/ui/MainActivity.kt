package com.arfoxcode.floatingepub.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arfoxcode.floatingepub.R
import com.arfoxcode.floatingepub.data.AppThemeMode
import com.arfoxcode.floatingepub.data.AppThemePrefs
import com.arfoxcode.floatingepub.data.Book
import com.arfoxcode.floatingepub.data.BookRepository
import com.arfoxcode.floatingepub.overlay.OverlayService
import com.arfoxcode.floatingepub.util.OverlayPermissionHelper
import com.arfoxcode.floatingepub.util.StoragePermissionHelper
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var repository: BookRepository
    private lateinit var themePrefs: AppThemePrefs
    private lateinit var adapter: BookAdapter
    private lateinit var drawerLayout: DrawerLayout

    // Slight transparency for book list items, so a custom background image can show through
    private val ITEM_ALPHA = 222 // out of 255 (~87% opaque)

    private val pickEpubLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importEpubFromUri(it) }
    }

    // Recreate the activity when coming back from Settings if the theme changed
    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        themePrefs = AppThemePrefs(this)
        AppCompatDelegate.setDefaultNightMode(
            when (themePrefs.themeMode) {
                AppThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                AppThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )

        setContentView(R.layout.activity_main)

        repository = BookRepository(this)
        drawerLayout = findViewById(R.id.drawerLayout)

        applyAppColors()
        applyDrawerColors()

        findViewById<View>(R.id.btnMenu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<FloatingActionButton>(R.id.fabImport).setOnClickListener {
            ensureStoragePermissionThenPickFile()
        }

        findViewById<View>(R.id.rowHome).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        findViewById<View>(R.id.rowSettings).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.rowPermission).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, PermissionActivity::class.java))
        }
        findViewById<View>(R.id.rowGithub).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/arfoxcode")))
        }

        // Ask for storage permission automatically on first launch
        if (!StoragePermissionHelper.hasStoragePermission(this)) {
            StoragePermissionHelper.requestStoragePermission(this)
        }

        observeBooks()
    }

    /** Apply background & item colors from AppThemePrefs (mode + custom colors/background image) */
    private fun applyAppColors() {
        val bgColor = themePrefs.effectiveBackgroundColor(this)
        val itemColor = ColorUtils.setAlphaComponent(themePrefs.effectiveItemColor(this), ITEM_ALPHA)
        val textColor = themePrefs.effectiveTextColor(this)
        val secondaryColor = themePrefs.effectiveSecondaryTextColor(this)

        val rootContent = findViewById<View>(R.id.rootContent)
        val imagePath = themePrefs.backgroundImagePath
        if (imagePath != null && File(imagePath).exists()) {
            val metrics = resources.displayMetrics
            val bitmap = decodeSampledBitmap(imagePath, metrics.widthPixels, metrics.heightPixels)
            if (bitmap != null) {
                rootContent.background = BitmapDrawable(resources, bitmap)
            } else {
                rootContent.setBackgroundColor(bgColor)
            }
        } else {
            rootContent.setBackgroundColor(bgColor)
        }

        findViewById<TextView>(R.id.txtAppTitle).setTextColor(textColor)
        findViewById<ImageView>(R.id.btnMenu).imageTintList = android.content.res.ColorStateList.valueOf(textColor)

        adapter = BookAdapter(itemColor, textColor, secondaryColor) { book -> openBookInOverlay(book) }
        findViewById<RecyclerView>(R.id.recyclerBooks).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    /** Make the hamburger drawer follow the same light/dark mode instead of being hardcoded dark */
    private fun applyDrawerColors() {
        val isDark = themePrefs.isDarkActive(this)
        val drawerBg = if (isDark) Color.parseColor("#1A1A24") else Color.parseColor("#FFFFFF")
        val textColor = themePrefs.effectiveTextColor(this)
        val secondaryColor = themePrefs.effectiveSecondaryTextColor(this)

        findViewById<View>(R.id.drawerContent).setBackgroundColor(drawerBg)

        listOf(R.id.rowHome, R.id.rowSettings, R.id.rowPermission).forEach { rowId ->
            val row = findViewById<LinearLayout>(rowId)
            (row.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(textColor)
            (row.getChildAt(1) as? TextView)?.setTextColor(textColor)
        }

        val githubRow = findViewById<LinearLayout>(R.id.rowGithub)
        (githubRow.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(secondaryColor)
        (githubRow.getChildAt(1) as? TextView)?.setTextColor(secondaryColor)
    }

    /** Downsample large background images so they don't cause memory issues */
    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, boundsOptions)

        var inSampleSize = 1
        if (boundsOptions.outHeight > reqHeight || boundsOptions.outWidth > reqWidth) {
            val halfHeight = boundsOptions.outHeight / 2
            val halfWidth = boundsOptions.outWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val finalOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return runCatching { BitmapFactory.decodeFile(path, finalOptions) }.getOrNull()
    }

    // ---------- Book list (Book + progress from ReadingHistory) ----------

    private fun observeBooks() {
        lifecycleScope.launch {
            repository.refreshAll() // initial load - StateFlow doesn't auto-query like Room did

            repository.getAllBooks()
                .combine(repository.getRecentHistory()) { books, histories ->
                    val progressByBookId = histories.associateBy { it.bookId }
                    books.map { book ->
                        val percent = progressByBookId[book.id]
                            ?.overallProgressPercent
                            ?.times(100)
                            ?.toInt() ?: 0
                        BookUiModel(book, percent)
                    }
                }
                .collect { adapter.submitList(it) }
        }
    }

    // ---------- Import ----------

    private fun ensureStoragePermissionThenPickFile() {
        if (StoragePermissionHelper.hasStoragePermission(this)) {
            pickEpubLauncher.launch(arrayOf("application/epub+zip", "*/*"))
        } else {
            Toast.makeText(this, "Enable storage permission first", Toast.LENGTH_SHORT).show()
            StoragePermissionHelper.requestStoragePermission(this)
        }
    }

    private fun importEpubFromUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                val tempFile = copyUriToTempFile(uri)
                val book = repository.importBook(tempFile)
                tempFile.delete()
                Toast.makeText(this@MainActivity, "\"${book.title}\" added", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun copyUriToTempFile(uri: Uri): File {
        val tempFile = File(cacheDir, "import_${System.currentTimeMillis()}.epub")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read the selected file")
        return tempFile
    }

    // ---------- Overlay (triggered contextually when tapping a book) ----------

    private fun openBookInOverlay(book: Book) {
        if (!OverlayPermissionHelper.hasOverlayPermission(this)) {
            Toast.makeText(this, "Enable overlay permission first from the Permissions menu", Toast.LENGTH_LONG).show()
            OverlayPermissionHelper.requestOverlayPermission(this)
            return
        }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_EXPAND_READER
            putExtra(OverlayService.EXTRA_BOOK_ID, book.id)
        }
        startService(intent)
        moveTaskToBack(true) // minimize so the overlay is visible
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
                Toast.makeText(this, "Overlay permission granted, try opening the book again", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

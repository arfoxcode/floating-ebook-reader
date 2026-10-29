package com.arfoxcode.floatingepub.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.*
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.arfoxcode.floatingepub.R
import com.arfoxcode.floatingepub.data.AppThemePrefs
import com.arfoxcode.floatingepub.data.Bookmark
import com.arfoxcode.floatingepub.data.BookRepository
import com.arfoxcode.floatingepub.data.ReaderFont
import com.arfoxcode.floatingepub.data.ReaderPrefs
import com.arfoxcode.floatingepub.data.ReadingTheme
import com.arfoxcode.floatingepub.epub.EpubRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service yang menampilkan bubble floating di atas aplikasi lain.
 * Saat bubble di-tap, akan expand menjadi jendela baca (reader window).
 *
 * Alur:
 *  - START_BUBBLE  -> tampilkan bubble kecil
 *  - EXPAND_READER -> ganti bubble jadi jendela baca penuh (dengan bookId)
 *  - COLLAPSE      -> kembali ke bubble kecil
 *  - STOP          -> hentikan service & hapus semua view
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "floating_book_channel"
        const val NOTIF_ID = 1001

        const val ACTION_START_BUBBLE = "ACTION_START_BUBBLE"
        const val ACTION_EXPAND_READER = "ACTION_EXPAND_READER"
        const val ACTION_COLLAPSE = "ACTION_COLLAPSE"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_BOOK_ID = "EXTRA_BOOK_ID"

        // Ambang batas gesture swipe ganti chapter
        private const val SWIPE_MIN_DISTANCE = 120
        private const val SWIPE_MIN_VELOCITY = 200
    }

    private lateinit var windowManager: WindowManager

    // View yang sedang aktif ditampilkan: bubble ATAU reader (tidak pernah dua-duanya)
    private var bubbleView: View? = null
    private var readerView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var readerParams: WindowManager.LayoutParams? = null

    private val epubRepository by lazy { EpubRepository(this) }
    private val bookRepository by lazy { BookRepository(this) }
    private val readerPrefs by lazy { ReaderPrefs(this) }
    private val appThemePrefs by lazy { AppThemePrefs(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Chapter yang lagi dibuka, dipakai untuk simpan histori saat reader ditutup
    private var currentBookId: Long = -1L
    private var currentChapterIndex: Int = 0
    private var currentChapterCount: Int = 0
    private var currentTargetScrollPercent: Float = 0f // posisi scroll yang perlu dipulihkan setelah halaman selesai dimuat

    /**
     * Menyajikan isi folder filesDir/books/ (hasil ekstrak semua EPUB) ke WebView
     * lewat virtual domain https://appassets.androidplatform.net/books/...
     * Ini menggantikan file:// yang diblokir WebView modern untuk folder data privat app.
     */
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler(
                "/books/",
                WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "books"))
            )
            .build()
    }

    /** Ubah path absolut hasil ekstrak EPUB jadi URL virtual yang bisa dimuat WebView */
    private fun toAssetUrl(absoluteFilePath: String): String {
        val booksRoot = File(filesDir, "books").canonicalPath
        val relative = File(absoluteFilePath).canonicalPath
            .removePrefix(booksRoot)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        return "https://appassets.androidplatform.net/books/$relative"
    }

    // ---------- Ukuran & posisi jendela reader (tersimpan lewat ReaderPrefs) ----------

    private fun resolveWindowWidth(): Int {
        val saved = readerPrefs.windowWidth
        val screenWidth = resources.displayMetrics.widthPixels
        return if (saved > 0) saved.coerceIn((screenWidth * 0.4).toInt(), screenWidth)
        else (screenWidth * 0.9).toInt()
    }

    private fun resolveWindowHeight(): Int {
        val saved = readerPrefs.windowHeight
        val screenHeight = resources.displayMetrics.heightPixels
        return if (saved > 0) saved.coerceIn((screenHeight * 0.3).toInt(), screenHeight)
        else (screenHeight * 0.75).toInt()
    }

    private fun resolveWindowX(windowWidth: Int): Int {
        val saved = readerPrefs.windowX
        val screenWidth = resources.displayMetrics.widthPixels
        return if (saved != Int.MIN_VALUE) saved.coerceIn(0, (screenWidth - windowWidth).coerceAtLeast(0))
        else (screenWidth - windowWidth) / 2
    }

    private fun resolveWindowY(windowHeight: Int): Int {
        val saved = readerPrefs.windowY
        val screenHeight = resources.displayMetrics.heightPixels
        return if (saved != Int.MIN_VALUE) saved.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))
        else (screenHeight - windowHeight) / 2
    }

    /** Set width/height/x/y pada params sesuai mode saat ini (fullscreen atau floating tersimpan) */
    private fun applyWindowGeometry(params: WindowManager.LayoutParams) {
        if (readerPrefs.isFullscreen) {
            params.width = resources.displayMetrics.widthPixels
            params.height = resources.displayMetrics.heightPixels
            params.x = 0
            params.y = 0
        } else {
            params.width = resolveWindowWidth()
            params.height = resolveWindowHeight()
            params.x = resolveWindowX(params.width)
            params.y = resolveWindowY(params.height)
        }
    }

    /** Sinkronkan ikon tombol & sembunyikan gagang resize saat fullscreen (tidak relevan lagi) */
    private fun refreshFullscreenUi(rootView: View, btnFullscreen: ImageView) {
        val isFull = readerPrefs.isFullscreen
        rootView.findViewById<View>(R.id.resizeHandle)?.visibility = if (isFull) View.GONE else View.VISIBLE
        btnFullscreen.setImageResource(if (isFull) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    /**
     * Warna "chrome" reader (top bar, panel pengaturan, teks) mengikuti mode
     * Sistem/Terang/Gelap aplikasi - TERPISAH dari tema baca konten EPUB
     * (Day/Night/Sepia) yang punya pengaturannya sendiri.
     */
    private fun applyChromeTheme(rootView: View) {
        val isDark = appThemePrefs.isDarkActive(this)
        val chromeBg = if (isDark) Color.parseColor("#1E1E2A") else Color.parseColor("#FFFFFF")
        val chromeText = if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#1A1A1A")
        val chromeSecondary = if (isDark) Color.parseColor("#AAAAAA") else Color.parseColor("#666666")
        val panelBg = if (isDark) Color.parseColor("#22283A") else Color.parseColor("#F0F0F5")
        val accent = Color.parseColor("#4A90E2")

        (rootView.background as? GradientDrawable)?.apply {
            setColor(chromeBg)
            setStroke((1 * resources.displayMetrics.density).toInt(), accent)
        }

        rootView.findViewById<TextView>(R.id.btnReaderSettings)?.setTextColor(chromeText)
        rootView.findViewById<TextView>(R.id.txtChapterPosition)?.setTextColor(chromeSecondary)

        listOf(R.id.btnMinimize, R.id.btnClose, R.id.btnFullscreen, R.id.btnBookmark).forEach { id ->
            rootView.findViewById<ImageView>(id)?.setColorFilter(chromeText)
        }

        val panel = rootView.findViewById<View>(R.id.readerSettingsPanel)
        (panel?.background as? GradientDrawable)?.setColor(panelBg)
        rootView.findViewById<TextView>(R.id.txtReadingModeLabel)?.setTextColor(chromeSecondary)
        rootView.findViewById<TextView>(R.id.txtFontSizeLabel)?.setTextColor(chromeSecondary)
        rootView.findViewById<TextView>(R.id.txtFontFamilyLabel)?.setTextColor(chromeSecondary)
        rootView.findViewById<TextView>(R.id.txtFontSize)?.setTextColor(chromeText)

        val bookmarkPanel = rootView.findViewById<View>(R.id.bookmarkPanel)
        (bookmarkPanel?.background as? GradientDrawable)?.setColor(panelBg)
        rootView.findViewById<TextView>(R.id.txtNoBookmarks)?.setTextColor(chromeSecondary)
        rootView.findViewById<TextView>(R.id.txtBookmarksLabel)?.setTextColor(chromeSecondary)
    }

    /** Ambil posisi scroll WebView saat ini (0.0-1.0) lalu simpan ke histori sebelum lanjut aksi lain */
    private fun saveCurrentScrollPosition(webView: WebView?, onDone: () -> Unit) {
        if (webView == null || currentBookId < 0) {
            onDone()
            return
        }
        val js = "(function(){ var max = document.body.scrollHeight - window.innerHeight; " +
            "if (max <= 0) return 0; return window.scrollY / max; })();"

        webView.evaluateJavascript(js) { result ->
            val percent = result?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            serviceScope.launch {
                bookRepository.updateProgress(
                    bookId = currentBookId,
                    chapterIndex = currentChapterIndex,
                    scrollPercent = percent,
                    overallProgressPercent = if (currentChapterCount > 0) {
                        (currentChapterIndex + 1) / currentChapterCount.toFloat()
                    } else 0f
                )
                onDone()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        when (intent?.action) {
            ACTION_START_BUBBLE -> showBubble()
            ACTION_EXPAND_READER -> {
                val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
                showReader(bookId)
            }
            ACTION_COLLAPSE -> {
                removeReader()
                showBubble()
            }
            ACTION_STOP -> {
                removeAllViews()
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ---------- BUBBLE ----------

    private fun showBubble() {
        if (bubbleView != null) return // sudah ada, jangan duplikat

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.layout_bubble, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // "Memorize" posisi: pakai lokasi terakhir bubble digeser, kalau belum pernah baru default
            x = if (readerPrefs.bubbleX != Int.MIN_VALUE) readerPrefs.bubbleX else 0
            y = if (readerPrefs.bubbleY != Int.MIN_VALUE) readerPrefs.bubbleY else 200
        }

        attachDragBehavior(view, params)

        try {
            windowManager.addView(view, params)
            bubbleView = view
            bubbleParams = params
        } catch (e: Exception) {
            // Bisa terjadi kalau izin overlay dicabut user di tengah jalan
            stopSelf()
        }
    }

    private fun removeBubble() {
        bubbleView?.let {
            windowManager.removeView(it)
            bubbleView = null
            bubbleParams = null
        }
    }

    /**
     * Drag-to-move untuk bubble, sekaligus membedakan antara "drag" dan "tap"
     * berdasarkan jarak pergeseran jari.
     */
    private fun attachDragBehavior(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var lastUpdateMs = 0L

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= 16) { // throttle ~60fps, hemat CPU/baterai
                        lastUpdateMs = now
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    windowManager.updateViewLayout(view, params) // pastikan posisi akhir akurat
                    if (!isDragging) {
                        v.performClick() // untuk aksesibilitas
                        showReader(bookId = -1L) // -1 = buku terakhir dibaca
                    } else {
                        // Simpan posisi terakhir supaya bubble "mengingat" lokasinya
                        readerPrefs.bubbleX = params.x
                        readerPrefs.bubbleY = params.y
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---------- READER ----------

    private fun showReader(bookId: Long) {
        removeBubble()

        // Reader sudah terbuka -> jangan bikin window baru, cukup ganti isi bukunya.
        if (readerView != null) {
            val existingWebView = readerView?.findViewById<WebView>(R.id.webViewContent)
            loadBookChapter(existingWebView, bookId, chapterIndex = -1)
            return
        }

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.layout_reader, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val windowWidth = resolveWindowWidth()
        val windowHeight = resolveWindowHeight()

        val params = WindowManager.LayoutParams(
            windowWidth,
            windowHeight,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        applyWindowGeometry(params) // set ukuran final - fullscreen kalau isFullscreen aktif

        val webView = view.findViewById<WebView>(R.id.webViewContent)
        webView?.settings?.apply {
            javaScriptEnabled = true // dibutuhkan untuk inject tema/font ke konten EPUB
            // Matikan fitur yang tidak dipakai EPUB - mengurangi jejak proses & risiko boros baterai
            domStorageEnabled = false
            databaseEnabled = false
            mediaPlaybackRequiresUserGesture = true // cegah media di dalam EPUB autoplay diam-diam
            setGeolocationEnabled(false)
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE // konten lokal, cache cuma buang-buang disk I/O
        }
        webView?.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                applyReaderStyle(view) // terapkan tema/font tiap kali chapter baru selesai dimuat
                if (currentTargetScrollPercent > 0f) {
                    val percent = currentTargetScrollPercent
                    val js = "window.scrollTo(0, Math.round((document.body.scrollHeight - window.innerHeight) * $percent));"
                    view.evaluateJavascript(js, null)
                }
            }
        }

        setupReaderSettingsPanel(view, webView)
        setupBookmarkPanel(view, webView)
        applyChromeTheme(view) // warna top bar/panel ikut mode Sistem/Terang/Gelap aplikasi

        view.findViewById<View>(R.id.btnPrevChapter)?.setOnClickListener { goToPrevChapter(webView) }
        view.findViewById<View>(R.id.btnNextChapter)?.setOnClickListener { goToNextChapter(webView) }
        webView?.let { attachSwipeGestures(it) }

        // Minimize: kembali ke bubble, posisi scroll persis disimpan dulu supaya tidak hilang
        view.findViewById<View>(R.id.btnMinimize)?.setOnClickListener {
            saveCurrentScrollPosition(webView) {
                removeReader()
                showBubble()
            }
        }
        // Close: keluar total dari floating reader (bubble ikut hilang), posisi scroll disimpan dulu
        view.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            saveCurrentScrollPosition(webView) {
                removeAllViews()
                stopSelf()
            }
        }

        // Fullscreen: toggle antara layar penuh dan ukuran floating tersimpan
        val btnFullscreen = view.findViewById<ImageView>(R.id.btnFullscreen)
        btnFullscreen?.let { refreshFullscreenUi(view, it) }
        btnFullscreen?.setOnClickListener {
            readerPrefs.isFullscreen = !readerPrefs.isFullscreen
            applyWindowGeometry(params)
            refreshFullscreenUi(view, btnFullscreen)
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                // Window belum terpasang (baru pertama kali dibuat) - abaikan, params sudah benar untuk addView
            }
        }

        attachResizeHandle(view, params)
        attachWindowDragBehavior(view, params)

        try {
            windowManager.addView(view, params)
            readerView = view
            readerParams = params
        } catch (e: Exception) {
            // Izin overlay dicabut di tengah jalan -> jangan crash, cukup hentikan service
            stopSelf()
            return
        }

        loadBookChapter(webView, bookId, chapterIndex = -1)
    }

    /** Wiring panel "Aa": tombol tema, ukuran font, dan pilihan jenis font */
    private fun setupReaderSettingsPanel(rootView: View, webView: WebView?) {
        val panel = rootView.findViewById<View>(R.id.readerSettingsPanel)
        val btnSettings = rootView.findViewById<View>(R.id.btnReaderSettings)
        val txtFontSize = rootView.findViewById<TextView>(R.id.txtFontSize)
        val spinner = rootView.findViewById<Spinner>(R.id.spinnerFontFamily)

        btnSettings?.setOnClickListener {
            rootView.findViewById<View>(R.id.bookmarkPanel)?.visibility = View.GONE // tutup panel lain
            panel?.visibility = if (panel?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        txtFontSize?.text = readerPrefs.fontSizePx.toString()

        val fontOptions = ReaderFont.values()
        spinner?.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            fontOptions.map { it.label }
        )
        spinner?.setSelection(fontOptions.indexOf(readerPrefs.font))
        spinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                readerPrefs.font = fontOptions[position]
                applyReaderStyle(webView)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rootView.findViewById<View>(R.id.btnThemeDay)?.setOnClickListener {
            readerPrefs.theme = ReadingTheme.DAY
            applyReaderStyle(webView)
        }
        rootView.findViewById<View>(R.id.btnThemeNight)?.setOnClickListener {
            readerPrefs.theme = ReadingTheme.NIGHT
            applyReaderStyle(webView)
        }
        rootView.findViewById<View>(R.id.btnThemeSepia)?.setOnClickListener {
            readerPrefs.theme = ReadingTheme.SEPIA
            applyReaderStyle(webView)
        }

        rootView.findViewById<View>(R.id.btnFontSmaller)?.setOnClickListener {
            readerPrefs.fontSizePx -= ReaderPrefs.FONT_SIZE_STEP
            txtFontSize?.text = readerPrefs.fontSizePx.toString()
            applyReaderStyle(webView)
        }
        rootView.findViewById<View>(R.id.btnFontBigger)?.setOnClickListener {
            readerPrefs.fontSizePx += ReaderPrefs.FONT_SIZE_STEP
            txtFontSize?.text = readerPrefs.fontSizePx.toString()
            applyReaderStyle(webView)
        }
    }

    /** Wiring tombol bookmark: toggle panel, tombol "+ Add here", dan render daftar bookmark */
    private fun setupBookmarkPanel(rootView: View, webView: WebView?) {
        val panel = rootView.findViewById<View>(R.id.bookmarkPanel)
        val btnToggle = rootView.findViewById<View>(R.id.btnBookmark)

        btnToggle?.setOnClickListener {
            if (panel?.visibility == View.VISIBLE) {
                panel.visibility = View.GONE
            } else {
                rootView.findViewById<View>(R.id.readerSettingsPanel)?.visibility = View.GONE // tutup panel lain
                panel?.visibility = View.VISIBLE
                refreshBookmarkList(rootView, webView)
            }
        }

        rootView.findViewById<View>(R.id.btnAddBookmark)?.setOnClickListener {
            addBookmarkAtCurrentPosition(rootView, webView)
        }
    }

    /** Ambil posisi scroll saat ini lalu simpan sebagai bookmark baru */
    private fun addBookmarkAtCurrentPosition(rootView: View, webView: WebView?) {
        if (webView == null || currentBookId < 0) return
        val js = "(function(){ var max = document.body.scrollHeight - window.innerHeight; " +
            "if (max <= 0) return 0; return window.scrollY / max; })();"

        webView.evaluateJavascript(js) { result ->
            val percent = result?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
            serviceScope.launch {
                bookRepository.addBookmark(currentBookId, currentChapterIndex, percent)
                refreshBookmarkList(rootView, webView)
            }
        }
    }

    /** Render ulang daftar bookmark untuk buku yang sedang dibuka */
    private fun refreshBookmarkList(rootView: View, webView: WebView?) {
        val container = rootView.findViewById<LinearLayout>(R.id.bookmarkListContainer) ?: return
        val emptyLabel = rootView.findViewById<TextView>(R.id.txtNoBookmarks)
        if (currentBookId < 0) return

        serviceScope.launch {
            val bookmarks = bookRepository.getBookmarksForBook(currentBookId)
            container.removeAllViews()
            emptyLabel?.visibility = if (bookmarks.isEmpty()) View.VISIBLE else View.GONE

            val isDark = appThemePrefs.isDarkActive(this@OverlayService)
            val textColor = if (isDark) Color.parseColor("#FFFFFF") else Color.parseColor("#1A1A1A")
            val scale = resources.displayMetrics.density

            bookmarks.forEach { bookmark ->
                val row = LinearLayout(this@OverlayService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (6 * scale).toInt(), 0, (6 * scale).toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        hideBookmarkPanelAndJump(rootView, webView, bookmark)
                    }
                }
                val label = TextView(this@OverlayService).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = "Chapter ${bookmark.chapterIndex + 1} • ${(bookmark.scrollPercent * 100).toInt()}%"
                    setTextColor(textColor)
                    textSize = 13f
                }
                val deleteBtn = ImageView(this@OverlayService).apply {
                    layoutParams = LinearLayout.LayoutParams((28 * scale).toInt(), (28 * scale).toInt())
                    setImageResource(R.drawable.ic_delete)
                    setPadding((6 * scale).toInt(), (6 * scale).toInt(), (6 * scale).toInt(), (6 * scale).toInt())
                    background = null
                    setOnClickListener {
                        serviceScope.launch {
                            bookRepository.deleteBookmark(bookmark.id)
                            refreshBookmarkList(rootView, webView)
                        }
                    }
                }
                row.addView(label)
                row.addView(deleteBtn)
                container.addView(row)

                container.addView(View(this@OverlayService).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (1 * scale).toInt())
                    setBackgroundColor(Color.parseColor(if (isDark) "#22FFFFFF" else "#22000000"))
                })
            }
        }
    }

    /** Tutup panel bookmark lalu lompat ke posisi yang ditandai */
    private fun hideBookmarkPanelAndJump(rootView: View, webView: WebView?, bookmark: Bookmark) {
        rootView.findViewById<View>(R.id.bookmarkPanel)?.visibility = View.GONE
        webView ?: return
        saveCurrentScrollPosition(webView) {
            loadBookChapter(webView, currentBookId, bookmark.chapterIndex, forceScrollPercent = bookmark.scrollPercent)
        }
    }

    /** Inject CSS ke halaman EPUB yang sedang tampil sesuai preferensi tema/font saat ini */
    private fun applyReaderStyle(webView: WebView?) {
        if (webView == null) return
        val theme = readerPrefs.theme
        val font = readerPrefs.font
        val fontSize = readerPrefs.fontSizePx
        val fontFamilyRule = if (font.cssValue.isNotEmpty()) "font-family: ${font.cssValue} !important;" else ""

        val css = "html, body { background-color: ${theme.bgColor} !important; } " +
            "body, body * { color: ${theme.textColor} !important; $fontFamilyRule " +
            "font-size: ${fontSize}px !important; line-height: 1.6 !important; }"

        val js = """
            (function() {
                var style = document.getElementById('floatingepub-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'floatingepub-style';
                    document.head.appendChild(style);
                }
                style.innerHTML = ${org.json.JSONObject.quote(css)};
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    /** Drag dari area titik-titik di top bar -> pindahkan posisi jendela reader */
    private fun attachWindowDragBehavior(rootView: View, params: WindowManager.LayoutParams) {
        val dragHandle = rootView.findViewById<View>(R.id.dragHandle) ?: return

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastUpdateMs = 0L

        dragHandle.setOnTouchListener { v, event ->
            if (readerPrefs.isFullscreen) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    val screenWidth = resources.displayMetrics.widthPixels
                    val screenHeight = resources.displayMetrics.heightPixels
                    params.x = (initialX + dx).coerceIn(0, (screenWidth - params.width).coerceAtLeast(0))
                    params.y = (initialY + dy).coerceIn(0, (screenHeight - params.height).coerceAtLeast(0))

                    // Throttle ke ~60fps - layar sentuh bisa kirim event lebih cepat dari itu,
                    // update layout window tiap event = kerja render/CPU yang mubazir & boros baterai
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= 16) {
                        lastUpdateMs = now
                        try {
                            windowManager.updateViewLayout(rootView, params)
                        } catch (e: Exception) {
                            // Window mungkin sudah tidak terpasang lagi - abaikan
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Pastikan posisi akhir benar-benar ter-render (bisa saja frame terakhir kena throttle)
                    try {
                        windowManager.updateViewLayout(rootView, params)
                    } catch (e: Exception) {
                        // abaikan
                    }
                    readerPrefs.windowX = params.x
                    readerPrefs.windowY = params.y
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /** Navigasi chapter - dipakai bersama oleh tombol <<< / >>> dan gesture swipe */
    private fun goToNextChapter(webView: WebView?) {
        webView ?: return
        if (currentChapterIndex < currentChapterCount - 1) {
            saveCurrentScrollPosition(webView) {
                loadBookChapter(webView, currentBookId, currentChapterIndex + 1)
            }
        }
    }

    private fun goToPrevChapter(webView: WebView?) {
        webView ?: return
        if (currentChapterIndex > 0) {
            saveCurrentScrollPosition(webView) {
                loadBookChapter(webView, currentBookId, currentChapterIndex - 1)
            }
        }
    }

    /**
     * Gesture system: swipe kiri/kanan pada area WebView untuk ganti chapter,
     * mirip membalik halaman. GestureDetector cuma "mengintip" touch event
     * (selalu return false di listener) supaya scroll/zoom bawaan WebView tetap normal -
     * baru dianggap swipe chapter kalau gerakan horizontal jauh lebih dominan dari vertikal.
     */
    private fun attachSwipeGestures(webView: WebView) {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val deltaX = e2.x - e1.x
                val deltaY = e2.y - e1.y

                val isHorizontalSwipe = kotlin.math.abs(deltaX) > SWIPE_MIN_DISTANCE &&
                    kotlin.math.abs(deltaX) > kotlin.math.abs(deltaY) * 1.5f &&
                    kotlin.math.abs(velocityX) > SWIPE_MIN_VELOCITY

                if (isHorizontalSwipe) {
                    if (deltaX < 0) goToNextChapter(webView) else goToPrevChapter(webView)
                    return true
                }
                return false
            }
        })

        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false // selalu false: biar WebView tetap terima event untuk scroll normal
        }
    }

    /** Drag dari gagang di pojok kanan-bawah -> ubah lebar/tinggi jendela reader */
    private fun attachResizeHandle(rootView: View, params: WindowManager.LayoutParams) {
        val handle = rootView.findViewById<View>(R.id.resizeHandle) ?: return

        var startWidth = 0
        var startHeight = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var lastUpdateMs = 0L

        val minWidth = (resources.displayMetrics.widthPixels * 0.4).toInt()
        val minHeight = (resources.displayMetrics.heightPixels * 0.3).toInt()
        val maxWidth = resources.displayMetrics.widthPixels
        val maxHeight = resources.displayMetrics.heightPixels

        handle.setOnTouchListener { v, event ->
            if (readerPrefs.isFullscreen) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    startHeight = params.height
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    params.width = (startWidth + dx).coerceIn(minWidth, maxWidth)
                    params.height = (startHeight + dy).coerceIn(minHeight, maxHeight)

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateMs >= 16) {
                        lastUpdateMs = now
                        try {
                            windowManager.updateViewLayout(rootView, params)
                        } catch (e: Exception) {
                            // Window mungkin sudah tidak terpasang lagi - abaikan
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    try {
                        windowManager.updateViewLayout(rootView, params)
                    } catch (e: Exception) {
                        // abaikan
                    }
                    readerPrefs.windowWidth = params.width
                    readerPrefs.windowHeight = params.height
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Buka & tampilkan isi chapter ke WebView.
     * bookId = -1 means "last read book" -> resolved from the most recent history entry.
     * chapterIndex = -1 means "resume last position" (not necessarily chapter 0), and also
     * signals that the saved scroll position for that chapter should be restored.
     */
    private fun loadBookChapter(
        webView: WebView?,
        bookId: Long,
        chapterIndex: Int,
        forceScrollPercent: Float? = null
    ) {
        if (webView == null) return

        serviceScope.launch {
            try {
                val resolvedBookId = if (bookId >= 0) {
                    bookId
                } else {
                    bookRepository.getMostRecentHistory()?.bookId ?: -1L
                }

                if (resolvedBookId < 0) {
                    webView.loadData(
                        "<html><body style='color:#eee;background:#1e1e2a;font-family:sans-serif;padding:24px'>" +
                            "No book is open yet. Add a book from the home screen first.</body></html>",
                        "text/html", "UTF-8"
                    )
                    return@launch
                }

                val bookEntity = bookRepository.getHistoryForBook(resolvedBookId)
                val isResuming = chapterIndex < 0
                val resolvedChapter = if (!isResuming) {
                    chapterIndex
                } else {
                    bookEntity?.lastChapterIndex ?: 0
                }

                // Only restore the exact scroll position when we're resuming the same chapter
                // the user left off at - explicit prev/next navigation always starts at the top.
                currentTargetScrollPercent = when {
                    forceScrollPercent != null -> forceScrollPercent
                    isResuming && bookEntity?.lastChapterIndex == resolvedChapter -> bookEntity.lastScrollPercent
                    else -> 0f
                }

                val bookRow = bookRepository.getBookById(resolvedBookId)
                    ?: throw IllegalStateException("Book with id=$resolvedBookId not found")

                val epubBook = epubRepository.open(File(bookRow.filePath), resolvedBookId)
                val chapterPath = epubBook.chapterFilePath(resolvedChapter)
                val url = chapterPath?.let { toAssetUrl(it) }

                currentBookId = resolvedBookId
                currentChapterIndex = resolvedChapter
                currentChapterCount = epubBook.chapterCount

                val overallProgress = if (epubBook.chapterCount > 0) {
                    (resolvedChapter + 1) / epubBook.chapterCount.toFloat()
                } else 0f

                readerView?.findViewById<TextView>(R.id.txtChapterPosition)?.text =
                    "${resolvedChapter + 1} / ${epubBook.chapterCount}  •  ${(overallProgress * 100).toInt()}%"
                readerView?.findViewById<android.widget.ProgressBar>(R.id.readingProgressBar)?.progress =
                    (overallProgress * 100).toInt()

                if (url != null) {
                    webView.loadUrl(url)
                    bookRepository.updateProgress(
                        bookId = resolvedBookId,
                        chapterIndex = resolvedChapter,
                        scrollPercent = currentTargetScrollPercent,
                        overallProgressPercent = overallProgress
                    )
                } else {
                    webView.loadData("<html><body>Chapter not found.</body></html>", "text/html", "UTF-8")
                }
            } catch (e: Exception) {
                webView.loadData(
                    "<html><body style='color:#eee;background:#1e1e2a;padding:24px'>" +
                        "Failed to open EPUB: ${e.message}</body></html>",
                    "text/html", "UTF-8"
                )
            }
        }
    }

    private fun removeReader() {
        readerView?.let { view ->
            // Penting untuk baterai: WebView yang cuma dilepas dari WindowManager TIDAK otomatis
            // berhenti - proses render Chromium-nya (timer JS, cache, thread) tetap hidup diam-diam
            // di background kalau tidak di-destroy() secara eksplisit.
            view.findViewById<WebView>(R.id.webViewContent)?.let { webView ->
                webView.stopLoading()
                webView.onPause()
                webView.clearHistory()
                webView.loadUrl("about:blank")
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
            windowManager.removeView(view)
            readerView = null
            readerParams = null
        }
    }

    private fun removeAllViews() {
        removeBubble()
        removeReader()
    }

    // ---------- NOTIFICATION (wajib untuk foreground service) ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Book Service",
                NotificationManager.IMPORTANCE_MIN
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Book active")
            .setSmallIcon(R.drawable.ic_bubble_book)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        removeAllViews()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

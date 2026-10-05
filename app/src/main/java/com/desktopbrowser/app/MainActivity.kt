package com.desktopbrowser.app

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.desktopbrowser.app.databinding.ActivityMainBinding
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var history: HistoryStore
    private lateinit var bookmarks: BookmarkStore
    private lateinit var tabAdapter: TabGridAdapter
    private lateinit var runtime: GeckoRuntime

    private val tabs = ArrayList<Tab>()
    private var activeTab: Tab? = null
    private var nextTabId = 1L

    private var isPageLoading = false
    private var adBlockEnabled = false
    private val closedTabs = ArrayList<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Layar penuh (video / elemen fullscreen)
    private var isFullScreen = false

    // Upload file (PromptDelegate.FilePrompt)
    private var pendingFilePrompt: PromptDelegate.FilePrompt? = null
    private var pendingFileResult: GeckoResult<PromptDelegate.PromptResponse>? = null
    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val prompt = pendingFilePrompt
            val res = pendingFileResult
            pendingFilePrompt = null
            pendingFileResult = null
            if (prompt == null || res == null) return@registerForActivityResult

            val data = result.data
            val uris = ArrayList<Uri>()
            if (result.resultCode == RESULT_OK && data != null) {
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
                }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
            }
            when {
                uris.isEmpty() -> res.complete(prompt.dismiss())
                prompt.type == PromptDelegate.FilePrompt.Type.SINGLE ->
                    res.complete(prompt.confirm(this, uris[0]))
                else -> res.complete(prompt.confirm(this, uris.toTypedArray()))
            }
        }

    // Unduhan: izin penyimpanan hanya diperlukan di Android 9 ke bawah
    private var pendingDownloadUrl: String? = null
    private var pendingDownloadResponse: WebResponse? = null
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val url = pendingDownloadUrl
            val response = pendingDownloadResponse
            pendingDownloadUrl = null
            pendingDownloadResponse = null
            if (!granted) {
                toast("Izin penyimpanan diperlukan untuk mengunduh")
            } else if (url != null) {
                enqueueDownload(url)
            } else if (response != null) {
                saveResponse(response)
            }
        }

    companion object {
        private const val HOME_URL = "https://www.google.com"
        private const val SEARCH_URL = "https://www.google.com/search?q="

        private const val PREFS = "browser_settings"
        private const val KEY_ADBLOCK = "block_ads"

        // Jumlah tab yang boleh "hidup" (sesi aktif di RAM) sekaligus.
        // Tab lain dibekukan ke disk dan dipulihkan saat dibuka.
        private const val MAX_LIVE_TABS = 4
        private const val MAX_TABS = 30
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        adBlockEnabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ADBLOCK, false)
        runtime = GeckoProvider.get(this, adBlockEnabled)
        history = HistoryStore(applicationContext)
        bookmarks = BookmarkStore(applicationContext)

        setupAddressBar()
        setupBottomBar()
        setupMenu()
        setupTabSwitcher()
        setupFindBar()
        setupBackPress()

        SessionStore.deleteLegacy(this)
        restoreTabs()
    }

    override fun onStart() {
        super.onStart()
        activeTab?.session?.setActive(true)
    }

    override fun onStop() {
        super.onStop()
        // Android bisa mematikan proses kapan saja setelah onStop:
        // simpan state SEMUA tab yang hidup + daftar tab.
        saveAllTabs()
        activeTab?.session?.setActive(false)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Aplikasi di latar belakang & RAM menipis -> bekukan tab non-aktif
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            saveAllTabs()
            tabs.filter { it !== activeTab && it.session != null }.forEach { freezeTab(it) }
        }
    }

    override fun onDestroy() {
        saveAllTabs()
        binding.geckoView.releaseSession()
        tabs.forEach {
            it.session?.let { s -> if (s.isOpen) s.close() }
            it.session = null
        }
        super.onDestroy()
    }

    // ==================================================================
    // Pembuatan sesi GeckoView (tampilan desktop permanen)
    // ==================================================================
    private fun newSessionSettings(): GeckoSessionSettings =
        GeckoSessionSettings.Builder()
            .usePrivateMode(false)
            // UA desktop asli Firefox + viewport desktop: situs memuat versi desktop
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP)
            .useTrackingProtection(adBlockEnabled)
            .allowJavascript(true)
            .build()

    private fun createSession(tab: Tab): GeckoSession {
        val session = GeckoSession(newSessionSettings())
        session.setProgressDelegate(progressDelegateFor(tab))
        session.setNavigationDelegate(navigationDelegateFor(tab))
        session.setContentDelegate(contentDelegateFor(tab))
        session.setPromptDelegate(promptDelegate)
        return session
    }

    private fun progressDelegateFor(tab: Tab) = object : GeckoSession.ProgressDelegate {

        override fun onPageStart(session: GeckoSession, url: String) {
            tab.url = url
            tab.progress = 1
            if (tab === activeTab) {
                setLoadingState(true)
                updateUrlBar(url)
            }
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            tab.progress = 0
            if (tab === activeTab) {
                setLoadingState(false)
                updateNavState()
            }
            val url = tab.url
            if (success && isWebUrl(url)) history.add(url, tab.title)
            SessionStore.save(this@MainActivity, tab.sessionState, tabFile(tab))
            persistTabs()
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            tab.progress = progress
            if (tab === activeTab) {
                binding.progressBar.progress = if (progress >= 100) 0 else progress
            }
        }

        override fun onSessionStateChange(
            session: GeckoSession,
            sessionState: GeckoSession.SessionState
        ) {
            tab.sessionState = sessionState
        }
    }

    private fun navigationDelegateFor(tab: Tab) = object : NavigationDelegate {

        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean
        ) {
            if (!url.isNullOrBlank()) tab.url = url
            if (tab === activeTab) updateUrlBar(url)
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tab.canGoBack = canGoBack
            if (tab === activeTab) updateNavState()
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tab.canGoForward = canGoForward
            if (tab === activeTab) updateNavState()
        }

        /**
         * Isolasi navigasi:
         *  - http/https/about/blob/data/javascript -> dimuat di dalam aplikasi
         *  - intent:// -> hanya browser_fallback_url http(s) yang dimuat
         *  - skema lain (market://, youtube://, shopee://, dll) -> DIBLOKIR
         */
        override fun onLoadRequest(
            session: GeckoSession,
            request: NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? {
            val uri = Uri.parse(request.uri)
            return when (uri.scheme?.lowercase()) {
                "http", "https", "about", "blob", "data", "javascript" ->
                    GeckoResult.fromValue(AllowOrDeny.ALLOW)
                "intent" -> {
                    try {
                        val intent = Intent.parseUri(request.uri, Intent.URI_INTENT_SCHEME)
                        val fallback = intent.getStringExtra("browser_fallback_url")
                        if (!fallback.isNullOrBlank() && isWebUrl(fallback)) {
                            mainHandler.post { tab.session?.loadUri(fallback) }
                        }
                    } catch (_: Exception) {
                    }
                    GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                else -> GeckoResult.fromValue(AllowOrDeny.DENY)
            }
        }

        // target="_blank" / window.open() -> tab baru di dalam aplikasi
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            if (tabs.size >= MAX_TABS) return null
            val newTab = Tab(nextTabId++)
            newTab.parentId = tab.id
            newTab.url = uri
            val newSession = createSession(newTab)
            // Sesi ini dibuka oleh Gecko sendiri (jangan dibuka manual)
            newTab.session = newSession
            tabs.add(newTab)
            activateWhenOpen(newTab, 20)
            return GeckoResult.fromValue(newSession)
        }
    }

    /** Sesi dari onNewSession dibuka Gecko sesaat setelah callback; tunggu sampai terbuka. */
    private fun activateWhenOpen(tab: Tab, attemptsLeft: Int) {
        mainHandler.postDelayed({
            if (!tabs.contains(tab)) return@postDelayed
            val s = tab.session
            if (s != null && !s.isOpen && attemptsLeft > 0) {
                activateWhenOpen(tab, attemptsLeft - 1)
            } else {
                hideTabSwitcher()
                activateTab(tab)
            }
        }, 30)
    }

    private fun contentDelegateFor(tab: Tab) = object : ContentDelegate {

        override fun onTitleChange(session: GeckoSession, title: String?) {
            if (!title.isNullOrBlank()) tab.title = title
        }

        override fun onCloseRequest(session: GeckoSession) {
            val parent = tabs.firstOrNull { it.id == tab.parentId }
            mainHandler.post { closeTab(tab, parent) }
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            if (tab === activeTab) setFullScreenUi(fullScreen)
        }

        override fun onContextMenu(
            session: GeckoSession,
            screenX: Int,
            screenY: Int,
            element: ContentDelegate.ContextElement
        ) {
            val src = element.srcUri
            val link = element.linkUri
            when {
                element.type == ContentDelegate.ContextElement.TYPE_IMAGE &&
                    src != null && isWebUrl(src) -> showImageDialog(src, tab)
                link != null && isWebUrl(link) -> showLinkDialog(link, tab)
            }
        }

        // Respons yang tidak ditampilkan Gecko (unduhan)
        override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
            startDownload(response)
        }

        override fun onCrash(session: GeckoSession) = recoverTab(tab)

        override fun onKill(session: GeckoSession) = recoverTab(tab)
    }

    /** Proses konten mati: buat ulang sesi dan pulihkan dari state terakhir. */
    private fun recoverTab(tab: Tab) {
        if (!tabs.contains(tab)) return
        val wasActive = tab === activeTab
        disposeSession(tab)
        if (wasActive) activateTab(tab)
    }

    // ==================================================================
    // Dialog JavaScript, dropdown <select>, dan upload file
    // ==================================================================
    private val promptDelegate = object : PromptDelegate {

        override fun onAlertPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.AlertPrompt
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            var done = false
            fun finish() {
                if (!done) {
                    done = true
                    result.complete(prompt.dismiss())
                }
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton("OK") { _, _ -> finish() }
                .setOnDismissListener { finish() }
                .show()
            return result
        }

        override fun onButtonPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.ButtonPrompt
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            var done = false
            fun finish(r: PromptDelegate.PromptResponse) {
                if (!done) {
                    done = true
                    result.complete(r)
                }
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton("OK") { _, _ ->
                    finish(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE))
                }
                .setNegativeButton("Batal") { _, _ ->
                    finish(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE))
                }
                .setOnDismissListener { finish(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onTextPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.TextPrompt
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            var done = false
            fun finish(r: PromptDelegate.PromptResponse) {
                if (!done) {
                    done = true
                    result.complete(r)
                }
            }
            val input = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                setText(prompt.defaultValue ?: "")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton("OK") { _, _ -> finish(prompt.confirm(input.text.toString())) }
                .setNegativeButton("Batal") { _, _ -> finish(prompt.dismiss()) }
                .setOnDismissListener { finish(prompt.dismiss()) }
                .show()
            return result
        }

        // Dropdown <select> dan menu pilihan
        override fun onChoicePrompt(
            session: GeckoSession,
            prompt: PromptDelegate.ChoicePrompt
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            var done = false
            fun finish(r: PromptDelegate.PromptResponse) {
                if (!done) {
                    done = true
                    result.complete(r)
                }
            }

            val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
            fun collect(list: Array<PromptDelegate.ChoicePrompt.Choice>?) {
                list?.forEach { c ->
                    if (c.separator) return@forEach
                    val sub = c.items
                    if (sub != null) collect(sub) else if (!c.disabled) flat.add(c)
                }
            }
            collect(prompt.choices)
            if (flat.isEmpty()) {
                result.complete(prompt.dismiss())
                return result
            }
            val labels = flat.map { it.label }.toTypedArray()

            val builder = AlertDialog.Builder(this@MainActivity)
                .setTitle(prompt.title ?: prompt.message)
                .setOnDismissListener { finish(prompt.dismiss()) }

            if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flat.size) { flat[it].selected }
                builder
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                        checked[which] = isChecked
                    }
                    .setPositiveButton("OK") { _, _ ->
                        val picked = flat.filterIndexed { i, _ -> checked[i] }
                        finish(prompt.confirm(picked.toTypedArray()))
                    }
                    .setNegativeButton("Batal", null)
            } else {
                builder.setItems(labels) { _, which -> finish(prompt.confirm(flat[which])) }
            }
            builder.show()
            return result
        }

        override fun onFilePrompt(
            session: GeckoSession,
            prompt: PromptDelegate.FilePrompt
        ): GeckoResult<PromptDelegate.PromptResponse>? {
            if (prompt.type == PromptDelegate.FilePrompt.Type.FOLDER) {
                return GeckoResult.fromValue(prompt.dismiss())
            }
            // Batalkan permintaan sebelumnya yang belum selesai
            pendingFileResult?.complete(pendingFilePrompt?.dismiss())

            val result = GeckoResult<PromptDelegate.PromptResponse>()
            pendingFilePrompt = prompt
            pendingFileResult = result

            val mimes = prompt.mimeTypes
                ?.filter { it.contains('/') }
                ?.takeIf { it.isNotEmpty() }
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (mimes != null && mimes.size == 1) mimes[0] else "*/*"
                if (mimes != null && mimes.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                }
                putExtra(
                    Intent.EXTRA_ALLOW_MULTIPLE,
                    prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
                )
            }
            try {
                fileChooserLauncher.launch(Intent.createChooser(intent, "Pilih berkas"))
            } catch (e: Exception) {
                pendingFilePrompt = null
                pendingFileResult = null
                return GeckoResult.fromValue(prompt.dismiss())
            }
            return result
        }
    }

    // ==================================================================
    // Manajemen tab
    // ==================================================================
    private fun tabFile(tab: Tab) = "tab_${tab.id}.bin"

    private fun restoreTabs() {
        val snap = TabStore.load(this)
        if (snap == null || snap.tabs.isEmpty()) {
            val first = Tab(1L)
            tabs.add(first)
            nextTabId = 2L
            activateTab(first)
        } else {
            tabs.addAll(snap.tabs)
            nextTabId = snap.nextId
            activateTab(tabs.firstOrNull { it.id == snap.activeId } ?: tabs.last())
        }
    }

    private fun persistTabs() {
        TabStore.save(this, tabs, activeTab?.id ?: -1L, nextTabId)
    }

    private fun saveAllTabs() {
        tabs.forEach { t ->
            if (t.session != null) SessionStore.save(this, t.sessionState, tabFile(t))
        }
        persistTabs()
    }

    /** Pastikan tab punya sesi; bila "beku", buat ulang dan pulihkan dari disk. */
    private fun ensureSession(tab: Tab): GeckoSession {
        tab.session?.let { return it }
        val session = createSession(tab)
        tab.session = session
        session.open(runtime)
        val saved = tab.sessionState ?: SessionStore.restore(this, tabFile(tab))
        if (saved != null) {
            session.restoreState(saved)
        } else {
            session.loadUri(if (tab.url.isNotBlank()) tab.url else HOME_URL)
        }
        return session
    }

    /** Tutup sesi sebuah tab (lepas dari tampilan bila sedang tampil). */
    private fun disposeSession(tab: Tab) {
        val s = tab.session ?: return
        if (binding.geckoView.session === s) binding.geckoView.releaseSession()
        if (s.isOpen) s.close()
        tab.session = null
    }

    private fun activateTab(tab: Tab) {
        hideFindBar()
        val old = activeTab
        if (old != null && old !== tab) old.session?.setActive(false)

        activeTab = tab
        tab.lastUsed = System.currentTimeMillis()

        val session = ensureSession(tab)
        binding.geckoView.setSession(session)
        session.setActive(true)
        binding.geckoView.requestFocus()

        binding.urlEditText.clearFocus()
        val p = tab.progress
        setLoadingState(p in 1..99)
        if (p in 1..99) binding.progressBar.progress = p

        updateUrlBar(tab.url)
        updateNavState()
        updateTabCount()
        enforceLiveLimit()
        persistTabs()
    }

    private fun openNewTab(url: String? = null, parent: Tab? = null): Tab? {
        if (tabs.size >= MAX_TABS) {
            toast("Batas $MAX_TABS tab tercapai")
            return null
        }
        val tab = Tab(nextTabId++)
        tab.parentId = parent?.id ?: -1L
        if (url != null) tab.url = url
        tabs.add(tab)
        activateTab(tab)
        return tab
    }

    private fun releaseTab(tab: Tab) {
        disposeSession(tab)
        tab.sessionState = null
        tab.thumbnail = null
        SessionStore.delete(this, tabFile(tab))
    }

    private fun closeTab(tab: Tab, prefer: Tab? = null) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        val wasActive = tab === activeTab

        rememberClosed(tab)
        tabs.removeAt(idx)
        releaseTab(tab)

        if (tabs.isEmpty()) {
            activeTab = null
            openNewTab()
            return
        }
        if (wasActive) {
            val next = prefer?.takeIf { it in tabs } ?: tabs[minOf(idx, tabs.size - 1)]
            activateTab(next)
        } else {
            updateTabCount()
            persistTabs()
        }
    }

    private fun closeAllTabs() {
        tabs.forEach { rememberClosed(it) }
        ArrayList(tabs).forEach { releaseTab(it) }
        tabs.clear()
        activeTab = null
        hideTabSwitcher()
        openNewTab()
    }

    /** Batasi jumlah sesi hidup agar RAM aman; tab paling lama tak dipakai dibekukan. */
    private fun enforceLiveLimit() {
        val live = tabs.filter { it.session != null && it !== activeTab }.sortedBy { it.lastUsed }
        var excess = live.size + 1 - MAX_LIVE_TABS
        for (t in live) {
            if (excess <= 0) break
            freezeTab(t)
            excess--
        }
    }

    private fun freezeTab(tab: Tab) {
        if (tab === activeTab || tab.session == null) return
        SessionStore.save(this, tab.sessionState, tabFile(tab))
        disposeSession(tab)
    }

    private fun updateTabCount() {
        binding.tabCountText.text = tabs.size.toString()
    }

    /** Ambil tangkapan layar tab aktif; [onDone] dipanggil (sukses atau gagal). */
    private fun captureThumbnail(tab: Tab, onDone: () -> Unit) {
        if (tab !== activeTab || binding.geckoView.session !== tab.session ||
            binding.geckoView.width <= 0
        ) {
            onDone()
            return
        }
        try {
            binding.geckoView.capturePixels().accept(
                { bmp ->
                    try {
                        if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                            val w = 300
                            val h = (w * bmp.height.toFloat() / bmp.width).toInt().coerceAtLeast(1)
                            tab.thumbnail = Bitmap.createScaledBitmap(bmp, w, h, true)
                        }
                    } catch (_: Throwable) {
                    }
                    onDone()
                },
                { onDone() }
            )
        } catch (_: Throwable) {
            onDone()
        }
    }

    // ==================================================================
    // Tab switcher (grid kartu tab)
    // ==================================================================
    private fun setupTabSwitcher() {
        tabAdapter = TabGridAdapter()
        binding.tabGrid.adapter = tabAdapter
        binding.tabGrid.setOnItemClickListener { _, _, position, _ ->
            val tab = tabs.getOrNull(position) ?: return@setOnItemClickListener
            hideTabSwitcher()
            activateTab(tab)
        }
        binding.tabButton.setOnClickListener {
            if (isSwitcherVisible()) hideTabSwitcher() else showTabSwitcher()
        }
        binding.newTabButton.setOnClickListener {
            hideTabSwitcher()
            openNewTab()
        }
        binding.closeAllTabsButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Tutup semua tab?")
                .setPositiveButton("Tutup semua") { _, _ -> closeAllTabs() }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun isSwitcherVisible() = binding.tabSwitcher.visibility == View.VISIBLE

    private fun showTabSwitcher() {
        binding.urlEditText.clearFocus()
        hideKeyboard(binding.root)

        // Tangkap thumbnail dulu (tampilan web harus masih terlihat), lalu tampilkan grid.
        var shown = false
        val show = {
            if (!shown) {
                shown = true
                updateSwitcherTitle()
                tabAdapter.notifyDataSetChanged()
                binding.webContainer.visibility = View.GONE
                binding.tabSwitcher.visibility = View.VISIBLE
            }
        }
        val tab = activeTab
        if (tab != null) captureThumbnail(tab) { show() } else show()
        mainHandler.postDelayed({ show() }, 400)
    }

    private fun hideTabSwitcher() {
        binding.tabSwitcher.visibility = View.GONE
        binding.webContainer.visibility = View.VISIBLE
    }

    private fun updateSwitcherTitle() {
        binding.tabSwitcherTitle.text = "${tabs.size} tab"
    }

    private fun hostOf(url: String): String =
        Uri.parse(url).host?.removePrefix("www.") ?: url

    private inner class TabGridAdapter : BaseAdapter() {
        override fun getCount() = tabs.size
        override fun getItem(position: Int): Any = tabs[position]
        override fun getItemId(position: Int) = tabs[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_tab, parent, false)
            val tab = tabs[position]
            val host = if (tab.url.isBlank()) "" else hostOf(tab.url)

            v.findViewById<TextView>(R.id.tabTitle).text =
                tab.title.ifBlank { host.ifBlank { "Tab baru" } }
            v.findViewById<TextView>(R.id.tabHost).text = host
            v.findViewById<ImageView>(R.id.tabThumb).setImageBitmap(tab.thumbnail)
            v.setBackgroundResource(
                if (tab === activeTab) R.drawable.bg_tab_card_active else R.drawable.bg_tab_card
            )
            v.findViewById<ImageButton>(R.id.tabClose).setOnClickListener {
                closeTab(tab)
                updateSwitcherTitle()
                notifyDataSetChanged()
            }
            return v
        }
    }

    // ==================================================================
    // Address bar (omnibox) bergaya Chrome
    // ==================================================================
    private fun currentUrl(): String? = activeTab?.url?.takeIf { it.isNotBlank() }

    private fun setupAddressBar() {
        val et = binding.urlEditText

        et.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                et.setText(currentUrl() ?: "")
                et.post { et.selectAll() }
                binding.clearButton.visibility = View.VISIBLE
            } else {
                binding.clearButton.visibility = View.GONE
                hideKeyboard(v)
                updateUrlBar(currentUrl())
            }
        }

        et.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || isEnter) {
                loadFromInput(et.text.toString())
                true
            } else {
                false
            }
        }

        binding.clearButton.setOnClickListener { et.setText("") }
    }

    private fun loadFromInput(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(" ") || !input.contains(".") -> SEARCH_URL + Uri.encode(input)
            else -> "https://$input"
        }
        activeTab?.session?.loadUri(url)
        binding.urlEditText.clearFocus()
        binding.geckoView.requestFocus()
    }

    private fun updateUrlBar(url: String?) {
        val et = binding.urlEditText
        if (et.hasFocus()) return

        if (url.isNullOrBlank() || url == "about:blank") {
            et.setText("")
            binding.securityIcon.setImageResource(R.drawable.ic_search)
            return
        }

        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.removePrefix("www.")
        et.setText(if ((scheme == "http" || scheme == "https") && host != null) host else url)

        binding.securityIcon.setImageResource(
            if (scheme == "https") R.drawable.ic_lock else R.drawable.ic_info
        )
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ==================================================================
    // Bar bawah
    // ==================================================================
    private fun setupBottomBar() {
        binding.backButton.setOnClickListener {
            if (activeTab?.canGoBack == true) activeTab?.session?.goBack()
        }
        binding.forwardButton.setOnClickListener {
            if (activeTab?.canGoForward == true) activeTab?.session?.goForward()
        }
        binding.homeButton.setOnClickListener { activeTab?.session?.loadUri(HOME_URL) }
        binding.reloadButton.setOnClickListener {
            if (isPageLoading) {
                activeTab?.session?.stop()
                setLoadingState(false)
            } else {
                activeTab?.session?.reload()
            }
        }
    }

    private fun setLoadingState(loading: Boolean) {
        isPageLoading = loading
        binding.reloadButton.setImageResource(
            if (loading) R.drawable.ic_close else R.drawable.ic_refresh
        )
        binding.reloadButton.contentDescription = if (loading) "Berhenti" else "Muat ulang"
        if (!loading) binding.progressBar.progress = 0
    }

    private fun updateNavState() {
        val tab = activeTab
        binding.backButton.apply {
            isEnabled = tab?.canGoBack == true
            alpha = if (isEnabled) 1f else 0.38f
        }
        binding.forwardButton.apply {
            isEnabled = tab?.canGoForward == true
            alpha = if (isEnabled) 1f else 0.38f
        }
    }

    // ==================================================================
    // Menu titik tiga
    // ==================================================================
    private fun setupMenu() {
        binding.menuButton.setOnClickListener { anchor ->
            val url = currentUrl()
            val isBookmarked = !url.isNullOrBlank() && bookmarks.contains(url)
            PopupMenu(this, anchor).apply {
                menu.add(0, 1, 0, "Tab baru")
                if (closedTabs.isNotEmpty()) menu.add(0, 2, 1, "Buka lagi tab yang ditutup")
                menu.add(0, 3, 2, "Muat ulang")
                menu.add(0, 4, 3, if (isBookmarked) "Hapus bookmark halaman ini" else "Tambah bookmark")
                menu.add(0, 5, 4, "Bookmark")
                menu.add(0, 6, 5, "Riwayat")
                menu.add(0, 7, 6, "Cari di halaman")
                menu.add(0, 8, 7, "Bagikan tautan")
                menu.add(0, 9, 8, "Salin tautan")
                menu.add(0, 10, 9, if (adBlockEnabled) "Blokir iklan: AKTIF" else "Blokir iklan: MATI")
                menu.add(0, 12, 10, "Hapus data penjelajahan")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            hideTabSwitcher()
                            openNewTab()
                        }
                        2 -> reopenClosedTab()
                        3 -> activeTab?.session?.reload()
                        4 -> toggleBookmark()
                        5 -> showBookmarks()
                        6 -> showHistory()
                        7 -> showFindBar()
                        8 -> currentUrl()?.let { shareText(it) }
                        9 -> copyCurrentUrl()
                        10 -> toggleAdBlock()
                        12 -> confirmClearData()
                    }
                    true
                }
                show()
            }
        }
    }

    private fun showHistory() {
        val entries = history.all()
        if (entries.isEmpty()) {
            toast("Riwayat kosong")
            return
        }
        val adapter = object : ArrayAdapter<HistoryStore.Entry>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, entries
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = getItem(position)!!
                v.findViewById<TextView>(android.R.id.text1).apply {
                    text = e.title.ifBlank { e.url }
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                v.findViewById<TextView>(android.R.id.text2).apply {
                    text = e.url
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Riwayat")
            .setAdapter(adapter) { _, which ->
                hideTabSwitcher()
                activeTab?.session?.loadUri(entries[which].url)
            }
            .setNeutralButton("Hapus riwayat") { _, _ ->
                history.clear()
                toast("Riwayat dihapus")
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun copyCurrentUrl() {
        val url = currentUrl() ?: return
        copyText(url)
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Hapus data penjelajahan?")
            .setMessage("Cookie, cache, riwayat, dan data situs akan dihapus. Anda akan keluar dari akun yang sedang login.")
            .setPositiveButton("Hapus") { _, _ ->
                runtime.storageController.clearData(StorageController.ClearFlags.ALL)
                history.clear()
                SessionStore.clearAll(this)
                // Buang riwayat navigasi semua tab; tab dimuat ulang dari URL-nya saat dibuka
                val current = activeTab
                tabs.forEach { t ->
                    disposeSession(t)
                    t.sessionState = null
                    t.canGoBack = false
                    t.canGoForward = false
                }
                if (current != null) activateTab(current)
                toast("Data dihapus")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ==================================================================
    // Fitur tambahan: unduhan, bookmark, cari di halaman, menu tekan-lama,
    // layar penuh, tab ditutup, bagikan, blokir iklan
    // ==================================================================
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun isWebUrl(url: String) = url.startsWith("http://") || url.startsWith("https://")

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("URL", text))
        toast("Tautan disalin")
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "Bagikan tautan"))
    }

    // ---------- Unduhan dari respons Gecko (dengan cookie/sesi login yang benar) ----------
    private fun startDownload(response: WebResponse) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadResponse = response
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveResponse(response)
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun saveResponse(response: WebResponse) {
        val body = response.body
        if (body == null) {
            if (isWebUrl(response.uri)) enqueueDownload(response.uri)
            return
        }
        val mime = header(response.headers, "Content-Type")?.substringBefore(';')?.trim()
        val fileName = URLUtil.guessFileName(
            response.uri, header(response.headers, "Content-Disposition"), mime
        )
        toast("Mengunduh $fileName")
        Thread {
            val ok = try {
                writeToDownloads(body, fileName, mime)
                true
            } catch (_: Throwable) {
                false
            } finally {
                try {
                    body.close()
                } catch (_: Throwable) {
                }
            }
            runOnUiThread {
                toast(if (ok) "Unduhan selesai: $fileName" else "Unduhan gagal: $fileName")
            }
        }.start()
    }

    private fun writeToDownloads(input: InputStream, fileName: String, mime: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                if (!mime.isNullOrBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Gagal membuat berkas unduhan")
            try {
                resolver.openOutputStream(uri)!!.use { out -> input.copyTo(out) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            var target = File(dir, fileName)
            var n = 1
            val dot = fileName.lastIndexOf('.')
            val base = if (dot > 0) fileName.substring(0, dot) else fileName
            val ext = if (dot > 0) fileName.substring(dot) else ""
            while (target.exists()) target = File(dir, "$base ($n)$ext").also { n++ }
            target.outputStream().use { out -> input.copyTo(out) }
            MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), null, null)
        }
    }

    // ---------- Unduh gambar (URL langsung, tanpa cookie) ----------
    private fun startDownload(url: String) {
        if (!isWebUrl(url)) {
            toast("Jenis unduhan ini belum didukung")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownloadUrl = url
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        enqueueDownload(url)
    }

    private fun enqueueDownload(url: String) {
        try {
            val fileName = URLUtil.guessFileName(url, null, null)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            toast("Mengunduh $fileName")
        } catch (e: Exception) {
            toast("Gagal memulai unduhan")
        }
    }

    // ---------- Tekan lama pada link / gambar ----------
    private fun showLinkDialog(url: String, tab: Tab) {
        val options = arrayOf("Buka di tab baru", "Buka di tab ini", "Salin tautan", "Bagikan tautan")
        AlertDialog.Builder(this)
            .setTitle(url.take(80))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        hideTabSwitcher()
                        openNewTab(url, tab)
                    }
                    1 -> tab.session?.loadUri(url)
                    2 -> copyText(url)
                    3 -> shareText(url)
                }
            }
            .show()
    }

    private fun showImageDialog(url: String, tab: Tab) {
        val options = arrayOf("Unduh gambar", "Buka gambar di tab baru", "Salin URL gambar")
        AlertDialog.Builder(this)
            .setTitle(url.take(80))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startDownload(url)
                    1 -> {
                        hideTabSwitcher()
                        openNewTab(url, tab)
                    }
                    2 -> copyText(url)
                }
            }
            .show()
    }

    // ---------- Bookmark ----------
    private fun toggleBookmark() {
        val url = currentUrl()
        if (url.isNullOrBlank()) return
        if (bookmarks.contains(url)) {
            bookmarks.remove(url)
            toast("Bookmark dihapus")
        } else {
            bookmarks.add(url, activeTab?.title ?: "")
            toast("Bookmark ditambahkan")
        }
    }

    private fun twoLineAdapter(titles: List<String>, subtitles: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, titles
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(android.R.id.text1).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                v.findViewById<TextView>(android.R.id.text2).apply {
                    text = subtitles[position]
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                return v
            }
        }
    }

    private fun showBookmarks() {
        val entries = bookmarks.all()
        if (entries.isEmpty()) {
            toast("Belum ada bookmark")
            return
        }
        val titles = entries.map { it.title.ifBlank { it.url } }
        val urls = entries.map { it.url }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Bookmark (tekan lama untuk menghapus)")
            .setAdapter(twoLineAdapter(titles, urls)) { _, which ->
                hideTabSwitcher()
                activeTab?.session?.loadUri(entries[which].url)
            }
            .setNegativeButton("Tutup", null)
            .create()
        dialog.show()
        dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
            bookmarks.remove(entries[position].url)
            dialog.dismiss()
            toast("Bookmark dihapus")
            showBookmarks()
            true
        }
    }

    // ---------- Cari di halaman ----------
    private fun showFindResult(r: GeckoSession.FinderResult) {
        binding.findCount.text = when {
            !r.found -> "0/0"
            r.total > 0 -> "${r.current}/${r.total}"
            else -> ""
        }
    }

    private fun findNext(forward: Boolean) {
        val q = binding.findInput.text.toString()
        val s = activeTab?.session ?: return
        if (q.isEmpty()) return
        val flags = if (forward) GeckoSession.FINDER_FIND_FORWARD else GeckoSession.FINDER_FIND_BACKWARDS
        s.finder.find(q, flags).accept({ r -> if (r != null) showFindResult(r) }, { })
    }

    private fun setupFindBar() {
        binding.findInput.doAfterTextChanged { text ->
            val s = activeTab?.session ?: return@doAfterTextChanged
            val q = text?.toString().orEmpty()
            if (q.isEmpty()) {
                s.finder.clear()
                binding.findCount.text = ""
            } else {
                s.finder.find(q, GeckoSession.FINDER_FIND_FORWARD)
                    .accept({ r -> if (r != null) showFindResult(r) }, { })
            }
        }
        binding.findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                findNext(true)
                true
            } else {
                false
            }
        }
        binding.findNext.setOnClickListener { findNext(true) }
        binding.findPrev.setOnClickListener { findNext(false) }
        binding.findClose.setOnClickListener { hideFindBar() }
    }

    private fun showFindBar() {
        hideTabSwitcher()
        binding.findBar.visibility = View.VISIBLE
        binding.findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideFindBar() {
        if (binding.findBar.visibility != View.VISIBLE) return
        activeTab?.session?.finder?.clear()
        binding.findInput.setText("")
        binding.findCount.text = ""
        binding.findBar.visibility = View.GONE
        hideKeyboard(binding.root)
    }

    // ---------- Tab yang baru ditutup ----------
    private fun rememberClosed(tab: Tab) {
        val url = tab.url
        if (url.isBlank() || url == "about:blank") return
        closedTabs.remove(url)
        closedTabs.add(url)
        while (closedTabs.size > 10) closedTabs.removeAt(0)
    }

    private fun reopenClosedTab() {
        if (closedTabs.isEmpty()) return
        val url = closedTabs.removeAt(closedTabs.size - 1)
        hideTabSwitcher()
        openNewTab(url)
    }

    // ---------- Blokir iklan (perlindungan pelacakan bawaan Gecko) ----------
    private fun toggleAdBlock() {
        adBlockEnabled = !adBlockEnabled
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean(KEY_ADBLOCK, adBlockEnabled).apply()
        runtime.settings.contentBlocking.setAntiTracking(GeckoProvider.antiTracking(adBlockEnabled))
        tabs.forEach { it.session?.settings?.useTrackingProtection = adBlockEnabled }
        toast(if (adBlockEnabled) "Blokir iklan aktif" else "Blokir iklan dimatikan")
        activeTab?.session?.reload()
    }

    // ---------- Layar penuh ----------
    private fun setFullScreenUi(fullScreen: Boolean) {
        if (isFullScreen == fullScreen) return
        isFullScreen = fullScreen
        val barsVisibility = if (fullScreen) View.GONE else View.VISIBLE
        binding.topBar.visibility = barsVisibility
        binding.progressBar.visibility = barsVisibility
        binding.bottomDivider.visibility = barsVisibility
        binding.bottomBar.visibility = barsVisibility
        if (fullScreen) binding.findBar.visibility = View.GONE

        requestedOrientation = if (fullScreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (fullScreen) {
                systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // ==================================================================
    // Tombol Back fisik
    // ==================================================================
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val tab = activeTab
                when {
                    isFullScreen -> tab?.session?.exitFullScreen()
                    binding.findBar.visibility == View.VISIBLE -> hideFindBar()
                    isSwitcherVisible() -> hideTabSwitcher()
                    binding.urlEditText.hasFocus() -> {
                        binding.urlEditText.clearFocus()
                        binding.geckoView.requestFocus()
                    }
                    tab?.canGoBack == true -> tab.session?.goBack()
                    else -> {
                        // Tab yang dibuka dari link -> tutup dan kembali ke tab asal
                        val parent = tabs.firstOrNull { it.id == tab?.parentId }
                        if (tab != null && parent != null) {
                            closeTab(tab, parent)
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        })
    }
}

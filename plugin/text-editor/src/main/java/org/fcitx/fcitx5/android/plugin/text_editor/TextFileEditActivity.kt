/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ActivityTextFileEditBinding
import splitties.views.topPadding
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets

class TextFileEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTextFileEditBinding
    private lateinit var docUri: Uri
    private lateinit var prefs: SharedPreferences
    private var displayName: String = ""
    private var saveItem: MenuItem? = null
    private var undoItem: MenuItem? = null
    private var redoItem: MenuItem? = null
    private var wordWrap: Boolean = true
    private var showWhitespace: Boolean = false
    private var useTab: Boolean = true
    private var fileSizeBytes: Long = 0L
    private var isRegex: Boolean = false
    private var isCaseSensitive: Boolean = false
    // Snapshot of the on-disk file at load/save time, used to detect external modifications when
    // the activity returns to the foreground. loadedFileSize < 0 means "not loaded yet".
    private var loadedLastModified: Long = 0L
    private var loadedFileSize: Long = -1L
    private var externalChangeDialogShowing: Boolean = false
    private var externalCheckInFlight: Boolean = false
    private var fileOperationGeneration: Long = 0L
    private var fileOperationInProgress: Boolean = false
    // Large files use a lower-overhead layout, disable autocomplete and page content into the
    // editor, while retaining the filename-selected TextMate grammar for each loaded page.
    private var isLargeFile: Boolean = false
    private var observedTextSizeChangeId: Long = 0L
    private var lowMemoryMode: Boolean = false
    private var lowMemoryNoticeShown: Boolean = false
    private var lowMemoryDegradedAtLevel: Int = 0
    private val largeFilePagerMutex = Mutex()
    private lateinit var sharedColorScheme: EditorColorScheme
    private val crashHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Unhandled editor coroutine error")
        if (::binding.isInitialized) {
            binding.root.post {
                toast(getString(R.string.editor_runtime_recovered))
            }
        }
    }

    private val tabs = mutableListOf<EditorTab>()
    private var activeTabIndex: Int = 0
    private lateinit var tabBarAdapter: TabBarAdapter
    private val draftScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var menuRefreshPosted = false

    private fun activeEditor(): ArrowTabCodeEditor = tabs[activeTabIndex].editor!!
    private fun activeTab(): EditorTab = tabs[activeTabIndex]

    private val browseFilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data?.data == null) return@registerForActivityResult
        val uri = result.data!!.data!!
        val name = result.data!!.getStringExtra(FileBrowserActivity.EXTRA_RESULT_DISPLAY_NAME)
            ?: uri.lastPathSegment ?: "?"
        val length = result.data!!.getLongExtra(FileBrowserActivity.EXTRA_RESULT_LENGTH, -1L)
        openInNewTab(uri, name, null, length)
    }

    private val saveAsPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        saveFileAsUri(uri)
    }

    private fun createEditor(tab: EditorTab): ArrowTabCodeEditor {
        val editor = ArrowTabCodeEditor(this)
        editor.colorScheme = sharedColorScheme
        editor.setTextSize(readTextSize().toFloat())
        applyUserTypeface(editor)
        editor.tabWidth = readTabWidth()
        editor.setWordwrap(if (tab.isLargeFile) false else wordWrap)
        editor.nonPrintablePaintingFlags = whitespaceFlags()
        editor.props.disallowSuggestions = true
        editor.props.cacheRenderNodeForLongLines = !tab.isLargeFile
        editor.props.deleteEmptyLineFast = false
        installDefaultSymbolPairs(editor.props.overrideSymbolPairs)
        if (tab.isLargeFile) {
            editor.getComponent(EditorAutoCompletion::class.java).isEnabled = false
            editor.setHighlightCurrentBlock(false)
            editor.setHighlightCurrentLine(false)
            editor.setHighlightBracketPair(false)
            editor.setDiagnostics(null)
        }
        editor.subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent::class.java) {
            if (!tab.suppressDirtyTracking) {
                tab.editGeneration++
                tab.isDirty = true
                if (!tab.isLargeFile) scheduleDraftSave(tab)
            }
            scheduleMenuStateRefresh()
        }
        editor.subscribeAlways(PublishSearchResultEvent::class.java) {
            updateMatchInfo()
        }
        editor.subscribeAlways(ScrollEvent::class.java) {
            if (tab.isLargeFile && tabs.indexOf(tab) == activeTabIndex) {
                tryLoadNextLargeFilePage()
            }
        }
        return editor
    }

    private fun applyEditorLanguage(editor: ArrowTabCodeEditor, tab: EditorTab, fileSize: Long = 0L) {
        val scopeName = TextFileSupport.detectScopeName(tab.displayName)
        // Always set the proper language directly — deferred highlighting
        // (PlainLanguage → real language after 180ms) is only for the initial load.
        val language = TextMateSetup.createLanguage(scopeName, assets, useTab)
        editor.setEditorLanguage(language)
        if (scopeName != null) {
            editor.installOnlineBracketsMatcher()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ManualTheme.apply(this)
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) {
            finish()
            return
        }
        docUri = uri
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
            ?: uri.lastPathSegment
            ?: "?"

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        observedTextSizeChangeId = prefs.getLong(EditorOptionsActivity.PREF_TEXT_SIZE_CHANGE_ID, 0L)
        wordWrap = prefs.getBoolean(PREF_WORD_WRAP, true)
        showWhitespace = prefs.getBoolean(PREF_SHOW_WHITESPACE, false)
        useTab = prefs.getBoolean(PREF_USE_TAB, true)
        fileSizeBytes = intent.getLongExtra(EXTRA_FILE_SIZE, -1L).coerceAtLeast(0L)
        isLargeFile = TextFileSupport.isLargeFile(fileSizeBytes)
        // Force word-wrap off for large files — wrap layout reflows the entire buffer on width
        // changes. Don't persist this override; the user's saved pref still applies to small files.
        if (isLargeFile) wordWrap = false

        enableEdgeToEdge()
        binding = ActivityTextFileEditBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            // The keyBar floats above the IME, and the editor is constraint-anchored to keyBar's
            // top — so shrinking the editor when the keyboard opens just means pushing keyBar up.
            // bottomMargin (not padding) so the editor view actually shrinks; sora's completion
            // popup uses editor.getHeight() as its bottom bound, padding would leave it overlapping
            // the keyboard.
            binding.keyBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = maxOf(navBars.bottom, ime.bottom)
            }
            insets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
            title = displayName
            subtitle = sizeSubtitle()
        }

        setupSearchBar()
        setupKeyBar()
        setupTabBar()

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        TextMateSetup.applyTheme(isDark, assets)
        sharedColorScheme = TextMateSetup.createColorScheme(assets).apply {
            setColor(
                EditorColorScheme.NON_PRINTABLE_CHAR,
                if (isDark) 0x1ACCCCCC else 0x1A444444
            )
            setColor(
                EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND,
                if (isDark) 0x40FFFFFF else 0x30000000
            )
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, 0)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, 0)
        }

        val initialTab = EditorTab(
            uri = docUri,
            displayName = displayName,
            isLargeFile = isLargeFile,
        )
        val editor = createEditor(initialTab)
        initialTab.editor = editor
        binding.editorContainer.addView(editor, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        applyEditorLanguage(editor, initialTab, fileSizeBytes)
        tabs.add(initialTab)
        activeTabIndex = 0
        tabBarAdapter.notifyDataSetChanged()

        loadFile(initialTab)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.searchBar.visibility == View.VISIBLE) {
                    closeSearchBar()
                    return
                }
                if (tabs.size > 1) {
                    closeTab(activeTabIndex)
                    return
                }
                if (isDirty()) {
                    AlertDialog.Builder(this@TextFileEditActivity)
                        .setTitle(R.string.unsaved_changes)
                        .setMessage(R.string.confirm_discard_changes)
                        .setPositiveButton(R.string.discard_changes) { _, _ ->
                            discardTab(activeTab())
                            finish()
                        }
                        .setNeutralButton(R.string.save) { _, _ ->
                            saveFile { finish() }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    finish()
                }
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (LowMemoryPolicy.shouldDegrade(level)) {
            enterLowMemoryMode(level)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        enterLowMemoryMode(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(R.string.open_file).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                browseFilePicker.launch(Intent(this@TextFileEditActivity, FileBrowserActivity::class.java))
                true
            }
        }
        saveItem = null
        undoItem = null
        redoItem = null
        menu.add(R.string.find_replace).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener { openSearchBar(); true }
        }
        menu.add(R.string.editor_options).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                startActivity(Intent(this@TextFileEditActivity, EditorOptionsActivity::class.java))
                true
            }
        }
        menu.add(R.string.save_as).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                saveAsFile()
                true
            }
        }
        menu.add(R.string.word_wrap).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isCheckable = true
            isChecked = wordWrap
            isEnabled = !isLargeFile
            setOnMenuItemClickListener {
                wordWrap = !wordWrap
                isChecked = wordWrap
                activeEditor().setWordwrap(wordWrap)
                prefs.edit().putBoolean(PREF_WORD_WRAP, wordWrap).apply()
                true
            }
        }
        menu.add(R.string.show_whitespace).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isCheckable = true
            isChecked = showWhitespace
            setOnMenuItemClickListener {
                showWhitespace = !showWhitespace
                isChecked = showWhitespace
                activeEditor().nonPrintablePaintingFlags = whitespaceFlags()
                prefs.edit().putBoolean(PREF_SHOW_WHITESPACE, showWhitespace).apply()
                true
            }
        }
        menu.add(R.string.tab_inserts_spaces).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isCheckable = true
            isChecked = !useTab
            setOnMenuItemClickListener {
                useTab = !useTab
                isChecked = !useTab
                activeEditor().setEditorLanguage(
                    TextMateSetup.createLanguage(activeScopeName(), assets, useTab)
                )
                // setEditorLanguage runs styleDelegate.reset() → reinstall bracket matcher.
                activeEditor().installOnlineBracketsMatcher()
                prefs.edit().putBoolean(PREF_USE_TAB, useTab).apply()
                true
            }
        }
        updateMenuState()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        maybeRestoreFromLowMemoryMode()
        val newTabWidth = readTabWidth()
        val textSizeChangeId = prefs.getLong(EditorOptionsActivity.PREF_TEXT_SIZE_CHANGE_ID, 0L)
        val shouldApplyDefaultTextSize = EditorOptionsActivity.shouldApplyDefaultTextSize(
            observedTextSizeChangeId,
            textSizeChangeId,
        )
        if (shouldApplyDefaultTextSize) observedTextSizeChangeId = textSizeChangeId
        val newTextSize = if (shouldApplyDefaultTextSize) readTextSize().toFloat() else 0f
        tabs.forEach { tab ->
            tab.editor?.let { tabEditor ->
                if (tabEditor.tabWidth != newTabWidth) tabEditor.tabWidth = newTabWidth
                if (shouldApplyDefaultTextSize) tabEditor.setTextSize(newTextSize)
                applyUserTypeface(tabEditor)
            }
        }
        maybeCheckExternalChange()
    }

    override fun onPause() {
        super.onPause()
        persistDraftOnPause()
    }

    private fun readFileSnapshot(uri: Uri): ExternalFileSnapshot {
        val doc = if (uri.scheme == "file") DocumentFile.fromFile(File(uri.path!!))
            else DocumentFile.fromSingleUri(this, uri)
        return ExternalFileSnapshot(
            lastModified = doc?.lastModified() ?: 0L,
            size = doc?.length() ?: -1L,
        )
    }

    private suspend fun captureFileSnapshotAsync(targetTab: EditorTab = activeTab()) {
        val tab = targetTab
        val uri = tab.uri
        val snapshot = withContext(Dispatchers.IO) { readFileSnapshot(uri) }
        if (tab !in tabs || tab.uri != uri) return
        tab.loadedLastModified = snapshot.lastModified
        tab.loadedFileSize = snapshot.size
        if (activeTab() === tab) {
            loadedLastModified = snapshot.lastModified
            loadedFileSize = snapshot.size
            fileSizeBytes = snapshot.size.coerceAtLeast(0L)
        }
    }

    private fun captureFileSnapshot() {
        lifecycleScope.launch(crashHandler) { captureFileSnapshotAsync() }
    }

    private fun maybeCheckExternalChange() {
        // Not loaded yet, a prompt is already up, or a check is already running.
        if (loadedFileSize < 0 || externalChangeDialogShowing || externalCheckInFlight || fileOperationInProgress) return
        val mode = prefs.getString(
            EditorOptionsActivity.PREF_EXTERNAL_CHANGE,
            EditorOptionsActivity.EXTERNAL_CHANGE_PROMPT
        )
        if (mode == EditorOptionsActivity.EXTERNAL_CHANGE_OFF) return
        val generation = fileOperationGeneration
        val tab = activeTab()
        val uri = tab.uri
        val baseline = ExternalFileSnapshot(tab.loadedLastModified, tab.loadedFileSize)
        externalCheckInFlight = true
        lifecycleScope.launch(crashHandler) {
            // Some providers stat slowly — keep the metadata query off the main thread.
            val first = withContext(Dispatchers.IO) { readFileSnapshot(uri) }
            if (generation != fileOperationGeneration || tab !in tabs || tab.uri != uri) {
                externalCheckInFlight = false
                return@launch
            }
            if (first == baseline || !first.isUsable) {
                        externalCheckInFlight = false
                return@launch
            }
            // Confirm immediately with a second provider observation. This avoids repeated polling
            // and filters transient metadata values commonly returned while a document is opening.
            val observed = withContext(Dispatchers.IO) { readFileSnapshot(uri) }
            externalCheckInFlight = false
            if (externalChangeDialogShowing) return@launch
            if (generation != fileOperationGeneration || activeTab() !== tab || tab.uri != uri) return@launch
            if (!ExternalChangePolicy.isConfirmedChange(baseline, first, observed)) {
                return@launch
            }
            // Never silently discard unsaved edits — fall back to prompting even in auto mode.
            if (mode == EditorOptionsActivity.EXTERNAL_CHANGE_AUTO && !isDirty()) {
                reloadFromDisk()
            } else {
                promptExternalChange()
            }
        }
    }

    private fun promptExternalChange() {
        if (externalChangeDialogShowing) return
        externalChangeDialogShowing = true
        val message = if (isDirty()) {
            R.string.external_change_dialog_message_dirty
        } else {
            R.string.external_change_dialog_message
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.external_change_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.reload) { _, _ ->
                externalChangeDialogShowing = false
                reloadFromDisk()
            }
            .setNegativeButton(R.string.keep_editing) { _, _ ->
                externalChangeDialogShowing = false
                // Accept the current on-disk state as the new baseline so we stop re-prompting
                // for this same external change.
                captureFileSnapshot()
            }
            .setOnCancelListener {
                externalChangeDialogShowing = false
                captureFileSnapshot()
            }
            .show()
    }

    private fun reloadFromDisk() {
        if (isLargeFile) {
            runCatching { activeTab().largeFilePager?.close() }
            activeTab().largeFilePager = null
            activeTab().largeFileFullyLoaded = false
            activeTab().largeFileLoadInFlight = false
            activeTab().isDirty = false
            lifecycleScope.launch(crashHandler) {
                loadLargeFile(activeTab())
                toast(getString(R.string.file_reloaded))
            }
            return
        }
        lifecycleScope.launch(crashHandler) {
            val original = try {
                withContext(Dispatchers.IO) {
                    TextFileSupport.openInputStream(contentResolver, docUri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("openInputStream returned null")
                }
            } catch (e: Exception) {
                toast(getString(R.string.error_open_file, e.message ?: displayName))
                return@launch
            }
            activeTab().originalText = original
            val cursor = activeEditor().cursor
            val prevLine = cursor.leftLine
            val prevColumn = cursor.leftColumn
            withSuppressedDirtyTracking {
                activeEditor().setText(original)
            }
            activeTab().editGeneration = 0L
            activeTab().savedGeneration = 0L
            activeTab().isDirty = false
            // sora's setText triggers styleDelegate.reset(), which nulls bracketsProvider.
            activeEditor().installOnlineBracketsMatcher()
            runCatching {
                val line = prevLine.coerceIn(0, activeEditor().text.lineCount - 1)
                val col = prevColumn.coerceIn(0, activeEditor().text.getColumnCount(line))
                activeEditor().setSelection(line, col)
            }
            withContext(Dispatchers.IO) { runCatching { draftFileForUri(activeTab().uri).delete() } }
            captureFileSnapshot()
            updateMenuState()
            toast(getString(R.string.file_reloaded))
        }
    }

    private fun readTextSize(): Int =
        EditorOptionsActivity.clampTextSize(
            prefs.getInt(EditorOptionsActivity.PREF_TEXT_SIZE, EditorOptionsActivity.DEFAULT_TEXT_SIZE)
        )

    private fun readTabWidth(): Int =
        prefs.getInt(EditorOptionsActivity.PREF_TAB_WIDTH, EditorOptionsActivity.DEFAULT_TAB_WIDTH)
            .coerceIn(EditorOptionsActivity.MIN_TAB_WIDTH, EditorOptionsActivity.MAX_TAB_WIDTH)

    private fun applyUserTypeface(editor: io.github.rosemoe.sora.widget.CodeEditor) {
        val raw = prefs.getString(EditorOptionsActivity.PREF_FONT_FALLBACK, null).orEmpty()
        val files = EditorOptionsActivity.enabledFontFiles(raw)
        val tf = FontLoader.loadTypeface(this, files)
        // Identity check — FontLoader caches and returns the same Typeface across calls when the
        // resolved set hasn't changed; avoids resetting the editor's typeface (and the layout
        // recompute it triggers) on every onResume.
        if (editor.typefaceText !== tf) editor.typefaceText = tf
        if (editor.typefaceLineNumber !== tf) editor.typefaceLineNumber = tf
    }

    private fun activeScopeName(): String? =
        if (lowMemoryMode) null else TextFileSupport.detectScopeName(displayName)

    private fun installDefaultSymbolPairs(target: SymbolPairMatch) {
        target.putPair('(', SymbolPairMatch.SymbolPair("(", ")"))
        target.putPair('[', SymbolPairMatch.SymbolPair("[", "]"))
        target.putPair('{', SymbolPairMatch.SymbolPair("{", "}"))
        // Quotes also surround a selection — typing " with text selected wraps it instead of replacing.
        val surroundOnSelection = object : SymbolPairMatch.SymbolPair.SymbolPairEx {
            override fun shouldDoAutoSurround(content: io.github.rosemoe.sora.text.Content): Boolean =
                content.cursor.isSelected
        }
        target.putPair('"', SymbolPairMatch.SymbolPair("\"", "\"", surroundOnSelection))
        target.putPair('\'', SymbolPairMatch.SymbolPair("'", "'", surroundOnSelection))
        target.putPair('`', SymbolPairMatch.SymbolPair("`", "`", surroundOnSelection))
    }

    private fun whitespaceFlags(): Int =
        if (showWhitespace) {
            // FOR_EMPTY_LINE is what paints leading whitespace on lines whose content is *only*
            // whitespace — without it, indented blank lines look completely blank.
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
                CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
                CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
                CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        } else {
            0
        }

    private fun buildSearchOptions(): EditorSearcher.SearchOptions {
        return EditorSearcher.SearchOptions(isCaseSensitive, isRegex)
    }

    private fun refreshSearchToggles() {
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val accent = if (isDark) 0x40FFFFFF else 0x1A000000

        binding.findRegex.setBackgroundColor(if (isRegex) accent else android.graphics.Color.TRANSPARENT)
        binding.findCase.setBackgroundColor(if (isCaseSensitive) accent else android.graphics.Color.TRANSPARENT)
    }

    private fun reRunSearch() {
        val query = binding.findInput.text?.toString().orEmpty()
        if (query.isEmpty()) {
            activeEditor().searcher.stopSearch()
        } else {
            activeEditor().searcher.search(query, buildSearchOptions())
        }
        updateMatchInfo()
    }

    private fun setupSearchBar() {
        binding.findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                reRunSearch()
            }
        })
        binding.findPrev.setOnClickListener {
            if (activeEditor().searcher.hasQuery()) activeEditor().searcher.gotoPrevious()
        }
        binding.findNext.setOnClickListener {
            if (activeEditor().searcher.hasQuery()) activeEditor().searcher.gotoNext()
        }
        binding.searchClose.setOnClickListener { closeSearchBar() }
        binding.findRegex.setOnClickListener {
            isRegex = !isRegex
            refreshSearchToggles()
            reRunSearch()
        }
        binding.findCase.setOnClickListener {
            isCaseSensitive = !isCaseSensitive
            refreshSearchToggles()
            reRunSearch()
        }
        binding.replaceOne.setOnClickListener {
            if (!activeEditor().searcher.hasQuery()) return@setOnClickListener
            activeEditor().searcher.replaceThis(binding.replaceInput.text.toString())
        }
        binding.replaceAll.setOnClickListener {
            if (!activeEditor().searcher.hasQuery()) return@setOnClickListener
            activeEditor().searcher.replaceAll(binding.replaceInput.text.toString())
        }
    }

    private fun openSearchBar() {
        binding.searchBar.visibility = View.VISIBLE
        refreshSearchToggles()
        binding.findInput.requestFocus()
        val query = binding.findInput.text?.toString().orEmpty()
        if (query.isNotEmpty()) {
            activeEditor().searcher.search(query, buildSearchOptions())
        }
        updateMatchInfo()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearchBar() {
        activeEditor().searcher.stopSearch()
        binding.searchBar.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.findInput.windowToken, 0)
        activeEditor().requestFocus()
    }

    private fun setupKeyBar() {
        binding.keySave.setOnClickListener {
            if (isDirty()) saveFile()
        }
        binding.keyUndo.setOnClickListener {
            if (activeEditor().canUndo()) activeEditor().undo()
        }
        binding.keyRedo.setOnClickListener {
            if (activeEditor().canRedo()) activeEditor().redo()
        }
        binding.keyTab.setOnClickListener { sendKey(KeyEvent.KEYCODE_TAB) }
        binding.keyHome.setOnClickListener { sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        binding.keyEnd.setOnClickListener { sendKey(KeyEvent.KEYCODE_MOVE_END) }
        binding.keyLeft.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        binding.keyUp.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        binding.keyDown.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        binding.keyRight.setOnClickListener { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        // Recompute even-fill vs scroll whenever the keyBar's width changes (orientation,
        // multi-window). The first pass also runs after the initial layout via post().
        binding.keysScroll.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) applyKeyBarDistribution()
        }
        binding.keysScroll.post { applyKeyBarDistribution() }
    }

    private fun setupTabBar() {
        tabBarAdapter = TabBarAdapter(
            tabs = tabs,
            activeIndex = { activeTabIndex },
            onTabClick = { index -> switchToTab(index) },
            onTabClose = { index -> closeTab(index) },
        )
        binding.tabBar.layoutManager = LinearLayoutManager(
            this, LinearLayoutManager.HORIZONTAL, false
        )
        binding.tabBar.adapter = tabBarAdapter
    }

    private fun switchToTab(index: Int) {
        if (index == activeTabIndex || index !in tabs.indices) return
        val oldIndex = activeTabIndex
        val oldEditor = activeEditor()
        activeTabIndex = index
        val tab = tabs[index]
        docUri = tab.uri
        displayName = tab.displayName
        isLargeFile = tab.isLargeFile
        loadedLastModified = tab.loadedLastModified
        loadedFileSize = tab.loadedFileSize
        fileSizeBytes = tab.loadedFileSize.coerceAtLeast(0L)
        supportActionBar?.title = displayName
        supportActionBar?.subtitle = if (tab.isDirty) {
            getString(R.string.unsaved_changes)
        } else {
            sizeSubtitle()
        }
        val newEditor = tab.editor!!
        newEditor.bringToFront()
        newEditor.visibility = View.VISIBLE
        oldEditor.visibility = View.GONE
        newEditor.requestFocus()
        closeSearchBar()
        updateMenuState()
        tabBarAdapter.notifyItemChanged(oldIndex)
        tabBarAdapter.notifyItemChanged(index)
        if (tab.isLargeFile && !tab.largeFileFullyLoaded) {
            tryLoadNextLargeFilePage(force = true)
        }
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs[index]
        if (tab.isDirty) {
            AlertDialog.Builder(this)
                .setTitle(tab.displayName)
                .setMessage(R.string.confirm_discard_changes)
                .setPositiveButton(R.string.discard_changes) { _, _ ->
                    discardTab(tab)
                    doCloseTab(index)
                }
                .setNeutralButton(R.string.save) { _, _ ->
                    if (index != activeTabIndex) {
                        switchToTab(index)
                    }
                    saveFile { doCloseTab(tabs.indexOf(tab)) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            doCloseTab(index)
        }
    }

    private fun doCloseTab(index: Int) {
        if (index !in tabs.indices) return
        val wasActive = index == activeTabIndex
        val closedTab = tabs[index]
        val closedEditor = closedTab.editor
        closedTab.draftJob?.cancel()
        binding.editorContainer.removeView(closedEditor)
        closedEditor?.release()
        runCatching { closedTab.largeFilePager?.close() }
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            finish()
            return
        }
        if (wasActive) {
            val newIndex = index.coerceAtMost(tabs.lastIndex)
            activeTabIndex = newIndex
            val tab = tabs[newIndex]
            docUri = tab.uri
            displayName = tab.displayName
            isLargeFile = tab.isLargeFile
            loadedLastModified = tab.loadedLastModified
            loadedFileSize = tab.loadedFileSize
            fileSizeBytes = tab.loadedFileSize.coerceAtLeast(0L)
            supportActionBar?.title = displayName
            val newEditor = tab.editor!!
            newEditor.visibility = View.VISIBLE
            newEditor.requestFocus()
            if (tab.isLargeFile && !tab.largeFileFullyLoaded) {
                tryLoadNextLargeFilePage(force = true)
            }
        } else if (index < activeTabIndex) {
            activeTabIndex--
        }
        updateMenuState()
        tabBarAdapter.notifyDataSetChanged()
    }

    private fun openInNewTab(uri: Uri, displayName: String, mime: String?, length: Long) {
        lifecycleScope.launch(crashHandler) {
            val isText = TextFileSupport.isProbablyTextFileAsync(contentResolver, uri, displayName, mime)
            if (!isText) {
                toast(getString(R.string.binary_file_unsupported))
                return@launch
            }
            openVerifiedTextInNewTab(uri, displayName, length)
        }
    }

    private fun openVerifiedTextInNewTab(uri: Uri, displayName: String, length: Long) {
        if (length > TextFileSupport.MAX_FILE_SIZE) {
            toast(getString(R.string.file_too_large, formatSize(length)))
            return
        }
        val existingIndex = tabs.indexOfFirst { it.uri == uri }
        if (existingIndex >= 0) {
            switchToTab(existingIndex)
            return
        }
        val isLarge = TextFileSupport.isLargeFile(length)
        val tab = EditorTab(
            uri = uri,
            displayName = displayName,
            isLargeFile = isLarge,
            loadedFileSize = length,
        )
        val editor = createEditor(tab)
        tab.editor = editor
        binding.editorContainer.addView(editor, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        applyEditorLanguage(editor, tab, length)
        tabs.add(tab)
        activeTabIndex = tabs.lastIndex
        docUri = uri
        this.displayName = displayName
        isLargeFile = isLarge
        fileSizeBytes = length
        loadedLastModified = 0L
        loadedFileSize = length
        supportActionBar?.title = displayName
        supportActionBar?.subtitle = sizeSubtitle()
        closeSearchBar()
        loadFile(tab)
        tabBarAdapter.notifyDataSetChanged()
        binding.tabBar.scrollToPosition(activeTabIndex)
    }

    // Even-fill when all keys fit the viewport; fall back to natural width + horizontal scroll
    // when they don't. Avoids leaving a big empty gap on tablets/landscape and keeps small phones
    // scrollable instead of squishing keys below their minimum tap target.
    private fun applyKeyBarDistribution() {
        val container = binding.keysContainer
        val viewport = binding.keysScroll.width
        if (viewport <= 0 || container.childCount == 0) return
        // EditorKey style declares 30dp per key — that's the target width when not stretching.
        // Use it instead of measuring TextView text bounds, which would be narrower than the tap target.
        val naturalTotal = keyBarKeyWidthPx * container.childCount
        val fits = naturalTotal <= viewport
        val targetContainerWidth =
            if (fits) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (container.layoutParams.width != targetContainerWidth) {
            container.layoutParams.width = targetContainerWidth
        }
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val lp = child.layoutParams as LinearLayout.LayoutParams
            if (fits) {
                lp.width = 0
                lp.weight = 1f
            } else {
                lp.width = keyBarKeyWidthPx
                lp.weight = 0f
            }
            child.layoutParams = lp
        }
        container.requestLayout()
    }

    // EditorKey style declares 30dp; cache the pixel value so re-distribution doesn't have to
    // re-resolve it on every pass.
    private val keyBarKeyWidthPx: Int by lazy {
        (30 * resources.displayMetrics.density).toInt()
    }

    private fun sendKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        activeEditor().dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        activeEditor().dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun updateMatchInfo() {
        val searcher = activeEditor().searcher
        val query = binding.findInput.text?.toString().orEmpty()
        binding.matchInfo.text = when {
            query.isEmpty() -> ""
            !searcher.hasQuery() -> ""
            searcher.matchedPositionCount == 0 -> getString(R.string.no_matches)
            else -> "${searcher.currentMatchedPositionIndex + 1}/${searcher.matchedPositionCount}"
        }
    }

    private fun scheduleMenuStateRefresh() {
        if (menuRefreshPosted || !::binding.isInitialized) return
        menuRefreshPosted = true
        binding.root.post {
            menuRefreshPosted = false
            if (!isFinishing && tabs.isNotEmpty()) updateMenuState()
        }
    }

    private fun updateMenuState(refreshTab: Boolean = true) {
        val dirty = isDirty()
        val canUndo = activeEditor().canUndo()
        val canRedo = activeEditor().canRedo()
        saveItem?.isEnabled = dirty
        undoItem?.isEnabled = canUndo
        redoItem?.isEnabled = canRedo
        binding.keySave.isEnabled = dirty
        binding.keySave.alpha = if (dirty) 1f else 0.4f
        binding.keyUndo.isEnabled = canUndo
        binding.keyUndo.alpha = if (canUndo) 1f else 0.4f
        binding.keyRedo.isEnabled = canRedo
        binding.keyRedo.alpha = if (canRedo) 1f else 0.4f
        supportActionBar?.subtitle = if (dirty) {
            getString(R.string.unsaved_changes)
        } else {
            sizeSubtitle()
        }
        if (refreshTab && ::tabBarAdapter.isInitialized) {
            tabBarAdapter.notifyItemChanged(activeTabIndex)
        }
    }

    private fun sizeSubtitle(): String {
        val size = formatSize(fileSizeBytes.coerceAtLeast(0L))
        return if (isLargeFile) "$size · ${getString(R.string.plain_mode)}" else size
    }

    private fun loadFile(targetTab: EditorTab) {
        val targetUri = targetTab.uri
        val targetEditor = targetTab.editor ?: return
        lifecycleScope.launch(crashHandler) {
            if (targetTab.isLargeFile && !TextFileSupport.isShizukuUri(targetTab.uri)) {
                loadLargeFile(targetTab)
                return@launch
            }
            val original = try {
                if (TextFileSupport.isShizukuUri(targetUri)) {
                    ShizukuFileReader(this@TextFileEditActivity).readText(targetUri.path!!)
                } else withContext(Dispatchers.IO) {
                    TextFileSupport.openInputStream(contentResolver, targetUri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("openInputStream returned null")
                }
            } catch (e: Exception) {
                toast(getString(R.string.error_open_file, e.message ?: targetTab.displayName))
                return@launch
            }
            if (!isValidLoadTarget(targetTab, targetUri, targetEditor)) return@launch
            val sourceModified = withContext(Dispatchers.IO) {
                readFileSnapshot(targetUri).lastModified
            }
            val draft = withContext(Dispatchers.IO) {
                runCatching {
                    val f = draftFileForUri(targetUri)
                    if (f.exists() && f.lastModified() >= sourceModified) f.readText() else null
                }.getOrNull()
            }
            if (!isValidLoadTarget(targetTab, targetUri, targetEditor)) return@launch
            targetTab.originalText = original
            withSuppressedDirtyTracking(targetTab) {
                targetEditor.setText(draft ?: original)
            }
            targetTab.editGeneration = if (draft != null) 1L else 0L
            targetTab.savedGeneration = 0L
            targetTab.isDirty = draft != null
            targetEditor.installOnlineBracketsMatcher()
            captureFileSnapshotAsync(targetTab)
            if (tabs.getOrNull(activeTabIndex) === targetTab) updateMenuState()
        }
    }

    private fun isValidLoadTarget(tab: EditorTab, uri: Uri, editor: ArrowTabCodeEditor): Boolean =
        tab in tabs && tab.uri == uri && tab.editor === editor

    private suspend fun loadLargeFile(targetTab: EditorTab) {
        val targetUri = targetTab.uri
        val targetEditor = targetTab.editor ?: return
        val pager = try {
            withContext(Dispatchers.IO) { LargeFilePager(this@TextFileEditActivity, targetUri) }
        } catch (e: Exception) {
            toast(getString(R.string.error_open_file, e.message ?: targetTab.displayName))
            return
        }
        targetTab.largeFilePager = pager
        // Publish the first page immediately. Sora's initial TextMate pass only publishes styles
        // after every currently loaded line has been tokenized; feeding the complete large file
        // here made a 400k-line dictionary look permanently unhighlighted. Remaining pages are
        // appended as the user approaches the loaded end and are analyzed incrementally.
        // Sora tokenizes this whole page before publishing a single style, so the first page — not
        // the steady-state 1 MB page — decides how long the file reads as plain text.
        val firstPageBytes = TextFileSupport.largeFileInitialPageBytes(targetTab.loadedFileSize)
        val content = try {
            withContext(Dispatchers.IO) { pager.readNextTextPage(firstPageBytes).orEmpty() }
        } catch (e: Exception) {
            pager.close()
            targetTab.largeFilePager = null
            toast(getString(R.string.error_open_file, e.message ?: targetTab.displayName))
            return
        }
        if (!isValidLoadTarget(targetTab, targetUri, targetEditor)) {
            pager.close()
            return
        }
        targetTab.originalText = ""
        targetTab.isDirty = false
        targetTab.editGeneration = 0L
        targetTab.savedGeneration = 0L
        withSuppressedDirtyTracking(targetTab) { targetEditor.setText(content) }
        targetEditor.installOnlineBracketsMatcher()
        targetTab.largeFileFullyLoaded = pager.isFullyConsumed
        if (targetTab.largeFileFullyLoaded) {
            pager.close()
            targetTab.largeFilePager = null
        }
        captureFileSnapshotAsync(targetTab)
        if (tabs.getOrNull(activeTabIndex) === targetTab) {
            updateMenuState()
            if (!targetTab.largeFileFullyLoaded) tryLoadNextLargeFilePage(force = true)
        }
    }

    private fun tryLoadNextLargeFilePage(force: Boolean = false) {
        if (!isLargeFile || activeTab().largeFileLoadInFlight || activeTab().largeFileFullyLoaded) return
        val pager = activeTab().largeFilePager ?: return
        val remainingScroll = activeEditor().scrollMaxY - activeEditor().offsetY
        if (!force && remainingScroll > activeEditor().height * PREFETCH_VIEWPORT_MULTIPLIER) return

        activeTab().largeFileLoadInFlight = true
        lifecycleScope.launch(crashHandler) {
            try {
                largeFilePagerMutex.withLock {
                    val nextPage = withContext(Dispatchers.IO) { pager.readNextTextPage() }
                    if (nextPage.isNullOrEmpty()) {
                        if (pager.isFullyConsumed) {
                            activeTab().largeFileFullyLoaded = true
                            pager.close()
                            activeTab().largeFilePager = null
                        }
                    } else {
                        withSuppressedDirtyTracking {
                            appendTextToEditor(nextPage)
                        }
                        if (pager.isFullyConsumed) {
                            activeTab().largeFileFullyLoaded = true
                            pager.close()
                            activeTab().largeFilePager = null
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed paging read for $docUri")
                activeTab().largeFileFullyLoaded = true
                runCatching { pager.close() }
                activeTab().largeFilePager = null
                toast(getString(R.string.error_open_file, e.message ?: displayName))
            } finally {
                activeTab().largeFileLoadInFlight = false
                updateMenuState(refreshTab = false)
            }
        }
    }

    private fun appendTextToEditor(text: String) {
        if (text.isEmpty()) return
        val content = activeEditor().text
        val lastLine = content.lineCount - 1
        val lastColumn = content.getColumnCount(lastLine)
        content.insert(lastLine, lastColumn, text)
    }

    private inline fun withSuppressedDirtyTracking(
        tab: EditorTab = activeTab(),
        block: () -> Unit,
    ) {
        val old = tab.suppressDirtyTracking
        tab.suppressDirtyTracking = true
        try {
            block()
        } finally {
            tab.suppressDirtyTracking = old
        }
    }

    private fun isDirty(): Boolean = activeTab().isDirty

    private fun saveFile(onSuccess: (() -> Unit)? = null) {
        fileOperationGeneration++
        fileOperationInProgress = true
        val targetTab = activeTab()
        val targetUri = targetTab.uri
        lifecycleScope.launch(crashHandler) {
            try {
                if (targetTab.isLargeFile) {
                    loadRemainingLargeFilePagesForSave()
                }
                val savedVersion = targetTab.editGeneration
                val content = targetTab.editor?.text?.toString() ?: return@launch
                withContext(Dispatchers.IO) {
                    // "wt" = truncate-and-write. Without 't', some providers append rather than
                    // overwrite, leaving stale tail bytes when the new content is shorter.
                    if (TextFileSupport.isShizukuUri(targetUri)) {
                        ShizukuFileReader(this@TextFileEditActivity).writeText(targetUri.path!!, content)
                    } else TextFileSupport.openOutputStream(contentResolver, targetUri)?.use {
                        it.write(content.toByteArray())
                    } ?: error("openOutputStream returned null")
                    runCatching { draftFileForUri(targetUri).delete() }
                }
                targetTab.originalText = content
                targetTab.savedGeneration = savedVersion
                if (targetTab.editGeneration == savedVersion) targetTab.isDirty = false
                getSharedPreferences(RECENT_EDIT_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(targetUri.toString(), true).apply()
                if (tabs.indexOf(targetTab) == activeTabIndex) captureFileSnapshotAsync()
                toast(getString(R.string.saved))
                updateMenuState()
                onSuccess?.invoke()
            } catch (e: Exception) {
                Timber.e(e, "Failed to save $docUri")
                toast(getString(R.string.error_save_file, e.message ?: ""))
            } finally {
                fileOperationInProgress = false
            }
        }
    }

    private fun saveAsFile() {
        val defaultName = activeTab().displayName.ifEmpty {
            "untitled.txt"
        }
        saveAsPicker.launch(defaultName)
    }

    private fun saveFileAsUri(uri: Uri) {
        fileOperationGeneration++
        fileOperationInProgress = true
        lifecycleScope.launch(crashHandler) {
            try {
                val tab = activeTab()
                val savedVersion = tab.editGeneration
                val content = activeEditor().text.toString()
                val resolvedName = withContext(Dispatchers.IO) {
                    TextFileSupport.openOutputStream(contentResolver, uri)?.use {
                        it.write(content.toByteArray())
                    } ?: error("openOutputStream returned null")
                    DocumentFile.fromSingleUri(this@TextFileEditActivity, uri)?.name
                }
                tab.originalText = content
                tab.savedGeneration = savedVersion
                if (tab.editGeneration == savedVersion) tab.isDirty = false
                // Switch the tab's URI to the new location
                tab.uri = uri
                docUri = uri
                displayName = resolvedName ?: uri.lastPathSegment ?: displayName
                tab.displayName = displayName
                supportActionBar?.title = displayName
                captureFileSnapshotAsync()
                toast(getString(R.string.saved))
                updateMenuState()
                tabBarAdapter.notifyDataSetChanged()
            } catch (e: Exception) {
                Timber.e(e, "Failed to save as $uri")
                toast(getString(R.string.error_save_file, e.message ?: ""))
            } finally {
                fileOperationInProgress = false
            }
        }
    }

    private fun persistDraftOnPause() {
        if (!::docUri.isInitialized || !::binding.isInitialized) return
        tabs.filter { !it.isLargeFile && it.isDirty && !it.suppressDraft }
            .forEach { scheduleDraftSave(it, immediate = true) }
    }

    private fun scheduleDraftSave(tab: EditorTab, immediate: Boolean = false) {
        if (tab.isLargeFile || tab.suppressDraft) return
        tab.draftJob?.cancel()
        val generation = tab.editGeneration
        val snapshot = tab.editor?.text?.toString() ?: return
        val target = draftFileForUri(tab.uri)
        tab.draftJob = draftScope.launch {
            if (!immediate) delay(DRAFT_SAVE_DEBOUNCE_MS)
            if (tab.suppressDraft || !tab.isDirty || tab.editGeneration != generation) return@launch
            runCatching {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeText(snapshot)
                if (!temp.renameTo(target)) {
                    target.writeText(snapshot)
                    temp.delete()
                }
            }.onFailure { Timber.w(it, "Failed to persist draft for ${tab.uri}") }
        }
    }

    private fun discardTab(tab: EditorTab) {
        tab.suppressDraft = true
        tab.isDirty = false
        tab.draftJob?.cancel()
        draftScope.launch { runCatching { draftFileForUri(tab.uri).delete() } }
    }

    private fun draftFileForUri(uri: Uri): File {
        val key = uri.toString()
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "fileedit/$hex.draft")
    }

    private suspend fun loadRemainingLargeFilePagesForSave() {
        if (!isLargeFile || activeTab().largeFileFullyLoaded) return
        val pager = activeTab().largeFilePager ?: return
        activeTab().largeFileLoadInFlight = true
        try {
            largeFilePagerMutex.withLock {
                while (true) {
                    val nextPage = withContext(Dispatchers.IO) { pager.readNextTextPage() } ?: break
                    if (nextPage.isNotEmpty()) {
                        withSuppressedDirtyTracking {
                            appendTextToEditor(nextPage)
                        }
                    }
                }
                if (pager.isFullyConsumed) {
                    activeTab().largeFileFullyLoaded = true
                    pager.close()
                    activeTab().largeFilePager = null
                }
            }
        } finally {
            activeTab().largeFileLoadInFlight = false
        }
    }

    private fun enterLowMemoryMode(level: Int) {
        if (!::binding.isInitialized || lowMemoryMode) return
        lowMemoryMode = true
        lowMemoryDegradedAtLevel = level
        runCatching {
            tabs.forEach { tab ->
                val editor = tab.editor ?: return@forEach
                editor.setStyles(null)
                editor.setDiagnostics(null)
                editor.setEditorLanguage(TextMateSetup.createLanguage(null, assets, useTab))
                editor.installOnlineBracketsMatcher()
                editor.getComponent(EditorAutoCompletion::class.java).isEnabled = false
                editor.props.cacheRenderNodeForLongLines = false
                editor.props.disallowSuggestions = true
            }
            if (!lowMemoryNoticeShown) {
                lowMemoryNoticeShown = true
                toast(getString(R.string.low_memory_features_disabled))
            }
            Timber.w("Entered low memory mode, level=$level")
        }.onFailure {
            Timber.e(it, "Failed to degrade editor on low memory")
        }
    }

    private fun maybeRestoreFromLowMemoryMode() {
        if (!lowMemoryMode || !::binding.isInitialized) return
        if (!LowMemoryPolicy.shouldRestoreOnResume(lowMemoryDegradedAtLevel)) return
        lowMemoryMode = false
        lowMemoryDegradedAtLevel = 0
        runCatching {
            tabs.forEach { tab ->
                val editor = tab.editor ?: return@forEach
                editor.props.cacheRenderNodeForLongLines = !tab.isLargeFile
                editor.getComponent(EditorAutoCompletion::class.java).isEnabled = !tab.isLargeFile
                if (tab.isLargeFile) {
                    editor.setHighlightCurrentBlock(false)
                    editor.setHighlightCurrentLine(false)
                    editor.setHighlightBracketPair(false)
                    editor.setDiagnostics(null)
                }
                applyEditorLanguage(editor, tab)
                editor.installOnlineBracketsMatcher()
            }
            updateMenuState(refreshTab = true)
            Timber.i("Restored editor features after returning to the foreground")
        }.onFailure {
            Timber.e(it, "Failed to restore editor features on low memory exit")
        }
    }

    override fun onDestroy() {
        for (tab in tabs) {
            runCatching { tab.largeFilePager?.close() }
            tab.largeFilePager = null
            tab.editor?.release()
        }
        super.onDestroy()
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_FILE_SIZE = "file_size"
        private const val RECENT_EDIT_PREFS = "recent_edit_transient"
        private const val PREFS_NAME = "text_editor"
        private const val PREF_WORD_WRAP = "word_wrap"
        private const val PREF_SHOW_WHITESPACE = "show_whitespace"
        private const val PREF_USE_TAB = "use_tab"
        private const val PREFETCH_VIEWPORT_MULTIPLIER = 2
        private const val DRAFT_SAVE_DEBOUNCE_MS = 750L
    }

    internal class LargeFilePager(
        private val context: Context,
        private val uri: Uri,
    ) : AutoCloseable {

        private var pfd: ParcelFileDescriptor? = null
        private var channel: FileChannel? = null
        private var mappedSize: Long = 0L
        private var mappedOffset: Long = 0L
        private var stream: BufferedInputStream? = null
        private var sourceExhausted: Boolean = false
        private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        private var carry: ByteArray = ByteArray(0)

        val isFullyConsumed: Boolean
            get() = sourceExhausted && carry.isEmpty()

        init {
            if (!openMappedSource()) {
                openStreamSource()
            }
        }

        fun readNextTextPage(maxBytes: Int = TextFileSupport.LARGE_FILE_PAGE_BYTES): String? {
            if (isFullyConsumed) return null
            val bytes = when {
                channel != null -> readMappedBytes(maxBytes)
                stream != null -> readStreamBytes(maxBytes)
                else -> ByteArray(0)
            }
            if (bytes.isEmpty() && isFullyConsumed) {
                return null
            }
            return decodeUtf8(bytes, sourceExhausted)
        }

        private fun openMappedSource(): Boolean {
            return try {
                val descriptor = if (uri.scheme == "file") {
                    ParcelFileDescriptor.open(File(uri.path!!), ParcelFileDescriptor.MODE_READ_ONLY)
                } else context.contentResolver.openFileDescriptor(uri, "r") ?: return false
                val fileChannel = FileInputStream(descriptor.fileDescriptor).channel
                mappedSize = fileChannel.size()
                pfd = descriptor
                channel = fileChannel
                sourceExhausted = mappedSize == 0L
                true
            } catch (_: Exception) {
                runCatching { channel?.close() }
                runCatching { pfd?.close() }
                channel = null
                pfd = null
                false
            }
        }

        private fun openStreamSource() {
            stream = BufferedInputStream(
                TextFileSupport.openInputStream(context.contentResolver, uri)
                    ?: error("openInputStream returned null")
            )
            sourceExhausted = false
        }

        private fun readMappedBytes(maxBytes: Int): ByteArray {
            val fileChannel = channel ?: return ByteArray(0)
            val remaining = mappedSize - mappedOffset
            if (remaining <= 0) {
                sourceExhausted = true
                return ByteArray(0)
            }
            val toRead = minOf(maxBytes.toLong(), remaining).toInt()
            val mapped = fileChannel.map(FileChannel.MapMode.READ_ONLY, mappedOffset, toRead.toLong())
            val out = ByteArray(toRead)
            mapped.get(out)
            mappedOffset += toRead
            if (mappedOffset >= mappedSize) sourceExhausted = true
            return out
        }

        private fun readStreamBytes(maxBytes: Int): ByteArray {
            val input = stream ?: return ByteArray(0)
            val buf = ByteArray(maxBytes)
            val read = input.read(buf)
            if (read < 0) {
                sourceExhausted = true
                return ByteArray(0)
            }
            return if (read == buf.size) buf else buf.copyOf(read)
        }

        private fun decodeUtf8(bytes: ByteArray, endInput: Boolean): String {
            val merged = if (carry.isEmpty()) {
                bytes
            } else {
                ByteArray(carry.size + bytes.size).also {
                    carry.copyInto(it, 0)
                    bytes.copyInto(it, carry.size)
                }
            }
            val inBuffer = ByteBuffer.wrap(merged)
            var outBuffer = CharBuffer.allocate((merged.size * decoder.maxCharsPerByte()).toInt() + 8)
            while (true) {
                val result = decoder.decode(inBuffer, outBuffer, endInput)
                if (result.isOverflow) {
                    val grown = CharBuffer.allocate(outBuffer.capacity() * 2)
                    outBuffer.flip()
                    grown.put(outBuffer)
                    outBuffer = grown
                    continue
                }
                if (result.isError) result.throwException()
                break
            }
            if (endInput) {
                while (true) {
                    val flush = decoder.flush(outBuffer)
                    if (flush.isOverflow) {
                        val grown = CharBuffer.allocate(outBuffer.capacity() * 2)
                        outBuffer.flip()
                        grown.put(outBuffer)
                        outBuffer = grown
                        continue
                    }
                    if (flush.isError) flush.throwException()
                    break
                }
                decoder.reset()
                carry = ByteArray(0)
            } else {
                val remain = inBuffer.remaining()
                carry = ByteArray(remain)
                if (remain > 0) inBuffer.get(carry)
            }
            outBuffer.flip()
            return outBuffer.toString()
        }

        override fun close() {
            runCatching { stream?.close() }
            runCatching { channel?.close() }
            runCatching { pfd?.close() }
            stream = null
            channel = null
            pfd = null
            sourceExhausted = true
            carry = ByteArray(0)
            decoder.reset()
        }
    }
}

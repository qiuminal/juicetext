/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.app.Dialog
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.io.File
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
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
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ActivityFileBrowserBinding
import org.fcitx.fcitx5.android.plugin.text_editor.databinding.ItemFileEntryBinding
import rikka.shizuku.Shizuku

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var prefs: SharedPreferences

    private var rootTree: DocumentFile? = null
    private var currentDir: DocumentFile? = null
    private var currentParent: DocumentFile? = null
    private var currentPath = ""
    private var shizukuMode = false
    private var shizukuPath = ""
    private val entries = mutableListOf<Entry>()
    private val directorySnapshots = LinkedHashMap<String, DirectoryResult>()
    private val shizukuSnapshots = LinkedHashMap<String, DirectoryResult>()
    private val adapter = FileAdapter()

    private var directoryJob: Job? = null
    private var directoryGeneration = 0L
    private var fileOpenJob: Job? = null
    private var refreshOnResume = false
    private var pendingEditedUri: String? = null
    private var retryAction: (() -> Unit)? = null
    private var showingCachedRoot = false
    private var restoredPath: String? = null
    private var restoredShizukuMode = false
    private var restoredShizukuPath = ""
    private var highlightedUri: String? = null
    private var awaitingShizuku = false
    private var pendingShizukuPath = "/storage/emulated/0"
    private val shizukuReader by lazy { ShizukuFileReader(this, keepConnected = true) }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { code, grant ->
        if (code == ShizukuFileReader.PERMISSION_REQUEST_CODE && awaitingShizuku) {
            awaitingShizuku = false
            if (grant == PackageManager.PERMISSION_GRANTED) {
                shizukuMode = true
                navigateShizuku(pendingShizukuPath)
            }
            else toast(getString(R.string.shizuku_denied))
        }
    }

    /** Provider metadata is captured during the IO scan; binding never touches DocumentFile. */
    private data class Entry(
        val doc: DocumentFile,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long,
        val isParent: Boolean,
        val isDirectory: Boolean,
        val textVerdict: TextFileVerdict? = null,
    )

    private data class DirectoryResult(
        val entries: List<Entry>,
        val parent: DocumentFile?
    )

    private val requestLegacyWrite = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        ensureStorageAccess()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ManualTheme.apply(this)
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoredPath = savedInstanceState?.getString(STATE_CURRENT_PATH)
        restoredShizukuMode = savedInstanceState?.getBoolean(STATE_SHIZUKU_MODE, false) == true
        if (restoredShizukuMode) {
            restoredShizukuPath = savedInstanceState?.getString(STATE_SHIZUKU_PATH).orEmpty()
        }

        enableEdgeToEdge()
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            // The Toolbar lays its action menu out at getPaddingRight(), so a right padding
            // (not contentInsetEnd) is what moves the top-right icons clear of the display's
            // rounded corner and edge gesture zone.
            binding.toolbar.setPadding(
                binding.toolbar.paddingLeft,
                statusBars.top,
                (TOOLBAR_END_INSET_DP * resources.displayMetrics.density).toInt(),
                binding.toolbar.paddingBottom,
            )
            binding.recyclerView.setPadding(0, 0, 0, navBars.bottom)
            insets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(!isTaskRoot)
            setTitle(R.string.edit_user_files)
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.retryButton.setOnClickListener { retryAction?.invoke() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val root = rootTree ?: run {
                    finish()
                    return
                }
                val current = currentDir ?: run {
                    finish()
                    return
                }
                if (shizukuMode) {
                    val parent = File(shizukuPath).parentFile?.path
                    if (shizukuPath == "/storage/emulated/0" || parent == null ||
                        parent == "/storage/emulated/0") {
                        shizukuMode = false
                        navigateTo(root, "/", backgroundRefresh = true)
                    } else {
                        navigateShizuku(parent)
                    }
                    return
                }
                if (current.uri == root.uri) {
                    finish()
                    return
                }
                navigateTo(
                    currentParent ?: root,
                    path = parentPath(currentPath),
                    preserveScroll = false,
                    backgroundRefresh = true,
                )
            }
        })

        showColdStartSurface()
        ensureStorageAccess()
        if (storageRequirement() != StorageAccessRequirement.NONE &&
            !prefs.getBoolean(PREF_STORAGE_PROMPTED, false)) {
            prefs.edit().putBoolean(PREF_STORAGE_PROMPTED, true).apply()
            requestStorageAccess()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (currentPath.isNotEmpty()) outState.putString(STATE_CURRENT_PATH, currentPath)
        // Android/data browsing is served by the Shizuku service, not the SAF tree, so the
        // plain path is not enough to restore it: without this flag a configuration change
        // (e.g. the theme toggle recreating the activity) would silently fall back to home.
        outState.putBoolean(STATE_SHIZUKU_MODE, shizukuMode)
        outState.putString(STATE_SHIZUKU_PATH, shizukuPath)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        shizukuReader.close()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ensureStorageAccess()
        // onCreate is followed by onResume, so refresh only after we explicitly launched the editor.
        if (refreshOnResume) {
            refreshOnResume = false
            if (getSharedPreferences(RECENT_EDIT_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(pendingEditedUri, false)
            ) {
                highlightedUri = pendingEditedUri
                getSharedPreferences(RECENT_EDIT_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(pendingEditedUri).apply()
            }
            pendingEditedUri = null
            refreshCurrentDirectory(showProgress = false)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        // When this browser was opened from the editor just to pick a file, the toolbar arrow must
        // return to that editor. Delegating to the back callback would instead walk up the
        // directory tree, which is what the in-app "up one level" button already does.
        if (callerForResult) {
            finish()
            return true
        }
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_THEME_TOGGLE, Menu.NONE, R.string.theme_toggle).apply {
            // Tint explicitly instead of relying on the vector's own ?attr/colorControlNormal:
            // the drawable cache can return a vector resolved against the previous night
            // configuration right after the theme toggle recreates the activity, which showed
            // up as a white icon on the white day-mode app bar.
            icon = getDrawable(ManualTheme.iconRes(ManualTheme.currentMode(this@FileBrowserActivity)))
                ?.mutate()
                ?.apply { setTint(resolveThemeColor(android.R.attr.textColorPrimary)) }
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                ManualTheme.cycle(this@FileBrowserActivity)
                invalidateOptionsMenu()
                true
            }
        }
        menu.add(R.string.new_text_file).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                createNewTextFile()
                true
            }
        }
        menu.add(R.string.new_folder).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                createNewFolder()
                true
            }
        }
        menu.add(R.string.set_home).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                setCurrentDirectoryAsHome()
                true
            }
        }
        menu.add(R.string.settings).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                showSettingsDialog()
                true
            }
        }
        return true
    }

    private fun navigateShizuku(path: String) {
        if ((path == "/storage/emulated/0/Android" || path == "/storage/emulated/0") &&
            rootTree != null) {
            shizukuMode = false
            navigateTo(DocumentFile.fromFile(File(path)),
                path.removePrefix("/storage/emulated/0").ifEmpty { "/" }, backgroundRefresh = true)
            return
        }
        if (!shizukuMode) shizukuMode = true
        directoryJob?.cancel()
        val generation = ++directoryGeneration
        shizukuPath = path
        currentPath = path
        binding.pathBar.text = path
        val sameDirectory = currentDir?.uri?.path == path
        currentDir = DocumentFile.fromFile(File(path))
        currentParent = File(path).parentFile?.let(DocumentFile::fromFile)
        val cached = shizukuSnapshots[path]
        if (cached != null) {
            replaceEntries(cached.entries)
            if (cached.entries.none { !it.isParent }) showEmpty() else showContent()
            if (!sameDirectory) binding.recyclerView.scrollToPosition(0)
        } else {
            showLoading(path)
        }
        directoryJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    scanShizukuDirectory(path)
                }
                if (generation != directoryGeneration) return@launch
                currentDir = DocumentFile.fromFile(File(path))
                currentParent = File(path).parentFile?.let(DocumentFile::fromFile)
                shizukuSnapshots[path] = result
                while (shizukuSnapshots.size > MAX_SESSION_DIRECTORY_SNAPSHOTS) {
                    shizukuSnapshots.remove(shizukuSnapshots.keys.first())
                }
                replaceEntries(result.entries)
                if (result.entries.none { !it.isParent }) showEmpty() else showContent()
                if (cached == null && !sameDirectory) binding.recyclerView.scrollToPosition(0)
                directoryJob = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == directoryGeneration) {
                    directoryJob = null
                    showState(getString(R.string.error_open_file, e.message)) { navigateShizuku(path) }
                }
            }
        }
    }

    private fun openShizukuFile(path: String) {
        if (fileOpenJob?.isActive == true) return
        fileOpenJob = lifecycleScope.launch {
            try {
                val size = shizukuReader.useService { it.fileSize(path) }
                openFileUri(Uri.Builder().scheme("shizuku").path(path).build(), File(path).name,
                    null, size)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(getString(R.string.error_open_file, e.message)) }
        }
    }

    private suspend fun scanShizukuDirectory(path: String): DirectoryResult {
        val scanned = shizukuReader.useService { remote ->
            remote.list(path).map { rawName ->
                currentCoroutineContext().ensureActive()
                val directory = rawName.endsWith("/")
                val name = rawName.removeSuffix("/")
                val childPath = File(path, name).path
                val size = if (directory) -1L else remote.fileSize(childPath)
                val modified = remote.lastModified(childPath)
                Triple(rawName, size, modified)
            }
        }
        val parent = File(path).parentFile?.takeIf {
            path != "/storage/emulated/0" && it.path != "/storage/emulated"
        }
        val children = scanned.map { (rawName, size, modified) ->
            currentCoroutineContext().ensureActive()
            val isDirectory = rawName.endsWith("/")
            val name = rawName.removeSuffix("/")
            val entryPath = File(path, name).path
            Entry(
                doc = DocumentFile.fromFile(File(entryPath)),
                name = name,
                mimeType = if (isDirectory) null else "text/plain",
                size = size,
                lastModified = modified,
                isParent = false,
                isDirectory = isDirectory,
                textVerdict = if (isDirectory) null else TextFileClassifier.classify(
                    name, "text/plain"
                ).let { if (it == TextFileVerdict.NEEDS_SNIFFING) TextFileVerdict.TEXT else it }
            )
        }.sortedWith(compareBy<Entry> { !it.isDirectory }.thenBy { it.name.lowercase() })
        val allEntries = buildList {
            if (parent != null) add(
                Entry(
                    doc = DocumentFile.fromFile(parent),
                    name = getString(R.string.parent_directory),
                    mimeType = null,
                    size = -1L,
                    lastModified = 0L,
                    isParent = true,
                    isDirectory = true,
                )
            )
            addAll(children)
        }
        return DirectoryResult(allEntries, parent?.let(DocumentFile::fromFile))
    }

    private fun restoreCachedRootSnapshot() {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val encoded = prefs.getString(PREF_ROOT_SNAPSHOT, null) ?: return
        val cached = runCatching { DirectorySnapshotCodec.decode(encoded, rootPath) }.getOrNull() ?: return
        if (cached.isEmpty()) return
        val restored = cached.mapNotNull { item ->
            runCatching {
                Entry(
                    doc = DocumentFile.fromFile(File(Uri.parse(item.uri).path ?: return@mapNotNull null)),
                    name = item.name,
                    mimeType = item.mimeType,
                    size = item.size,
                    lastModified = item.lastModified,
                    isParent = false,
                    isDirectory = item.isDirectory,
                    textVerdict = item.textVerdict,
                )
            }.getOrNull()
        }
        if (restored.isNotEmpty()) {
            showingCachedRoot = true
            currentPath = "/"
            binding.pathBar.text = currentPath
            val result = DirectoryResult(restored, parent = null)
            directorySnapshots[DocumentFile.fromFile(File(rootPath)).uri.toString()] = result
            replaceEntries(restored)
            showContent()
        }
    }

    private fun persistRootSnapshot(root: DocumentFile, result: DirectoryResult) {
        val snapshot = result.entries.asSequence()
            .filterNot { it.isParent }
            .map {
                DirectorySnapshotEntry(
                    uri = it.doc.uri.toString(),
                    name = it.name,
                    mimeType = it.mimeType,
                    size = it.size,
                    lastModified = it.lastModified,
                    isDirectory = it.isDirectory,
                    textVerdict = it.textVerdict,
                )
            }
            .toList()
        val cacheRootUri = root.uri.path.orEmpty()
        prefs.edit().putString(
            PREF_ROOT_SNAPSHOT,
            DirectorySnapshotCodec.encode(cacheRootUri, snapshot),
        ).apply()
    }

    private fun storageRequirement() = StorageAccessPolicy.requirement(
        Build.VERSION.SDK_INT,
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager(),
        checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED,
    )

    private fun ensureStorageAccess() {
        if (storageRequirement() != StorageAccessRequirement.NONE) {
            showGrantState()
        } else if (rootTree == null && directoryJob == null) {
            if (prefs.getString(PREF_HOME_PATH, null).isNullOrBlank()) restoreCachedRootSnapshot()
            openTree(Uri.fromFile(File(Environment.getExternalStorageDirectory().absolutePath)))
        }
    }

    private fun showGrantState() {
        cancelDirectoryLoad()
        rootTree = null
        currentDir = null
        currentParent = null
        currentPath = ""
        binding.pathBar.text = ""
        replaceEntries(emptyList())
        showState(getString(R.string.all_files_access_message)) { requestStorageAccess() }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestLegacyWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                showGrantState()
            }
        }
    }

    private fun openTree(uri: Uri) {
        cancelDirectoryLoad()
        if (!showingCachedRoot) showColdStartSurface()
        val generation = directoryGeneration
        directoryJob = lifecycleScope.launch {
            val root = try {
                withContext(Dispatchers.IO) {
                    DocumentFile.fromFile(File(uri.path!!))
                        .takeIf { it.isDirectory && it.canRead() }
                }
            } catch (_: Exception) {
                null
            }
            if (generation != directoryGeneration) return@launch
            if (root == null) {
                directoryJob = null
                showState(getString(R.string.folder_load_failed)) {
                    ensureStorageAccess()
                }
                return@launch
            }
            rootTree = root
            directoryJob = null
            val restorePath = restoredPath
            val restoreShizuku = restoredShizukuMode
            val restoreShizukuPath = restoredShizukuPath
            restoredPath = null
            restoredShizukuMode = false
            restoredShizukuPath = ""
            // A Shizuku path (Android/data) is not reachable through the SAF tree, so restore it
            // through the Shizuku service instead of the relative-path resolution below.
            if (restoreShizuku && restoreShizukuPath.isNotBlank() &&
                hasShizukuAccess() && needsShizukuPath(restoreShizukuPath)) {
                shizukuMode = true
                navigateShizuku(restoreShizukuPath)
                return@launch
            }
            if (!restorePath.isNullOrBlank() && restorePath != "/") {
                val target = withContext(Dispatchers.IO) { resolveRelativeDirectory(root, restorePath) }
                if (generation != directoryGeneration) return@launch
                if (target != null) {
                    navigateTo(target, path = restorePath, showProgress = false, backgroundRefresh = true)
                    return@launch
                }
            }
            if (openHomeDirectory(root, generation)) return@launch
            navigateTo(root, path = "/", showProgress = false)
        }
    }

    private fun navigateTo(
        dir: DocumentFile,
        path: String,
        preserveScroll: Boolean = false,
        showProgress: Boolean = true,
        backgroundRefresh: Boolean = false,
    ) {
        val root = rootTree ?: return
        shizukuMode = false
        directoryJob?.cancel()
        val generation = ++directoryGeneration
        val previousDirUri = currentDir?.uri
        if (previousDirUri != null && previousDirUri != dir.uri) highlightedUri = null
        currentDir = dir
        currentPath = path
        binding.pathBar.text = path
        val snapshot = directorySnapshots[dir.uri.toString()]
        if (snapshot != null) {
            currentParent = snapshot.parent
            replaceEntries(snapshot.entries)
            if (snapshot.entries.none { !it.isParent }) showEmpty() else showContent()
            if (!preserveScroll) binding.recyclerView.scrollToPosition(0)
        }
        if (DirectoryLoadPresentation.shouldShowBlockingProgress(
                hasSnapshot = snapshot != null,
                backgroundRefresh = backgroundRefresh || !showProgress,
            )) {
            replaceEntries(emptyList())
            showLoading(path)
        } else if (snapshot == null) {
            showContent()
        }

        directoryJob = lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    resolveAmbiguousTextIcons(scanDirectory(dir, root))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (generation != directoryGeneration) return@launch
            if (result == null) {
                showState(getString(R.string.folder_load_failed)) {
                    navigateTo(dir, path, preserveScroll = true)
                }
                return@launch
            }

            currentParent = result.parent
            if (generation != directoryGeneration) return@launch
            directorySnapshots[dir.uri.toString()] = result
            while (directorySnapshots.size > MAX_SESSION_DIRECTORY_SNAPSHOTS) {
                directorySnapshots.remove(directorySnapshots.keys.first())
            }
            replaceEntries(result.entries)
            showingCachedRoot = false
            if (dir.uri == root.uri) persistRootSnapshot(root, result)
            if (result.entries.none { !it.isParent }) {
                showEmpty()
            } else {
                showContent()
            }
            if (!preserveScroll && snapshot == null) binding.recyclerView.scrollToPosition(0)
        }
    }

    private suspend fun scanDirectory(
        dir: DocumentFile,
        root: DocumentFile
    ): DirectoryResult {
        val parent = if (dir.uri == root.uri) {
            null
        } else {
            File(dir.uri.path!!).parentFile?.let(DocumentFile::fromFile)
                ?.takeIf { isInsideRoot(it, root) } ?: root
        }
        val children = (File(dir.uri.path!!).listFiles()
            ?: throw java.io.IOException("Cannot list ${dir.uri.path}"))
            .map { file ->
            currentCoroutineContext().ensureActive()
            val metadata = android.system.Os.stat(file.path)
            val isDirectory = android.system.OsConstants.S_ISDIR(metadata.st_mode)
            val mimeType = if (isDirectory) null else android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
            Entry(
                doc = DocumentFile.fromFile(file),
                name = file.name,
                mimeType = mimeType,
                size = if (isDirectory) -1L else metadata.st_size,
                lastModified = metadata.st_mtime * 1000L,
                isParent = false,
                isDirectory = isDirectory
            )
        }
        val sortedChildren = children.sortedWith(
            compareBy<Entry> { !it.isDirectory }.thenBy { it.name.lowercase() }
        )
        val allEntries = buildList {
            if (parent != null) {
                add(
                    Entry(
                        doc = parent,
                        name = getString(R.string.parent_directory),
                        mimeType = null,
                        size = -1L,
                        lastModified = 0L,
                        isParent = true,
                        isDirectory = true
                    )
                )
            }
            addAll(sortedChildren)
        }
        return DirectoryResult(allEntries, parent)
    }

    /** Called only from Dispatchers.IO. */
    private fun resolveRelativeDirectory(root: DocumentFile, path: String): DocumentFile? {
        var node = root
        for (segment in path.split('/').filter { it.isNotEmpty() }) {
            node = node.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return node
    }

    /** Called only from Dispatchers.IO. */
    private fun isInsideRoot(file: DocumentFile, root: DocumentFile): Boolean {
        val base = File(root.uri.path!!).canonicalPath
        val path = File(file.uri.path!!).canonicalPath
        return path == base || path.startsWith(base.trimEnd('/') + "/")
    }

    private fun refreshCurrentDirectory(showProgress: Boolean = true) {
        if (shizukuMode) {
            navigateShizuku(shizukuPath)
            return
        }
        val dir = currentDir ?: return
        navigateTo(dir, currentPath, preserveScroll = true, showProgress = showProgress)
    }

    private fun cancelDirectoryLoad() {
        directoryGeneration++
        directoryJob?.cancel()
        directoryJob = null
    }

    private fun replaceEntries(newEntries: List<Entry>) {
        entries.clear()
        entries.addAll(newEntries)
        adapter.notifyDataSetChanged()
    }

    private fun showColdStartSurface() {
        binding.pathBar.text = "/"
        binding.recyclerView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.stateContainer.visibility = View.GONE
        retryAction = null
    }

    private fun showLoading(path: String) {
        binding.pathBar.text = path
        binding.recyclerView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.stateContainer.visibility = View.GONE
        retryAction = null
    }

    private fun showContent() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.stateContainer.visibility = View.GONE
        retryAction = null
    }

    private fun showEmpty() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        binding.stateMessage.setText(R.string.empty_directory)
        binding.retryButton.visibility = View.GONE
        binding.stateContainer.visibility = View.VISIBLE
        retryAction = null
    }

    private fun showState(message: String, retry: (() -> Unit)? = null) {
        binding.recyclerView.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.stateMessage.text = message
        binding.retryButton.visibility = if (retry == null) View.GONE else View.VISIBLE
        binding.stateContainer.visibility = View.VISIBLE
        retryAction = retry
    }

    private fun onEntryClick(entry: Entry) {
        if (shizukuMode) {
            if (entry.isParent) {
                navigateShizuku(File(shizukuPath).parentFile?.path ?: "/storage/emulated/0")
            } else if (entry.isDirectory) {
                navigateShizuku(File(shizukuPath, entry.name).path)
            } else {
                openShizukuFile(File(shizukuPath, entry.name).path)
            }
            return
        }
        if (!entry.isParent && entry.isDirectory) {
            val path = entry.doc.uri.path.orEmpty()
            if (needsShizukuPath(path)) {
                when {
                    !shizukuBrowseEnabled ->
                        navigateTo(entry.doc, childPath(currentPath, entry.name), backgroundRefresh = true)
                    hasShizukuAccess() -> {
                        shizukuMode = true
                        navigateShizuku(path)
                    }
                    runCatching { Shizuku.pingBinder() }.getOrDefault(false) -> {
                        pendingShizukuPath = path
                        awaitingShizuku = true
                        Shizuku.requestPermission(ShizukuFileReader.PERMISSION_REQUEST_CODE)
                    }
                    else -> navigateTo(entry.doc, childPath(currentPath, entry.name), backgroundRefresh = true)
                }
                return
            }
        }
        if (entry.isParent || entry.isDirectory) {
            val targetPath = if (entry.isParent) {
                parentPath(currentPath)
            } else {
                childPath(currentPath, entry.name)
            }
            navigateTo(entry.doc, targetPath, backgroundRefresh = true)
            return
        }
        fileOpenJob?.cancel()
        fileOpenJob = lifecycleScope.launch {
            openFileUri(entry.doc.uri, entry.name, entry.mimeType, entry.size)
        }
    }

    private fun hasShizukuAccess(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private val shizukuBrowseEnabled: Boolean
        get() = prefs.getBoolean(PREF_SHIZUKU_BROWSE, true)

    /** Android/data is unreadable through the normal file API; it needs the Shizuku service. */
    private fun needsShizukuPath(path: String): Boolean =
        path == ANDROID_DATA_PATH || path.startsWith("$ANDROID_DATA_PATH/")

    /** True when the configured home directory was successfully opened. */
    private suspend fun openHomeDirectory(root: DocumentFile, generation: Long): Boolean {
        val home = prefs.getString(PREF_HOME_PATH, null)?.trim().orEmpty()
        if (home.isEmpty() || home == "/") return false
        val rootPath = File(root.uri.path!!).path
        if (home == rootPath) return false
        if (needsShizukuPath(home)) {
            if (!shizukuBrowseEnabled) return false
            if (hasShizukuAccess()) {
                shizukuMode = true
                navigateShizuku(home)
                return true
            }
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
                pendingShizukuPath = home
                awaitingShizuku = true
                Shizuku.requestPermission(ShizukuFileReader.PERMISSION_REQUEST_CODE)
            }
            return false
        }
        val base = rootPath.trimEnd('/')
        if (!home.startsWith("$base/")) return false
        val relative = home.removePrefix(base)
        if (relative.isEmpty() || relative == "/") return false
        val target = withContext(Dispatchers.IO) { resolveRelativeDirectory(root, relative) }
        if (generation != directoryGeneration) return false
        if (target == null) return false
        navigateTo(target, path = relative, showProgress = false, backgroundRefresh = true)
        return true
    }

    private fun setCurrentDirectoryAsHome() {
        val path = currentDir?.uri?.path
        if (path.isNullOrBlank()) return
        prefs.edit().putString(PREF_HOME_PATH, path).apply()
        toast(getString(R.string.home_set))
    }

    private fun showSettingsDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val shizukuToggle = CheckBox(this).apply {
            setText(R.string.shizuku_open)
            isChecked = shizukuBrowseEnabled
        }
        container.addView(shizukuToggle)
        container.addView(TextView(this).apply {
            setText(R.string.dark_mode)
            setPadding(0, pad / 2, 0, 0)
        })
        val current = ManualTheme.currentMode(this)
        val group = RadioGroup(this)
        listOf(
            ManualTheme.MODE_DAY to R.string.theme_day,
            ManualTheme.MODE_NIGHT to R.string.theme_night,
            ManualTheme.MODE_SYSTEM to R.string.theme_system,
        ).forEach { (mode, labelRes) ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = mode
                setText(labelRes)
                isChecked = mode == current
            })
        }
        container.addView(group)
        container.addView(TextView(this).apply {
            setText(getString(R.string.current_version, BuildConfig.VERSION_NAME))
            setPadding(0, pad / 2, 0, 0)
        })
        container.addView(Button(this).apply {
            setText(R.string.check_for_updates)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { checkForUpdates() }
        })
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit()
                    .putBoolean(PREF_SHIZUKU_BROWSE, shizukuToggle.isChecked)
                    .apply()
                val selected = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Int
                if (selected != null && selected != current) {
                    ManualTheme.setMode(this, selected)
                    invalidateOptionsMenu()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Asks GitHub for the newest release and, when one exists, offers to queue it.
     *
     * The check runs on a background thread, and the download itself is handed to the system
     * downloader, so neither the check nor the ~3 MB fetch ever blocks the editor.
     */
    private fun checkForUpdates() {
        val checking = AlertDialog.Builder(this)
            .setTitle(R.string.check_for_updates)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .create()
        checking.show()
        UpdateChecker.checkAsync(BuildConfig.VERSION_NAME) { result ->
            if (isFinishing || isDestroyed) return@checkAsync
            checking.dismiss()
            when (result) {
                is UpdateChecker.Result.UpdateAvailable -> AlertDialog.Builder(this)
                    .setTitle(R.string.update_available_title)
                    .setMessage(getString(R.string.update_available_message, result.tag))
                    .setPositiveButton(R.string.update_download) { _, _ ->
                        val id = UpdateChecker.enqueueDownload(this, result.apkUrl, result.tag)
                        toast(
                            if (id != null) getString(R.string.update_queued)
                            else getString(R.string.update_failed)
                        )
                    }
                    .setNeutralButton(R.string.update_open_release) { _, _ ->
                        UpdateChecker.openReleasesPage(this, result.tag)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()

                is UpdateChecker.Result.UpToDate ->
                    toast(getString(R.string.update_up_to_date, result.tag))

                is UpdateChecker.Result.NoApk ->
                    AlertDialog.Builder(this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_no_apk, result.tag))
                        .setPositiveButton(R.string.update_open_release) { _, _ ->
                            UpdateChecker.openReleasesPage(this, result.tag)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()

                is UpdateChecker.Result.Failed ->
                    toast(getString(R.string.update_failed_detail, result.message))
            }
        }
    }

    private fun createNewTextFile() {
        val dir = currentDir
        if (dir == null) {
            toast(getString(R.string.new_text_file_failed))
            return
        }
        val baseName = getString(R.string.new_text_file_default_name)
        val existing = entries.asSequence()
            .filterNot { it.isParent }
            .map { it.name }
            .toSet()
        val fileName = NewFileNaming.uniqueName(baseName, "txt", existing)
        fileOpenJob?.cancel()
        fileOpenJob = lifecycleScope.launch {
            val created = withContext(Dispatchers.IO) {
                try {
                    File(File(dir.uri.path!!), fileName).takeIf { it.createNewFile() }
                        ?.let(DocumentFile::fromFile)
                } catch (_: Exception) {
                    null
                }
            }
            if (created == null) {
                toast(getString(R.string.new_text_file_failed))
                return@launch
            }
            openFileUri(created.uri, created.name ?: fileName, "text/plain", 0L)
        }
    }

    private fun createNewFolder() {
        val dir = currentDir
        if (dir == null) {
            toast(getString(R.string.new_folder_failed))
            return
        }
        val existing = entries.asSequence()
            .filterNot { it.isParent }
            .map { it.name }
            .toSet()
        val editText = EditText(this).apply {
            setText(NewFileNaming.uniqueDirectoryName(getString(R.string.new_folder_default_name), existing))
            setSelection(0, text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.new_folder)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isEmpty() || name == "." || name == ".." || '/' in name) return@setPositiveButton
                val existingNames = entries.asSequence()
                    .filterNot { it.isParent }
                    .map { it.name }
                    .toSet()
                val unique = if (existingNames.any { it.equals(name, ignoreCase = true) }) {
                    NewFileNaming.uniqueDirectoryName(name, existingNames)
                } else name
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val target = File(File(dir.uri.path!!), unique)
                            !target.exists() && target.mkdir()
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (ok) {
                        highlightedUri = null
                        refreshCurrentDirectory(showProgress = false)
                    } else {
                        toast(getString(R.string.new_folder_failed))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        focusAndShowKeyboard(dialog, editText)
    }

    private suspend fun openFileUri(
        uri: Uri,
        displayName: String,
        mime: String?,
        length: Long
    ) {
        val isSupported = TextFileSupport.isProbablyTextFileAsync(
            contentResolver,
            uri,
            displayName,
            mime,
        )
        if (!isSupported) {
            shortToast(getString(R.string.binary_file_unsupported))
            return
        }
        if (length > TextFileSupport.MAX_FILE_SIZE) {
            toast(getString(R.string.file_too_large, formatSize(length)))
            return
        }
        if (callerForResult) {
            setResult(
                RESULT_OK,
                Intent().apply {
                    data = uri
                    putExtra(EXTRA_RESULT_DISPLAY_NAME, displayName)
                    putExtra(EXTRA_RESULT_LENGTH, length)
                }
            )
            finish()
        } else {
            refreshOnResume = true
            pendingEditedUri = uri.toString()
            startActivity(
                Intent(this, TextFileEditActivity::class.java).apply {
                    data = uri
                    putExtra(TextFileEditActivity.EXTRA_DISPLAY_NAME, displayName)
                    putExtra(TextFileEditActivity.EXTRA_FILE_SIZE, length)
                }
            )
        }
    }

    private val callerForResult: Boolean
        get() = callingActivity != null

    private inner class FileAdapter : RecyclerView.Adapter<FileVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
            val itemBinding = ItemFileEntryBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return FileVH(itemBinding)
        }

        override fun getItemCount(): Int = entries.size

        override fun onBindViewHolder(holder: FileVH, position: Int) {
            val entry = entries[position]
            holder.bind(entry)
            holder.itemView.setOnClickListener { onEntryClick(entry) }
            holder.itemView.setOnLongClickListener { onEntryLongClick(entry) }
        }
    }

    private fun onEntryLongClick(entry: Entry): Boolean {
        if (entry.isParent) return false
        val actions: List<Pair<String, () -> Unit>> = if (entry.isDirectory) {
            listOf(
                getString(R.string.rename) to {
                    showRenameDialog(entry.doc, entry.name, entry.isDirectory)
                },
                getString(R.string.alias) to { showAliasDialog(entry) },
                getString(R.string.delete) to { showDeleteConfirm(entry.doc, entry.name) },
            )
        } else {
            listOf(
                getString(R.string.rename) to {
                    showRenameDialog(entry.doc, entry.name, entry.isDirectory)
                },
                getString(R.string.delete) to { showDeleteConfirm(entry.doc, entry.name) },
            )
        }
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
        return true
    }

    private fun showAliasDialog(entry: Entry) {
        val path = entry.doc.uri.path ?: return
        val editText = EditText(this).apply {
            setText(FolderAliases.get(this@FileBrowserActivity, path).orEmpty())
            setSelection(0, text.length)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.alias)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                FolderAliases.set(this, path, editText.text.toString())
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        focusAndShowKeyboard(dialog, editText)
    }

    private fun showRenameDialog(doc: DocumentFile, currentName: String, isDirectory: Boolean) {
        val editText = EditText(this).apply {
            setText(currentName)
            setSelection(0, baseNameSelectionLength(currentName, isDirectory))
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(editText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isEmpty() || newName == currentName || newName == "." ||
                    newName == ".." || '/' in newName) return@setPositiveButton
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            val target = File(File(doc.uri.path!!).parentFile, newName)
                            !target.exists() && doc.renameTo(newName)
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (ok) {
                        highlightedUri = doc.uri.toString()
                        refreshCurrentDirectory(showProgress = false)
                    } else {
                        toast(getString(R.string.rename_failed, newName))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
        focusAndShowKeyboard(dialog, editText)
    }

    private fun showDeleteConfirm(doc: DocumentFile, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.confirm_delete, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            File(doc.uri.path!!).deleteRecursively()
                        } catch (_: Exception) {
                            false
                        }
                    }
                    if (ok) {
                        toast(getString(R.string.deleted))
                        refreshCurrentDirectory(showProgress = false)
                    } else {
                        toast(getString(R.string.delete_failed, name))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Resolves every regular file to an immutable icon verdict before publishing the directory. */
    private fun resolveAmbiguousTextIcons(result: DirectoryResult): DirectoryResult {
        val resolved = result.entries.map { entry ->
            if (entry.isParent || entry.isDirectory) return@map entry
            val metadataVerdict = TextFileClassifier.classify(entry.name, entry.mimeType)
            val verdict = if (metadataVerdict != TextFileVerdict.NEEDS_SNIFFING) {
                metadataVerdict
            } else {
                if (TextFileSupport.isProbablyTextFile(
                        contentResolver,
                        entry.doc.uri,
                        entry.name,
                        entry.mimeType,
                    )
                ) TextFileVerdict.TEXT else TextFileVerdict.BINARY
            }
            entry.copy(textVerdict = verdict)
        }
        return result.copy(entries = resolved)
    }

    /** Folder label: original name, then the user alias rendered in green bold (display only). */
    private fun entryLabel(entry: Entry): CharSequence {
        if (!entry.isDirectory || entry.isParent) return entry.name
        val alias = FolderAliases.get(this, entry.doc.uri.path.orEmpty()) ?: return entry.name
        val text = SpannableStringBuilder(entry.name).append(" ").append(alias)
        val aliasStart = entry.name.length + 1
        text.setSpan(
            ForegroundColorSpan(ALIAS_COLOR),
            aliasStart,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        text.setSpan(
            StyleSpan(Typeface.BOLD),
            aliasStart,
            text.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        return text
    }

    private inner class FileVH(val binding: ItemFileEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: Entry) {
            binding.name.text = entryLabel(entry)
            binding.name.setTextColor(
                if (!entry.isParent && entry.doc.uri.toString() == highlightedUri) 0xFF2EAD55.toInt()
                else com.google.android.material.color.MaterialColors.getColor(
                    binding.name,
                    android.R.attr.textColorPrimary,
                    0xFF000000.toInt(),
                )
            )
            when {
                entry.isParent -> {
                    binding.icon.setImageResource(R.drawable.ic_browser_parent)
                    binding.icon.rotation = 0f
                    binding.info.visibility = View.GONE
                }
                entry.isDirectory -> {
                    binding.icon.setImageResource(R.drawable.ic_browser_folder)
                    binding.icon.rotation = 0f
                    binding.info.text = FileEntryMetadataFormatter.formatModifiedTime(
                        entry.lastModified,
                        java.time.ZoneId.systemDefault(),
                    ).orEmpty()
                    binding.info.visibility = if (binding.info.text.isEmpty()) View.GONE else View.VISIBLE
                }
                else -> {
                    binding.icon.setImageResource(
                        when (FileIconPolicy.regularFileIcon(entry.textVerdict)) {
                            RegularFileIcon.EDITABLE_TEXT -> R.drawable.ic_browser_text_file
                            RegularFileIcon.UNKNOWN -> R.drawable.ic_browser_unknown_file
                        }
                    )
                    binding.icon.rotation = 0f
                    binding.info.text = FileEntryMetadataFormatter.formatLine(
                        entry.lastModified,
                        entry.size,
                        java.time.ZoneId.systemDefault(),
                    )
                    binding.info.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun childPath(parent: String, name: String): String =
        if (parent == "/") "/$name" else "$parent/$name"

    private fun parentPath(path: String): String {
        if (path.isEmpty() || path == "/") return "/"
        return path.substringBeforeLast('/', "").ifEmpty { "/" }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }

    private fun shortToast(msg: String) {
        val notice = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT)
        notice.show()
        binding.root.postDelayed({ notice.cancel() }, UNSUPPORTED_TOAST_DURATION_MS)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Focuses a naming field and raises the soft keyboard once the dialog window is ready.
     *
     * A plain [InputMethodManager.showSoftInput] issued right after `show()` is dropped by the
     * framework because the dialog window has not been focused yet, which is why the keyboard used
     * to stay hidden until the user tapped the field. Two things are needed here: the dialog window
     * must declare [WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE], and the focus
     * request must be posted so it runs after the window is attached.
     */
    private fun focusAndShowKeyboard(dialog: Dialog, field: EditText) {
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        field.requestFocus()
        field.post {
            field.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Length of the base name (everything before the extension) that a rename should pre-select. */
    private fun baseNameSelectionLength(currentName: String, isDirectory: Boolean): Int {
        if (isDirectory) return currentName.length
        val dot = currentName.lastIndexOf('.')
        return if (dot > 0) dot else currentName.length
    }

    /**
     * Resolves a theme attribute to a concrete colour for the activity's current configuration.
     *
     * Menu icons are drawn from the drawable cache, which can hold a vector already resolved
     * against the previous night mode; resolving the colour here keeps the tint correct right after
     * a theme change.
     */
    private fun resolveThemeColor(attr: Int): Int {
        val typed = android.util.TypedValue()
        val resolved = theme.resolveAttribute(attr, typed, true)
        return if (resolved && typed.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, typed.resourceId)
        } else {
            typed.data
        }
    }

    companion object {
        private const val STATE_CURRENT_PATH = "current_path"
        private const val STATE_SHIZUKU_MODE = "shizuku_mode"
        private const val STATE_SHIZUKU_PATH = "shizuku_path"
        private const val RECENT_EDIT_PREFS = "recent_edit_transient"
        private const val PREFS_NAME = "text_editor"
        private const val PREF_HOME_PATH = "home_path"
        private const val PREF_SHIZUKU_BROWSE = "shizuku_browse_all"
        private const val PREF_STORAGE_PROMPTED = "storage_prompted"
        private const val PREF_ROOT_SNAPSHOT = "root_directory_snapshot_v1"
        private const val ANDROID_DATA_PATH = "/storage/emulated/0/Android/data"
        private val ALIAS_COLOR = 0xFF2EAD55.toInt()
        private const val TOOLBAR_END_INSET_DP = 12
        private const val MAX_SESSION_DIRECTORY_SNAPSHOTS = 32
        private const val UNSUPPORTED_TOAST_DURATION_MS = 600L
        private const val MENU_THEME_TOGGLE = 1001
        const val EXTRA_RESULT_DISPLAY_NAME = "result_display_name"
        const val EXTRA_RESULT_LENGTH = "result_length"
    }
}

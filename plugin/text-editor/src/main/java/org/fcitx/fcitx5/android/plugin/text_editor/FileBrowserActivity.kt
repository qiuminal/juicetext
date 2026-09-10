/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
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
import splitties.views.topPadding

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var prefs: SharedPreferences

    private var rootTree: DocumentFile? = null
    private var currentDir: DocumentFile? = null
    private var currentParent: DocumentFile? = null
    private var currentPath = ""
    private val entries = mutableListOf<Entry>()
    private val directorySnapshots = LinkedHashMap<String, DirectoryResult>()
    private val sniffedTextUris = mutableSetOf<String>()
    private val adapter = FileAdapter()

    private var directoryJob: Job? = null
    private var directoryGeneration = 0L
    private var fileOpenJob: Job? = null
    private var refreshOnResume = false
    private var pendingEditedUri: String? = null
    private var retryAction: (() -> Unit)? = null
    private var showingCachedRoot = false
    private var restoredPath: String? = null
    private var highlightedUri: String? = null

    /** Provider metadata is captured during the IO scan; binding never touches DocumentFile. */
    private data class Entry(
        val doc: DocumentFile,
        val name: String,
        val mimeType: String?,
        val size: Long,
        val lastModified: Long,
        val isParent: Boolean,
        val isDirectory: Boolean
    )

    private data class DirectoryResult(
        val entries: List<Entry>,
        val parent: DocumentFile?
    )

    private val pickTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            if (rootTree == null) {
                toast(getString(R.string.grant_access_denied))
                showGrantState()
            }
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            persistTreePermission(uri)
            prefs.edit().putString(PREF_TREE_URI, uri.toString()).apply()
            openTree(uri)
        }
    }

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        fileOpenJob?.cancel()
        fileOpenJob = lifecycleScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                persistUriPermission(uri)
                val doc = DocumentFile.fromSingleUri(this@FileBrowserActivity, uri)
                Triple(
                    doc?.name ?: uri.lastPathSegment ?: "?",
                    doc?.type ?: contentResolver.getType(uri),
                    doc?.length() ?: -1L
                )
            }
            openFileUri(uri, metadata.first, metadata.second, metadata.third)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ManualTheme.apply(this)
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        restoredPath = savedInstanceState?.getString(STATE_CURRENT_PATH)

        enableEdgeToEdge()
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
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
        restoreCachedRootSnapshot()
        lifecycleScope.launch { restorePersistedTree() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (currentPath.isNotEmpty()) outState.putString(STATE_CURRENT_PATH, currentPath)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
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
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_THEME_TOGGLE, Menu.NONE, R.string.theme_toggle).apply {
            icon = getDrawable(
                if (ManualTheme.isDark(this@FileBrowserActivity)) R.drawable.ic_theme_light
                else R.drawable.ic_theme_dark
            )
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                ManualTheme.toggle(this@FileBrowserActivity)
                true
            }
        }
        menu.add(R.string.change_root_folder).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                launchPicker()
                true
            }
        }
        menu.add(R.string.open_single_file).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            setOnMenuItemClickListener {
                pickFile.launch(arrayOf("*/*"))
                true
            }
        }
        return true
    }

    private fun restoreCachedRootSnapshot() {
        val rootUri = prefs.getString(PREF_TREE_URI, null) ?: return
        val encoded = prefs.getString(PREF_ROOT_SNAPSHOT, null) ?: return
        val cached = runCatching { DirectorySnapshotCodec.decode(encoded, rootUri) }.getOrNull() ?: return
        if (cached.isEmpty()) return
        val restored = cached.mapNotNull { item ->
            runCatching {
                Entry(
                    doc = DocumentFile.fromSingleUri(this, Uri.parse(item.uri)) ?: return@mapNotNull null,
                    name = item.name,
                    mimeType = item.mimeType,
                    size = item.size,
                    lastModified = item.lastModified,
                    isParent = false,
                    isDirectory = item.isDirectory,
                )
            }.getOrNull()
        }
        if (restored.isNotEmpty()) {
            showingCachedRoot = true
            currentPath = "/"
            binding.pathBar.text = currentPath
            val result = DirectoryResult(restored, parent = null)
            directorySnapshots[rootUri] = result
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
                )
            }
            .toList()
        val cacheRootUri = prefs.getString(PREF_TREE_URI, null) ?: root.uri.toString()
        prefs.edit().putString(
            PREF_ROOT_SNAPSHOT,
            DirectorySnapshotCodec.encode(cacheRootUri, snapshot),
        ).apply()
    }

    private suspend fun restorePersistedTree() {
        val saved = prefs.getString(PREF_TREE_URI, null)?.let(Uri::parse)
        val persistedOk = saved != null && withContext(Dispatchers.IO) {
            contentResolver.persistedUriPermissions.any {
                it.uri == saved && it.isReadPermission && it.isWritePermission
            }
        }
        if (saved != null && persistedOk) {
            openTree(saved)
        } else {
            showGrantStateAndPick()
        }
    }

    private suspend fun persistTreePermission(uri: Uri) = withContext(Dispatchers.IO) {
        persistUriPermission(uri)
    }

    private fun persistUriPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers don't allow persisting; the transient grant remains usable.
        }
    }

    private fun showGrantStateAndPick() {
        cancelDirectoryLoad()
        rootTree = null
        currentDir = null
        currentParent = null
        currentPath = ""
        binding.pathBar.text = ""
        replaceEntries(emptyList())
        showState(getString(R.string.grant_access_message)) { launchPicker() }
        launchPicker()
    }

    private fun showGrantState() {
        showState(getString(R.string.grant_access_message)) { launchPicker() }
    }

    private fun launchPicker() {
        try {
            pickTree.launch(null)
        } catch (_: Exception) {
            showGrantState()
        }
    }

    private fun openTree(uri: Uri) {
        cancelDirectoryLoad()
        if (!showingCachedRoot) showColdStartSurface()
        val generation = directoryGeneration
        directoryJob = lifecycleScope.launch {
            val root = try {
                withContext(Dispatchers.IO) {
                    DocumentFile.fromTreeUri(this@FileBrowserActivity, uri)
                        ?.takeIf { it.canRead() }
                }
            } catch (_: Exception) {
                null
            }
            if (generation != directoryGeneration) return@launch
            if (root == null) {
                prefs.edit().remove(PREF_TREE_URI).apply()
                showGrantStateAndPick()
                return@launch
            }
            rootTree = root
            directoryJob = null
            val persistedUri = prefs.getString(PREF_TREE_URI, null)
            val cachedResult = persistedUri?.let(directorySnapshots::get)
            if (cachedResult != null) directorySnapshots[root.uri.toString()] = cachedResult
            val restorePath = restoredPath
            restoredPath = null
            if (!restorePath.isNullOrBlank() && restorePath != "/") {
                val target = withContext(Dispatchers.IO) { resolveRelativeDirectory(root, restorePath) }
                if (generation != directoryGeneration) return@launch
                if (target != null) {
                    navigateTo(target, path = restorePath, showProgress = false, backgroundRefresh = true)
                    return@launch
                }
            }
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
                withContext(Dispatchers.IO) { scanDirectory(dir, root) }
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
            val resolvedResult = resolveAmbiguousTextIcons(result)
            if (generation != directoryGeneration) return@launch
            directorySnapshots[dir.uri.toString()] = resolvedResult
            while (directorySnapshots.size > MAX_SESSION_DIRECTORY_SNAPSHOTS) {
                directorySnapshots.remove(directorySnapshots.keys.first())
            }
            replaceEntries(resolvedResult.entries)
            showingCachedRoot = false
            if (dir.uri == root.uri) persistRootSnapshot(root, resolvedResult)
            if (resolvedResult.entries.none { !it.isParent }) {
                showEmpty()
            } else {
                showContent()
            }
            if (!preserveScroll) binding.recyclerView.scrollToPosition(0)
        }
    }

    private suspend fun scanDirectory(
        dir: DocumentFile,
        root: DocumentFile
    ): DirectoryResult {
        val parent = if (dir.uri == root.uri) {
            null
        } else {
            dir.parentFile?.takeIf { isInsideRoot(it, root) } ?: root
        }
        val children = dir.listFiles().map { child ->
            currentCoroutineContext().ensureActive()
            val isDirectory = child.isDirectory
            Entry(
                doc = child,
                name = child.name ?: "?",
                mimeType = child.type,
                size = if (isDirectory) -1L else child.length(),
                lastModified = child.lastModified(),
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
        var node: DocumentFile? = file
        while (node != null) {
            if (node.uri == root.uri) return true
            node = node.parentFile
        }
        return false
    }

    private fun refreshCurrentDirectory(showProgress: Boolean = true) {
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
        val items = arrayOf(getString(R.string.rename), getString(R.string.delete))
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenameDialog(entry.doc, entry.name, entry.isDirectory)
                    1 -> showDeleteConfirm(entry.doc, entry.name)
                }
            }
            .show()
        return true
    }

    private fun showRenameDialog(doc: DocumentFile, currentName: String, isDirectory: Boolean) {
        val editText = EditText(this).apply {
            setText(currentName)
            val dot = currentName.lastIndexOf('.')
            val selectionEnd = if (!isDirectory && dot > 0) dot else currentName.length
            setSelection(0, selectionEnd)
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
                if (newName.isEmpty() || newName == currentName) return@setPositiveButton
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            doc.renameTo(newName)
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
        editText.requestFocus()
    }

    private fun showDeleteConfirm(doc: DocumentFile, name: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.confirm_delete, name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            doc.delete()
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

    private suspend fun resolveAmbiguousTextIcons(result: DirectoryResult): DirectoryResult {
        val resolved = result.entries.map { entry ->
            if (entry.isParent || entry.isDirectory ||
                TextFileSupport.extensionOf(entry.name) != "bak" ||
                TextFileClassifier.classify(entry.name, entry.mimeType) != TextFileVerdict.NEEDS_SNIFFING
            ) return@map entry
            val supported = TextFileSupport.isProbablyTextFileAsync(
                contentResolver,
                entry.doc.uri,
                entry.name,
                entry.mimeType,
            )
            if (supported) sniffedTextUris += entry.doc.uri.toString()
            entry
        }
        return result.copy(entries = resolved)
    }

    private fun isSupportedTextEntry(entry: Entry): Boolean =
        entry.doc.uri.toString() in sniffedTextUris ||
            TextFileClassifier.classify(entry.name, entry.mimeType) == TextFileVerdict.TEXT

    private inner class FileVH(val binding: ItemFileEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: Entry) {
            binding.name.text = entry.name
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
                        if (isSupportedTextEntry(entry)) R.drawable.ic_browser_text_file
                        else R.drawable.ic_browser_unknown_file
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

    companion object {
        private const val STATE_CURRENT_PATH = "current_path"
        private const val RECENT_EDIT_PREFS = "recent_edit_transient"
        private const val PREFS_NAME = "text_editor"
        private const val PREF_TREE_URI = "tree_uri"
        private const val PREF_ROOT_SNAPSHOT = "root_directory_snapshot_v1"
        private const val MAX_SESSION_DIRECTORY_SNAPSHOTS = 32
        private const val UNSUPPORTED_TOAST_DURATION_MS = 600L
        private const val MENU_THEME_TOGGLE = 1001
        const val EXTRA_RESULT_DISPLAY_NAME = "result_display_name"
        const val EXTRA_RESULT_LENGTH = "result_length"
    }
}

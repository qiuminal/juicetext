/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

/**
 * Pure Kotlin contracts for the editor's phase-one lifecycle behavior.
 *
 * These types deliberately have no Android dependencies, so state transitions can be verified by
 * local JVM tests. Activities should adapt UI/provider events to these contracts rather than
 * duplicating their decisions.
 */
internal class EditorDirtyState(initialGeneration: Long = 0L) {
    private var editGeneration: Long = initialGeneration
    private var savedGeneration: Long = initialGeneration

    val isDirty: Boolean
        get() = editGeneration != savedGeneration

    fun recordEdit() {
        editGeneration++
    }

    fun markPersisted() {
        savedGeneration = editGeneration
    }
}

internal enum class PendingEditsChoice {
    CANCEL,
    SAVE,
    DISCARD,
}

internal data class PendingEditsEffect(
    val continueClosing: Boolean,
    val saveBeforeClosing: Boolean,
    val deleteDraft: Boolean,
)

internal object PendingEditsPolicy {
    fun effect(choice: PendingEditsChoice): PendingEditsEffect = when (choice) {
        PendingEditsChoice.CANCEL -> PendingEditsEffect(
            continueClosing = false,
            saveBeforeClosing = false,
            deleteDraft = false,
        )
        PendingEditsChoice.SAVE -> PendingEditsEffect(
            continueClosing = true,
            saveBeforeClosing = true,
            deleteDraft = true,
        )
        PendingEditsChoice.DISCARD -> PendingEditsEffect(
            continueClosing = true,
            saveBeforeClosing = false,
            deleteDraft = true,
        )
    }

    fun shouldRestoreDraft(
        draftExists: Boolean,
        draftLastModified: Long,
        sourceLastModified: Long,
    ): Boolean = draftExists && draftLastModified >= sourceLastModified
}

/**
 * Prevents the onCreate/open-tree load from being repeated by the immediately following onResume.
 * Later resumes are refreshes and are allowed through.
 */
internal class DirectoryLoadCoordinator {
    private var suppressNextResume: Boolean = false

    fun onDirectoryOpened(): Boolean {
        suppressNextResume = true
        return true
    }

    fun onResume(hasCurrentDirectory: Boolean): Boolean {
        if (!hasCurrentDirectory) return false
        if (suppressNextResume) {
            suppressNextResume = false
            return false
        }
        return true
    }
}

/** A fail-fast seam that provider adapters can invoke immediately before blocking provider calls. */
internal class ProviderIoThreadPolicy(
    private val mainThreadId: Long,
) {
    fun checkCallAllowed(currentThreadId: Long = Thread.currentThread().id) {
        check(currentThreadId != mainThreadId) { "Provider I/O must not run on the main thread" }
    }
}

internal data class ExternalFileSnapshot(
    val lastModified: Long,
    val size: Long,
) {
    val isUsable: Boolean
        get() = size >= 0L
}

/**
 * Provider metadata can briefly fluctuate while a document is opened. A change is actionable only
 * when two consecutive observations agree and differ from the loaded baseline.
 */
internal object ExternalChangePolicy {
    fun isConfirmedChange(
        baseline: ExternalFileSnapshot,
        firstObservation: ExternalFileSnapshot,
        confirmation: ExternalFileSnapshot,
    ): Boolean {
        if (!baseline.isUsable || !firstObservation.isUsable || !confirmation.isUsable) return false
        if (firstObservation != confirmation) return false
        if (confirmation.size != baseline.size) return true
        return baseline.lastModified > 0L &&
            confirmation.lastModified > 0L &&
            confirmation.lastModified != baseline.lastModified
    }
}

internal object DirectoryLoadPresentation {
    fun shouldShowBlockingProgress(hasSnapshot: Boolean, backgroundRefresh: Boolean): Boolean =
        !hasSnapshot && !backgroundRefresh
}

internal data class DirectorySnapshotEntry(
    val uri: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val textVerdict: TextFileVerdict? = null,
)

/** Small, bounded root-directory snapshot used only for immediate cold-start presentation. */
internal object DirectorySnapshotCodec {
    // Version 2 persists the pre-read text/binary verdict. Rejecting v1 avoids briefly drawing an
    // ambiguous file with the wrong icon before the first post-upgrade scan finishes.
    private const val VERSION = "2"
    const val MAX_ENTRIES = 300

    fun encode(rootUri: String, entries: List<DirectorySnapshotEntry>): String = buildString {
        append(VERSION).append('\t').append(encodeField(rootUri)).append('\n')
        entries.take(MAX_ENTRIES).forEach { entry ->
            append(encodeField(entry.uri)).append('\t')
            append(encodeField(entry.name)).append('\t')
            append(encodeField(entry.mimeType.orEmpty())).append('\t')
            append(entry.size).append('\t')
            append(entry.lastModified).append('\t')
            append(if (entry.isDirectory) '1' else '0').append('\t')
            append(entry.textVerdict?.name.orEmpty()).append('\n')
        }
    }

    fun decode(value: String, expectedRootUri: String): List<DirectorySnapshotEntry>? {
        val lines = value.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        val header = lines.first().split('\t')
        if (header.size != 2 || header[0] != VERSION) return null
        if (decodeField(header[1]) != expectedRootUri) return null
        return lines.drop(1).take(MAX_ENTRIES).mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size != 7) return@mapNotNull null
            val size = fields[3].toLongOrNull() ?: return@mapNotNull null
            DirectorySnapshotEntry(
                uri = decodeField(fields[0]),
                name = decodeField(fields[1]),
                mimeType = decodeField(fields[2]).ifEmpty { null },
                size = size,
                lastModified = fields[4].toLongOrNull() ?: 0L,
                isDirectory = fields[5] == "1",
                textVerdict = fields[6].takeIf { it.isNotEmpty() }?.let {
                    runCatching { TextFileVerdict.valueOf(it) }.getOrNull()
                },
            )
        }
    }

    private fun encodeField(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodeField(value: String): String =
        java.net.URLDecoder.decode(value, Charsets.UTF_8.name())
}

internal object FileEntryMetadataFormatter {
    fun formatModifiedTime(epochMillis: Long, zoneId: java.time.ZoneId): String? {
        if (epochMillis <= 0L) return null
        return java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(zoneId)
            .format(java.time.format.DateTimeFormatter.ofPattern("yy-MM-dd HH:mm"))
    }

    fun formatSize(bytes: Long): String? {
        if (bytes < 0L) return null
        if (bytes < 1024L) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "%.1fKB".format(java.util.Locale.ROOT, kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return "%.1fMB".format(java.util.Locale.ROOT, mb)
        return "%.1fGB".format(java.util.Locale.ROOT, mb / 1024.0)
    }

    fun formatLine(epochMillis: Long, size: Long, zoneId: java.time.ZoneId): String =
        listOfNotNull(formatModifiedTime(epochMillis, zoneId), formatSize(size)).joinToString(" ")
}

internal enum class FileOpenRejection {
    UNSUPPORTED,
    TOO_LARGE,
}

internal object FileOpenPolicy {
    fun rejection(isSupported: Boolean, size: Long, maxSize: Long): FileOpenRejection? = when {
        !isSupported -> FileOpenRejection.UNSUPPORTED
        size > maxSize -> FileOpenRejection.TOO_LARGE
        else -> null
    }
}

internal enum class TextFileVerdict {
    TEXT,
    BINARY,
    NEEDS_SNIFFING,
}

internal enum class RegularFileIcon {
    EDITABLE_TEXT,
    UNKNOWN,
}

/**
 * Picks a non-colliding file name for a freshly created document. The SAF provider also
 * de-duplicates, but choosing the name up front keeps the result predictable and lets the browser
 * highlight the exact entry after the directory refreshes.
 */
internal object NewFileNaming {
    fun uniqueName(baseName: String, extension: String, existingNames: Set<String>): String {
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val existingLower = existingNames.mapTo(HashSet()) { it.lowercase() }
        val first = "$baseName$ext"
        if (first.lowercase() !in existingLower) return first
        var index = 2
        while (true) {
            val candidate = "$baseName $index$ext"
            if (candidate.lowercase() !in existingLower) return candidate
            index++
        }
    }

    /** Folder names have no extension; keep the same "name (2), name (3)" de-duplication. */
    fun uniqueDirectoryName(baseName: String, existingNames: Set<String>): String {
        val existingLower = existingNames.mapTo(HashSet()) { it.lowercase() }
        if (baseName.lowercase() !in existingLower) return baseName
        var index = 2
        while (true) {
            val candidate = "$baseName $index"
            if (candidate.lowercase() !in existingLower) return candidate
            index++
        }
    }
}

internal object FileIconPolicy {
    fun regularFileIcon(verdict: TextFileVerdict?): RegularFileIcon =
        if (verdict == TextFileVerdict.TEXT) RegularFileIcon.EDITABLE_TEXT
        else RegularFileIcon.UNKNOWN
}

/** Pure filename/MIME part of text-file classification; byte sniffing remains an I/O concern. */
internal object TextFileClassifier {
    private val knownTextExtensions = setOf(
        "txt", "md", "markdown", "rst", "log",
        "conf", "config", "ini", "cfg", "properties",
        "yaml", "yml", "toml", "json", "json5",
        "xml", "html", "htm", "css",
        "lua", "py", "sh", "bash", "zsh", "fish",
        "js", "ts", "kt", "java", "c", "cpp", "h", "hpp",
        "go", "rs", "rb", "php", "pl",
        "csv", "tsv", "dict", "phrase", "mb", "table",
    )

    private val knownBinaryExtensions = setOf(
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "svg",
        "mp3", "mp4", "wav", "flac", "ogg", "avi", "mkv",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "so", "dll", "exe", "apk", "dex", "jar", "class",
        "db", "sqlite", "sqlite3", "bin", "dat", "img",
    )

    fun classify(displayName: String, mimeType: String?): TextFileVerdict {
        val extension = displayName.substringAfterLast('.', "").lowercase()
        if (extension in knownBinaryExtensions) return TextFileVerdict.BINARY
        if (extension in knownTextExtensions) return TextFileVerdict.TEXT
        if (mimeType?.startsWith("text/", ignoreCase = true) == true) return TextFileVerdict.TEXT
        return TextFileVerdict.NEEDS_SNIFFING
    }

    fun sniff(sample: ByteArray): TextFileVerdict =
        if (sample.any { it == 0.toByte() }) TextFileVerdict.BINARY else TextFileVerdict.TEXT
}

/**
 * Maps Android ComponentCallbacks2 trim levels to editor feature decisions.
 *
 * TRIM_MEMORY_UI_HIDDEN (20) reports that the UI left the screen — it is delivered every time
 * the app is moved to the background, and sits numerically above TRIM_MEMORY_RUNNING_LOW (10).
 * Treating it as genuine memory pressure swapped every active tab to plain text and permanently
 * stripped syntax highlighting upon return.
 */
internal object LowMemoryPolicy {
    const val TRIM_MEMORY_RUNNING_MODERATE = 5
    const val TRIM_MEMORY_RUNNING_LOW = 10
    const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    const val TRIM_MEMORY_UI_HIDDEN = 20
    const val TRIM_MEMORY_BACKGROUND = 40
    const val TRIM_MEMORY_MODERATE = 60
    const val TRIM_MEMORY_COMPLETE = 80

    fun shouldDegrade(level: Int): Boolean =
        level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW

    /**
     * Trim levels delivered to a backgrounded process (>= 40) describe a hidden app whose
     * memory pressure signal no longer applies when the user brings juicetext back to the
     * foreground.
     */
    fun shouldRestoreOnResume(degradedAtLevel: Int): Boolean =
        degradedAtLevel >= TRIM_MEMORY_BACKGROUND
}

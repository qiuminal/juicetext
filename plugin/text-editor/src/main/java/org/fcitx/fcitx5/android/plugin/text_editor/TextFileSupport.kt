/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TextFileSupport {

    const val MAX_FILE_SIZE: Long = 100L * 1024 * 1024 // 100 MB

    // Files above this size use a lower-overhead editor configuration (no wrapping/autocomplete),
    // but supported TextMate syntax highlighting remains enabled.
    const val LARGE_FILE_THRESHOLD: Long = 10L * 1024 * 1024 // 10 MB
    const val LARGE_FILE_PAGE_BYTES: Int = 1024 * 1024 // 1 MB

    fun isLargeFile(bytes: Long): Boolean = bytes >= LARGE_FILE_THRESHOLD

    fun largeFileInitialPageBytes(fileSize: Long): Int =
        minOf(fileSize.coerceAtLeast(0L), LARGE_FILE_PAGE_BYTES.toLong()).toInt()

    private val KNOWN_TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "rst", "log",
        "conf", "config", "ini", "cfg", "properties",
        "yaml", "yml", "toml", "json", "json5",
        "xml", "html", "htm", "css",
        "lua", "py", "sh", "bash", "zsh", "fish",
        "js", "ts", "kt", "java", "c", "cpp", "h", "hpp",
        "go", "rs", "rb", "php", "pl",
        "csv", "tsv",
        "dict", "phrase",
        "mb", "table"
    )

    private val KNOWN_BINARY_EXTENSIONS = setOf(
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar",
        "jpg", "jpeg", "png", "gif", "bmp", "webp", "ico", "svg",
        "mp3", "mp4", "wav", "flac", "ogg", "avi", "mkv",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
        "so", "dll", "exe", "apk", "dex", "jar", "class",
        "db", "sqlite", "sqlite3",
        "bin", "dat", "img"
    )

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    suspend fun isProbablyTextFileAsync(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String?
    ): Boolean {
        return withContext(Dispatchers.IO) {
            isProbablyTextFile(resolver, uri, displayName, mimeType)
        }
    }

    fun isProbablyTextFile(
        resolver: ContentResolver,
        uri: Uri,
        displayName: String,
        mimeType: String?
    ): Boolean {
        val ext = extensionOf(displayName)
        if (ext.isNotEmpty()) {
            if (ext in KNOWN_BINARY_EXTENSIONS) return false
            if (ext in KNOWN_TEXT_EXTENSIONS) return true
        }
        if (mimeType != null) {
            if (mimeType.startsWith("text/")) return true
            // Many providers report octet-stream for unknown content; fall through to sniff
        }
        return sniffIsText(resolver, uri)
    }

    private fun sniffIsText(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(2048)
            val n = input.read(buf)
            if (n <= 0) return true
            for (i in 0 until n) {
                if (buf[i] == 0.toByte()) return false
            }
            true
        } ?: false
    } catch (_: Exception) {
        false
    }

    fun detectScopeName(displayName: String): String? {
        return when (extensionOf(displayName)) {
            "lua" -> "source.lua"
            "yaml", "yml" -> "source.yaml"
            "json", "json5" -> "source.json"
            "sh", "bash", "zsh" -> "source.shell"
            "md", "markdown" -> "text.html.markdown"
            "ini", "conf", "config", "cfg", "properties", "toml" -> "source.ini"
            else -> null
        }
    }
}

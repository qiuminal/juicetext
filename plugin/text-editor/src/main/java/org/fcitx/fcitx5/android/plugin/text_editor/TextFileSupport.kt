/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TextFileSupport {

    fun openInputStream(resolver: ContentResolver, uri: Uri): InputStream? =
        if (uri.scheme == "file") FileInputStream(requireNotNull(uri.path))
            else resolver.openInputStream(uri)

    fun isShizukuUri(uri: Uri): Boolean = uri.scheme == "shizuku"

    fun openOutputStream(resolver: ContentResolver, uri: Uri): OutputStream? =
        if (uri.scheme == "file") FileOutputStream(requireNotNull(uri.path))
        else resolver.openOutputStream(uri, "wt")

    const val MAX_FILE_SIZE: Long = 100L * 1024 * 1024 // 100 MB

    // Files above this size use a lower-overhead editor configuration (no wrapping/autocomplete),
    // but supported TextMate syntax highlighting remains enabled.
    // 5 MB captures standard word dictionaries and large YAML code tables (e.g. 300k+ line Rime
    // dicts are often 6MB-9MB) so they enter paged loading rather than parsing the entire buffer.
    const val LARGE_FILE_THRESHOLD: Long = 5L * 1024 * 1024 // 5 MB
    const val LARGE_FILE_PAGE_BYTES: Int = 1024 * 1024 // 1 MB

    // The first page is tokenized in full before TextMate publishes any style, so it bounds how
    // long an opened file shows as plain text. Keep it small; later pages are appended and
    // analyzed incrementally while their content is off-screen.
    const val LARGE_FILE_FIRST_PAGE_BYTES: Int = 128 * 1024 // 128 KB

    fun isLargeFile(bytes: Long): Boolean = bytes >= LARGE_FILE_THRESHOLD

    // Bytes to load before the first highlight pass runs. A size <= 0 means the caller has not read
    // the file snapshot yet — which is exactly the large-file first page — so bound it too rather
    // than asking the pager for nothing.
    fun largeFileInitialPageBytes(fileSize: Long): Int =
        if (fileSize <= 0L) LARGE_FILE_FIRST_PAGE_BYTES
        else minOf(fileSize, LARGE_FILE_FIRST_PAGE_BYTES.toLong()).toInt()

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
        openInputStream(resolver, uri)?.use { input ->
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

package org.fcitx.fcitx5.android.plugin.text_editor

import java.io.File

object ShizukuAccessPolicy {
    const val CHUNK_BYTES = 64 * 1024
    const val MAX_ENTRIES = 512

    fun validatePath(path: String): File {
        require(path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/")) {
            "Use /storage/emulated/0 paths"
        }
        val file = File(path).canonicalFile
        require(file.path == "/storage/emulated/0" || file.path.startsWith("/storage/emulated/0/")) {
            "Path is outside shared storage"
        }
        return file
    }

    fun validateRead(offset: Long, length: Int) {
        require(offset >= 0 && length in 1..CHUNK_BYTES) { "Invalid read range" }
    }
}

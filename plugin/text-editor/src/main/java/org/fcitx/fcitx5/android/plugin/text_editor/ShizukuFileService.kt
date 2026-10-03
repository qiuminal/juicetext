package org.fcitx.fcitx5.android.plugin.text_editor

import android.os.RemoteException
import java.io.File
import java.io.RandomAccessFile

/** The only privileged operations exposed to the app are bounded file reads and directory listing. */
class ShizukuFileService : IShizukuFileService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun fileSize(path: String): Long {
        val file = readableFile(path)
        if (!file.isFile) throw RemoteException("Not a file")
        return file.length()
    }

    override fun lastModified(path: String): Long = readableFile(path).lastModified()

    override fun read(path: String, offset: Long, length: Int): ByteArray {
        ShizukuAccessPolicy.validateRead(offset, length)
        val file = readableFile(path)
        if (!file.isFile) throw RemoteException("Not a file")
        RandomAccessFile(file, "r").use { input ->
            if (offset >= input.length()) return ByteArray(0)
            input.seek(offset)
            val buffer = ByteArray(minOf(length.toLong(), input.length() - offset).toInt())
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            return if (total == buffer.size) buffer else buffer.copyOf(total)
        }
    }

    override fun list(path: String): Array<String> {
        val directory = ShizukuAccessPolicy.validatePath(path)
        if (!directory.isDirectory || !directory.canRead()) throw RemoteException("Cannot list directory")
        val names = directory.list() ?: throw RemoteException("Cannot list directory")
        require(names.size <= ShizukuAccessPolicy.MAX_ENTRIES) { "Too many entries; enter a narrower path" }
        return names.sorted().map { name ->
            if (File(directory, name).isDirectory) "$name/" else name
        }.toTypedArray()
    }

    override fun truncate(path: String) {
        val file = writableFile(path)
        RandomAccessFile(file, "rw").use { it.setLength(0L) }
    }

    override fun write(path: String, offset: Long, data: ByteArray) {
        require(offset >= 0 && data.size <= ShizukuAccessPolicy.CHUNK_BYTES) { "Invalid write range" }
        RandomAccessFile(writableFile(path), "rw").use {
            it.seek(offset)
            it.write(data)
        }
    }

    private fun readableFile(path: String): File = ShizukuAccessPolicy.validatePath(path).also {
        if (!it.exists() || !it.canRead()) throw RemoteException("Cannot read file")
    }

    private fun writableFile(path: String): File = readableFile(path).also {
        if (!it.isFile || !it.canWrite()) throw RemoteException("Cannot write file")
    }
}

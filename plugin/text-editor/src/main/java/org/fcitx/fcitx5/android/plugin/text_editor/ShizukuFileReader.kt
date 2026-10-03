package org.fcitx.fcitx5.android.plugin.text_editor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File

class ShizukuFileReader(context: Context, private val keepConnected: Boolean = false) {
    private val context = context.applicationContext
    private val args = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuFileService::class.java.name)
    ).daemon(false).processNameSuffix("file-service").debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var connection: ServiceConnection? = null
    private var connected: CompletableDeferred<IShizukuFileService>? = null
    private var serviceBinder: IBinder? = null

    suspend fun <T> useService(block: suspend (IShizukuFileService) -> T): T {
        try {
            val remote = connect()
            return withContext(Dispatchers.IO) { block(remote) }
        } finally {
            if (!keepConnected) withContext(NonCancellable + Dispatchers.Main.immediate) { close() }
        }
    }

    private suspend fun connect(): IShizukuFileService = withContext(Dispatchers.Main.immediate) {
        check(Shizuku.pingBinder()) { "Shizuku is not running" }
        check(!Shizuku.isPreV11()) { "Update Shizuku" }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { "Shizuku permission required" }
        if (serviceBinder?.isBinderAlive == false) close()
        val pending = run {
            if (connected == null) {
                val pending = CompletableDeferred<IShizukuFileService>()
                val serviceConnection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        if (connected !== pending) return
                        serviceBinder = binder
                        pending.complete(IShizukuFileService.Stub.asInterface(binder))
                    }
                    override fun onServiceDisconnected(name: ComponentName) {
                        if (connected === pending) close()
                        pending.completeExceptionally(IllegalStateException("Shizuku disconnected"))
                    }
                }
                connected = pending
                connection = serviceConnection
                try {
                    Shizuku.bindUserService(args, serviceConnection)
                } catch (error: Throwable) {
                    connected = null
                    connection = null
                    throw error
                }
            }
            requireNotNull(connected)
        }
        try {
            withTimeout(10_000) { pending.await() }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            if (connected === pending) close()
            throw error
        }
    }

    fun close() {
        val serviceConnection = connection
        connection = null
        serviceBinder = null
        connected?.cancel()
        connected = null
        if (serviceConnection != null) {
            runCatching { Shizuku.unbindUserService(args, serviceConnection, false) }
        }
    }

    suspend fun importCopy(path: String): File = useService { remote ->
        val size = remote.fileSize(path)
        val modified = remote.lastModified(path)
        require(size in 0..TextFileSupport.MAX_FILE_SIZE) { "File is too large" }
        val output = File.createTempFile("shizuku-copy-", "-${File(path).name}", context.filesDir)
        try {
            output.outputStream().use { sink ->
                var offset = 0L
                while (offset < size) {
                    currentCoroutineContext().ensureActive()
                    val chunk = remote.read(path, offset,
                        minOf(ShizukuAccessPolicy.CHUNK_BYTES.toLong(), size - offset).toInt())
                    check(chunk.isNotEmpty() && chunk.size <= size - offset) { "File changed during read" }
                    sink.write(chunk)
                    offset += chunk.size
                }
                check(remote.fileSize(path) == size && remote.lastModified(path) == modified) {
                    "File changed during read"
                }
            }
            output
        } catch (e: Throwable) {
            output.delete()
            throw e
        }
    }

    suspend fun readText(path: String): String = useService { remote ->
        val size = remote.fileSize(path)
        require(size in 0..TextFileSupport.MAX_FILE_SIZE) { "File is too large" }
        val bytes = ByteArray(size.toInt())
        var offset = 0L
        while (offset < size) {
            currentCoroutineContext().ensureActive()
            val chunk = remote.read(path, offset,
                minOf(ShizukuAccessPolicy.CHUNK_BYTES.toLong(), size - offset).toInt())
            check(chunk.isNotEmpty()) { "Unexpected end of file" }
            chunk.copyInto(bytes, offset.toInt())
            offset += chunk.size
        }
        bytes.decodeToString()
    }

    suspend fun writeText(path: String, text: String) = useService { remote ->
        val bytes = text.toByteArray()
        remote.truncate(path)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + ShizukuAccessPolicy.CHUNK_BYTES, bytes.size)
            remote.write(path, offset.toLong(), bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    companion object { const val PERMISSION_REQUEST_CODE = 2821 }
}

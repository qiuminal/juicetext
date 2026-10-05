package org.fcitx.fcitx5.android.plugin.text_editor

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks the project's GitHub releases for a newer build and hands the APK to the system
 * downloader.
 *
 * The download deliberately runs in [DownloadManager] rather than in-process: fetching ~3 MB
 * inside the app would hold the user on a progress screen, while the system queue lets them keep
 * editing, survives the app being swiped away, and shows progress in the notification shade.
 */
internal object UpdateChecker {

    private const val RELEASES_API = "https://api.github.com/repos/qiuminal/juicetext/releases/latest"
    private const val REQUEST_TIMEOUT_MS = 15_000

    /** Outcome of a version query, so the caller can show the right message. */
    internal sealed interface Result {
        /** [tag] is the latest release tag (for example `v0.3.5`) and [apkUrl] its APK asset. */
        data class UpdateAvailable(val tag: String, val notes: String, val apkUrl: String) : Result

        /** The installed build is current; [tag] names what the feed reported. */
        data class UpToDate(val tag: String) : Result

        /** A release was found but it carried no `.apk` asset to install. */
        data class NoApk(val tag: String) : Result

        /** The feed could not be reached or parsed; [message] is user-facing. */
        data class Failed(val message: String) : Result
    }

    /** The latest release, or a failure. Never throws. */
    fun checkForUpdate(currentVersionName: String): Result = try {
        val body = httpGet(RELEASES_API)
        val json = JSONObject(body)
        val tag = json.optString("tag_name").ifBlank { return Result.Failed("no tag_name") }
        val latest = normalizeVersion(tag)
        if (!isNewerVersion(latest, normalizeVersion(currentVersionName))) {
            Result.UpToDate(tag)
        } else {
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            if (apkUrl.isBlank()) Result.NoApk(tag)
            else Result.UpdateAvailable(tag, json.optString("body"), apkUrl)
        }
    } catch (e: Exception) {
        Result.Failed(e.message ?: e.javaClass.simpleName)
    }

    /**
     * Queues [apkUrl] in the system downloader, naming the file after the release so the user can
     * find it in Downloads.
     *
     * Returns the download id, or `null` when the request could not be enqueued.
     */
    fun enqueueDownload(context: Context, apkUrl: String, tag: String): Long? = try {
        val fileName = "juicetext-${normalizeVersion(tag)}-release.apk"
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(fileName)
            .setDescription(context.getString(R.string.update_download_description))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val manager = ContextCompat.getSystemService(context, DownloadManager::class.java)
        manager?.enqueue(request)
    } catch (e: Exception) {
        null
    }

    /** Opens the release page so the user can install or read the notes themselves. */
    fun openReleasesPage(context: Context, tag: String) {
        val url = if (tag.isBlank()) "https://github.com/qiuminal/juicetext/releases"
        else "https://github.com/qiuminal/juicetext/releases/tag/$tag"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "juicetext-update-check").apply { isDaemon = true }
    }

    /** Runs [block] off the main thread and delivers its result back on the main thread. */
    fun checkAsync(currentVersionName: String, onResult: (Result) -> Unit) {
        io.execute {
            val result = checkForUpdate(currentVersionName)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(result) }
        }
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "juicetext-updater")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Strips a leading `v` and any pre-release/build suffix, keeping the numeric core. */
    internal fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')

    /**
     * True when [candidate] is strictly newer than [installed], compared segment by segment.
     *
     * Purely numeric comparison, so `0.3.10` correctly beats `0.3.9` where a string compare would
     * not. Missing segments count as zero, so `0.4` equals `0.4.0`.
     */
    internal fun isNewerVersion(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: return false }
        val b = installed.split('.').map { it.toIntOrNull() ?: return false }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}

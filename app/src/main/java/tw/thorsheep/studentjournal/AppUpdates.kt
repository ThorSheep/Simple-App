package tw.thorsheep.studentjournal

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(val tag: String, val title: String, val notes: String, val apkUrl: String, val versionCode: Int)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
    data object Unsupported : UpdateState
}

object AppUpdates {
    private const val API = "https://api.github.com/repos/ThorSheep/Simple-App/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/ThorSheep/Simple-App/releases/download/"
    private const val MAX_APK_BYTES = 120L * 1024 * 1024

    fun releaseCode(tag: String): Int? {
        val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(tag.trim()) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues[3].toIntOrNull() ?: return null
        return if (major <= 999 && minor <= 99 && patch <= 99) major * 10000 + minor * 100 + patch else null
    }

    fun parseRelease(raw: String): AppRelease {
        val root = JSONObject(raw)
        require(!root.optBoolean("draft") && !root.optBoolean("prerelease")) { "沒有可安裝的正式版本" }
        val tag = root.getString("tag_name")
        val code = releaseCode(tag) ?: error("Release 標籤格式無效：$tag")
        val asset = root.getJSONArray("assets").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.firstOrNull { item ->
                val name = item.optString("name")
                name.matches(Regex("simple-app-v\\d+\\.\\d+\\.\\d+\\.apk"))
            }
        } ?: error("Release 未提供正式 APK")
        val url = asset.getString("browser_download_url")
        require(url.startsWith(DOWNLOAD_PREFIX) && url.endsWith(".apk")) { "APK 下載位置不安全" }
        return AppRelease(tag, root.optString("name", tag), root.optString("body", ""), url, code)
    }

    suspend fun latest(context: Context): UpdateState = withContext(Dispatchers.IO) {
        if (BuildConfig.DEBUG) return@withContext UpdateState.Unsupported
        try {
            val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("Accept", "application/vnd.github+json"); setRequestProperty("User-Agent", "Simple-App/${BuildConfig.VERSION_NAME}"); connectTimeout = 15_000; readTimeout = 20_000
            }
            try { require(connection.responseCode == 200) { "無法取得版本資訊（HTTP ${connection.responseCode}）" }; val release = parseRelease(connection.inputStream.bufferedReader().use { reader -> reader.readText() }); if (release.versionCode > appVersionCode(context)) UpdateState.Available(release) else UpdateState.Current } finally { connection.disconnect() }
        } catch (e: Exception) { UpdateState.Failed(e.message ?: "檢查更新失敗") }
    }

    suspend fun download(context: Context, release: AppRelease): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(folder, "${release.tag}.apk")
            val temp = File(folder, "${release.tag}.download")
            temp.delete()
            val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 45_000; setRequestProperty("User-Agent", "Simple-App/${BuildConfig.VERSION_NAME}") }
            try {
                require(connection.responseCode in 200..299) { "APK 下載失敗（HTTP ${connection.responseCode}）" }
                val length = connection.contentLengthLong
                require(length in 1..MAX_APK_BYTES) { "APK 檔案大小無效" }
                connection.inputStream.use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) { val read = input.read(buffer); if (read < 0) break; total += read; require(total <= MAX_APK_BYTES) { "APK 檔案過大" }; output.write(buffer, 0, read) }
                    require(total == length) { "APK 下載不完整" }
                } }
            } finally { connection.disconnect() }
            @Suppress("DEPRECATION") val archive = context.packageManager.getPackageArchiveInfo(temp.absolutePath, 0) ?: error("APK 無法驗證")
            require(archive.packageName == context.packageName && appVersionCode(archive) > appVersionCode(context)) { "APK 並非此 App 的較新正式版本" }
            target.delete(); require(temp.renameTo(target)) { "無法儲存 APK" }; target
        }
    }

    fun install(context: Context, file: File): Boolean {
        if (!file.isFile) return false
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    @Suppress("DEPRECATION") private fun appVersionCode(context: Context): Int = appVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
    @Suppress("DEPRECATION") private fun appVersionCode(info: android.content.pm.PackageInfo): Int = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
}

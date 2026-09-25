package tw.thorsheep.studentjournal

import android.content.Context
import java.util.UUID

object SyncSettings {
    private const val FILE = "sync-settings"
    private const val DEVICE_ID = "deviceId"
    private const val SERVER_URL = "serverUrl"
    private const val TOKEN = "token"

    fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(FILE, 0)
        return prefs.getString(DEVICE_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(DEVICE_ID, it).apply() }
    }

    fun connection(context: Context): SyncConnection? {
        val prefs = context.getSharedPreferences(FILE, 0)
        val url = prefs.getString(SERVER_URL, null) ?: return null
        val token = prefs.getString(TOKEN, null) ?: return null
        return SyncConnection(url, deviceId(context), token).takeIf { runCatching { it.validate() }.isSuccess }
    }

    fun save(context: Context, serverUrl: String, token: String) {
        val connection = SyncConnection(serverUrl.trimEnd('/'), deviceId(context), token)
        connection.validate()
        context.getSharedPreferences(FILE, 0).edit().putString(SERVER_URL, connection.serverUrl).putString(TOKEN, token).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, 0).edit().remove(SERVER_URL).remove(TOKEN).apply()
    }
}

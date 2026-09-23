package tw.thorsheep.studentjournal

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SyncConnection(val serverUrl: String, val deviceId: String, val token: String) {
    fun validate() {
        require(serverUrl.startsWith("https://") && serverUrl.length <= 2000) { "同步伺服器必須使用 HTTPS" }
        require(deviceId.isNotBlank() && token.length >= 32) { "同步裝置設定無效" }
    }
}

data class RemoteSyncChange(val cursor: Long, val operation: SyncOutbox)
data class SyncResult(val acceptedOperationIds: List<String>, val cursor: Long, val changes: List<RemoteSyncChange>)

object SyncJson {
    const val PROTOCOL_VERSION = 1

    fun pairRequest(pairCode: String, deviceId: String, name: String): String = JSONObject()
        .put("pairCode", pairCode).put("deviceId", deviceId).put("name", name).toString()

    fun pairToken(raw: String): String = JSONObject(raw).getString("token").also { require(it.length >= 32) { "伺服器回傳的裝置憑證無效" } }

    fun syncRequest(connection: SyncConnection, cursor: Long, operations: List<SyncOutbox>): String {
        connection.validate(); require(cursor >= 0 && operations.size <= 1000) { "同步請求無效" }
        return JSONObject().put("protocolVersion", PROTOCOL_VERSION).put("deviceId", connection.deviceId).put("cursor", cursor)
            .put("operations", JSONArray().apply { operations.forEach { put(operation(it)) } }).toString()
    }

    fun syncResult(raw: String): SyncResult {
        val root = JSONObject(raw)
        val cursor = root.getLong("cursor").also { require(it >= 0) { "伺服器 cursor 無效" } }
        fun strings(key: String) = root.getJSONArray(key).let { values -> (0 until values.length()).map { values.getString(it) } }
        val changes = root.getJSONArray("changes").let { values -> (0 until values.length()).map { index ->
            val item = values.getJSONObject(index)
            RemoteSyncChange(item.getLong("cursor"), SyncOutbox(
                operationId = item.getString("operationId"), entityType = item.getString("entityType"), entityId = item.getString("entityId"),
                revision = item.getString("revision"), deviceId = item.getString("deviceId"), deleted = item.getBoolean("deleted"),
                payload = item.getJSONObject("payload").toString(), createdAt = 0
            ))
        } }
        return SyncResult(strings("acceptedOperationIds"), cursor, changes)
    }

    private fun operation(value: SyncOutbox): JSONObject = JSONObject()
        .put("operationId", value.operationId).put("entityType", value.entityType).put("entityId", value.entityId)
        .put("revision", value.revision).put("deviceId", value.deviceId).put("deleted", value.deleted)
        .put("payload", JSONObject(value.payload))
}

object SyncHttpClient {
    fun pair(serverUrl: String, pairCode: String, deviceId: String, name: String): String =
        SyncJson.pairToken(post(serverUrl, "/v1/pair", SyncJson.pairRequest(pairCode, deviceId, name)))

    fun sync(connection: SyncConnection, cursor: Long, operations: List<SyncOutbox>): SyncResult {
        connection.validate()
        return SyncJson.syncResult(post(connection.serverUrl, "/v1/sync", SyncJson.syncRequest(connection, cursor, operations), connection.token))
    }

    private fun post(serverUrl: String, path: String, body: String, token: String? = null): String {
        val url = URL(serverUrl.trimEnd('/') + path)
        require(url.protocol == "https") { "同步伺服器必須使用 HTTPS" }
        return (url.openConnection() as HttpURLConnection).run {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            outputStream.bufferedWriter().use { it.write(body) }
            val input = if (responseCode in 200..299) inputStream else errorStream
            val raw = input?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (responseCode !in 200..299) throw IllegalStateException(JSONObject(raw).optString("error", "同步伺服器錯誤"))
            raw
        }
    }
}

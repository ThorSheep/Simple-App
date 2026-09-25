package tw.thorsheep.studentjournal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncJsonTest {
    @Test fun createsServerCompatibleSyncRequest() {
        val connection = SyncConnection("https://sync.example.test", "device-a", "a".repeat(64))
        val operation = SyncOutbox("op-a", "money", "entry-a", "0000000001000-00000-device-a", "device-a", false, "{\"id\":\"entry-a\"}", 1)
        val root = JSONObject(SyncJson.syncRequest(connection, 7, listOf(operation)))
        assertEquals(1, root.getInt("protocolVersion"))
        assertEquals(7, root.getLong("cursor"))
        assertEquals("entry-a", root.getJSONArray("operations").getJSONObject(0).getJSONObject("payload").getString("id"))
    }

    @Test fun readsSyncResponse() {
        val raw = """{"acceptedOperationIds":["op-a"],"cursor":9,"changes":[{"cursor":9,"operationId":"op-b","entityType":"money","entityId":"entry-b","revision":"0000000001000-00000-device-b","deviceId":"device-b","deleted":false,"payload":{"id":"entry-b"}}]}"""
        val result = SyncJson.syncResult(raw)
        assertEquals(listOf("op-a"), result.acceptedOperationIds)
        assertEquals(9, result.cursor)
        assertEquals("entry-b", result.changes.single().operation.entityId)
        assertTrue(result.changes.single().operation.payload.contains("entry-b"))
    }
}

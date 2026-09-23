package tw.thorsheep.studentjournal

import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncSettingsTest {
    @Test fun savesAndClearsConnectionWithoutChangingDeviceId() {
        val context = RuntimeEnvironment.getApplication()
        SyncSettings.clear(context)
        val deviceId = SyncSettings.deviceId(context)
        SyncSettings.save(context, "https://sync.example.test/", "a".repeat(64))
        val connection = SyncSettings.connection(context)
        assertNotNull(connection)
        assertEquals(deviceId, connection!!.deviceId)
        assertEquals("https://sync.example.test", connection.serverUrl)
        SyncSettings.clear(context)
        assertNull(SyncSettings.connection(context))
    }
}

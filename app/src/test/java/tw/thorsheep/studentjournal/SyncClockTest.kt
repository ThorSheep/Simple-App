package tw.thorsheep.studentjournal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncClockTest {
    @Test fun nextVersionIncrementsLogicalClockAtSameTime() {
        val first = SyncClock.next(null, "device-a", 1000)
        val second = SyncClock.next(first, "device-a", 1000)
        assertEquals("0000000001000-00000-device-a", first)
        assertEquals("0000000001000-00001-device-a", second)
        assertTrue(SyncClock.isNewer(second, first))
    }

    @Test fun laterClockWinsAndDeviceIdBreaksTie() {
        val earlier = "0000000001000-00000-device-a"
        val later = SyncClock.next(earlier, "device-b", 2000)
        val tieA = "0000000002000-00000-device-a"
        val tieB = "0000000002000-00000-device-b"
        assertTrue(SyncClock.isNewer(later, earlier))
        assertTrue(SyncClock.isNewer(tieB, tieA))
        assertFalse(SyncClock.isNewer(tieA, tieB))
    }
}

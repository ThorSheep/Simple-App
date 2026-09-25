package tw.thorsheep.studentjournal

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncMoneyPayloadTest {
    @Test fun roundTripsMoneyEntry() {
        val entry = Entry(id = "money-1", type = "money", title = "午餐", date = "2026-09-23", cents = 12050, category = "午餐", note = "", direction = "支出")
        assertEquals(entry, SyncMoneyPayload.decode(SyncMoneyPayload.encode(entry)))
    }
}

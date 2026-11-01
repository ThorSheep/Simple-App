package tw.thorsheep.studentjournal

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementSubscriptionTest {
    @Test fun sourceCancellationIncludesLegacyAndCurrentCategories() {
        val subscribed = listOf("ncu#最新消息", "ncu#舊分類", "other#最新消息", "ncu")
        assertEquals(listOf("ncu#最新消息", "ncu#舊分類", "ncu"), subscriptionIdsForSource("ncu", subscribed))
    }
}

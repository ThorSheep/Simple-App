package tw.thorsheep.studentjournal

import android.app.NotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AnnouncementUpdateTest {
    @Test fun baselineFilteringAndDeduplicationSurviveCacheClear() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = JournalDb.get(context)
        val source = AnnouncementSource("test-source", "測試學校", "教學", "資工系", "https://example.org")
        val manager = context.getSystemService(NotificationManager::class.java)
        context.getSharedPreferences("settings", 0).edit().putBoolean("notify", true).commit()
        db.subscriptions().save(Subscription("${source.id}#活動"))
        db.automation().save(AnnouncementKeyword(text = "獎學金"))
        val old = Announcement("old", source.id, "獎學金申請", "2026-09-10", "https://example.org/old", "活動")
        AnnouncementUpdates.update(context, listOf(source)) { listOf(old) }
        assertEquals(0, manager.activeNotifications.size)
        val fresh = old.copy(id = "new", url = "https://example.org/new")
        val excluded = old.copy(id = "excluded", url = "https://example.org/excluded", category = "招生")
        AnnouncementUpdates.update(context, listOf(source)) { listOf(old, fresh, excluded) }
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(fresh.url, manager.activeNotifications.single().tag)
        manager.cancelAll()
        db.automation().clearNews()
        AnnouncementUpdates.update(context, listOf(source)) { listOf(old, fresh, excluded) }
        assertEquals(0, manager.activeNotifications.size)
        val failed = AnnouncementUpdates.update(context, listOf(source)) { error("網路無法連線") }
        assertTrue(failed.contains("失敗"))
        assertEquals(0, manager.activeNotifications.size)
        assertEquals(3, db.automation().seen().size)
    }
}

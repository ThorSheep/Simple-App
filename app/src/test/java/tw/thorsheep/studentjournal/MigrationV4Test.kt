package tw.thorsheep.studentjournal

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MigrationV4Test {
    @Test fun migratesRealDatabaseAndPreservesTaskOnCourseDeletion() = checkMigration(false)
    @Test fun migratesDiscardedPrototypeWithoutDuplicateCourses() = checkMigration(true)

    private fun checkMigration(prototype: Boolean) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, date TEXT NOT NULL, cents INTEGER NOT NULL, category TEXT NOT NULL, note TEXT NOT NULL, day INTEGER NOT NULL, start TEXT NOT NULL, end TEXT NOT NULL, room TEXT NOT NULL, teacher TEXT NOT NULL, courseId TEXT NOT NULL, done INTEGER NOT NULL, direction TEXT NOT NULL)")
            db.execSQL("CREATE TABLE subscriptions (sourceId TEXT NOT NULL PRIMARY KEY)")
            db.execSQL("CREATE TABLE announcements (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, title TEXT NOT NULL, date TEXT NOT NULL, url TEXT NOT NULL, category TEXT NOT NULL, fetchedAt INTEGER NOT NULL, read INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX index_announcements_sourceId ON announcements(sourceId)")
            db.execSQL("CREATE UNIQUE INDEX index_announcements_url ON announcements(url)")
            db.execSQL("INSERT INTO entries VALUES ('course1','course','資料結構','',0,'','',4,'09:00','10:00','A101','王老師','',0,'支出')")
            db.execSQL("INSERT INTO entries VALUES ('task1','task','作業','2026-09-10',0,'','保留備註',1,'','','','','course1',1,'支出')")
            db.execSQL("INSERT INTO entries VALUES ('money1','money','午餐','2026-09-10',15000,'午餐','',1,'','','','','',0,'支出')")
            db.execSQL("INSERT INTO subscriptions VALUES ('unit')")
            db.execSQL("INSERT INTO announcements VALUES ('news','unit','公告','2026-09-10','https://example.org/news','',1,0)")
            if (prototype) {
                db.execSQL("CREATE TABLE courses (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, teacher TEXT NOT NULL, color INTEGER NOT NULL, archived INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE course_meetings (id TEXT NOT NULL PRIMARY KEY, courseId TEXT NOT NULL, day INTEGER NOT NULL, start TEXT NOT NULL, end TEXT NOT NULL, room TEXT NOT NULL)")
                db.execSQL("CREATE INDEX index_course_meetings_courseId ON course_meetings(courseId)")
                db.execSQL("CREATE TABLE academic_items (id TEXT NOT NULL PRIMARY KEY, courseId TEXT NOT NULL, type TEXT NOT NULL, title TEXT NOT NULL, dueDate TEXT NOT NULL, presentationDate TEXT NOT NULL, groupWork INTEGER NOT NULL, groupNote TEXT NOT NULL, note TEXT NOT NULL, done INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX index_academic_items_courseId ON academic_items(courseId)")
                db.execSQL("CREATE INDEX index_academic_items_dueDate ON academic_items(dueDate)")
                db.execSQL("CREATE TABLE announcement_keywords (text TEXT NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL)")
                db.execSQL("INSERT INTO courses VALUES ('course1','資料結構','王老師',4281690968,0)")
                db.execSQL("INSERT INTO course_meetings VALUES ('meeting1','course1',4,'09:00','10:00','A101')")
                db.execSQL("INSERT INTO academic_items VALUES ('task1','course1','報告','已編輯事項','2026-09-10','2026-09-11',1,'第二組','保留備註',1)")
                db.execSQL("INSERT INTO announcement_keywords VALUES ('獎學金',1)")
            }
            db.version = if (prototype) 4 else 3
        }
        val db = Room.databaseBuilder(context, JournalDb::class.java, name).addMigrations(MIGRATION_3_5, MIGRATION_4_5).allowMainThreadQueries().build()
        try {
            assertEquals("資料結構", db.academic().allCourses().single().title)
            assertEquals("09:00", db.academic().allMeetings().single().start)
            val task = db.academic().allItems().single()
            assertEquals("course1", task.courseId); assertTrue(task.done); assertEquals("保留備註", task.note)
            if (prototype) { assertEquals("已編輯事項", task.title); assertEquals("2026-09-11", task.presentationDate); assertTrue(task.grouped); assertEquals("獎學金", db.automation().keywords().single().text) }
            assertEquals(15000L, db.automation().entries().single().cents)
            assertEquals("unit", db.automation().subscriptions().single().sourceId)
            assertEquals(listOf("https://example.org/news"), db.automation().seen())
            val course = db.academic().allCourses().single()
            db.academic().save(course.copy(title = "資料結構 II"), listOf(CourseMeeting(courseId = course.id)))
            assertEquals("course1", db.academic().allItems().single().courseId)
            db.academic().deleteCourse(course.id)
            assertTrue(db.academic().allMeetings().isEmpty())
            assertNull(db.academic().allItems().single().courseId)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

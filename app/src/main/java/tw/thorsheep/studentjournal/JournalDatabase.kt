package tw.thorsheep.studentjournal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "entries")
data class Entry(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: String,
    val title: String,
    val date: String = "",
    val cents: Long = 0,
    val category: String = "",
    val note: String = "",
    val day: Int = 1,
    val start: String = "",
    val end: String = "",
    val room: String = "",
    val teacher: String = "",
    val courseId: String = "",
    val done: Boolean = false,
    val direction: String = "支出"
)

@Entity(tableName = "subscriptions")
data class Subscription(@PrimaryKey val sourceId: String)

@Entity(
    tableName = "announcements",
    indices = [Index(value = ["sourceId"]), Index(value = ["url"], unique = true)]
)
data class Announcement(
    @PrimaryKey val id: String, val sourceId: String, val title: String, val date: String,
    val url: String, val category: String = "", val fetchedAt: Long = System.currentTimeMillis(),
    val read: Boolean = false
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries")
    fun observe(): Flow<List<Entry>>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun find(id: String): Entry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: Entry)

    @Insert
    suspend fun insertAll(entries: List<Entry>)

    @Query("DELETE FROM entries")
    suspend fun clear()

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteId(id: String)

    @Query("UPDATE entries SET courseId = '' WHERE courseId = :id")
    suspend fun unlink(id: String)

    @Transaction
    suspend fun delete(entry: Entry) {
        unlink(entry.id); deleteId(entry.id)
    }

    @Transaction
    suspend fun replace(entries: List<Entry>) {
        clear(); insertAll(entries)
    }
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions")
    fun observe(): Flow<List<Subscription>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(item: Subscription)

    @Query("DELETE FROM subscriptions WHERE sourceId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM subscriptions")
    suspend fun clear()

    @Insert
    suspend fun insertAll(items: List<Subscription>)

    @Transaction
    suspend fun replace(items: List<Subscription>) {
        clear(); insertAll(items)
    }
}

@Dao
interface AnnouncementDao {
    @Query("SELECT * FROM announcements WHERE sourceId IN (:sourceIds) ORDER BY date DESC, fetchedAt DESC LIMIT 250")
    fun observe(sourceIds: List<String>): Flow<List<Announcement>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<Announcement>)

    @Query("UPDATE announcements SET read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("DELETE FROM announcements WHERE fetchedAt < :before")
    suspend fun trim(before: Long)
}

@Database(
    entities = [Entry::class, Subscription::class, Announcement::class, Course::class, CourseMeeting::class, AcademicItem::class, AnnouncementKeyword::class, SeenAnnouncement::class, SyncMetadata::class, SyncOutbox::class, SyncState::class],
    version = 6,
    exportSchema = false
)
abstract class JournalDb : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun announcements(): AnnouncementDao
    abstract fun academic(): AcademicDao
    abstract fun automation(): AutomationDao
    abstract fun sync(): SyncDao

    companion object {
        @Volatile
        private var instance: JournalDb? = null

        fun get(context: Context): JournalDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                JournalDb::class.java,
                "journal.db"
            )
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE entries ADD COLUMN direction TEXT NOT NULL DEFAULT '支出'")
                        db.execSQL("UPDATE entries SET direction = category WHERE type = 'money' AND category IN ('支出','收入')")
                        db.execSQL("UPDATE entries SET category = '' WHERE type = 'money'")
                        db.execSQL("CREATE TABLE IF NOT EXISTS subscriptions (sourceId TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS announcements (id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, title TEXT NOT NULL, date TEXT NOT NULL, url TEXT NOT NULL, fetchedAt INTEGER NOT NULL, read INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_announcements_sourceId ON announcements (sourceId)")
                        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_announcements_url ON announcements (url)")
                    }
                }, object : Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE announcements ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                    }
                }, MIGRATION_3_5, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
        }
    }
}

package tw.thorsheep.studentjournal

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
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
    entities = [Entry::class, Subscription::class, Announcement::class],
    version = 3,
    exportSchema = false
)
abstract class JournalDb : RoomDatabase() {
    abstract fun entries(): EntryDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun announcements(): AnnouncementDao

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
                }).build().also { instance = it }
        }
    }
}

data class AnnouncementSource(
    val id: String,
    val school: String,
    val group: String,
    val name: String,
    val url: String,
    val parser: AnnouncementParser = AnnouncementParser.GENERIC,
    val categories: List<String> = emptyList(),
    val parent: String = ""
)

enum class AnnouncementParser { GENERIC, SHSD_JSON, CSIE_SECTIONS, NTOU_CSIE, NTOU_LIST }

object AnnouncementCatalog {
    private const val NCU_ASSET = "announcements/ncu.csv"
    private const val NTOU_ASSET = "announcements/ntou.csv"
    private const val AVAILABLE_ASSET = "announcements/available.csv"

    fun sources(context: Context): List<AnnouncementSource> =
        (loadCsv(context, NCU_ASSET) + loadCsv(context, NTOU_ASSET))
            .filter { (it.school to it.name) in loadAvailable(context) }

    private fun loadAvailable(context: Context): Set<Pair<String, String>> =
        context.assets.open(AVAILABLE_ASSET).bufferedReader().use { reader ->
            reader.readLines().drop(1).mapNotNull { line ->
                val fields = line.split(',').map { it.trim() }
                if (fields.size == 2 && fields.all { it.isNotBlank() }) fields[0] to fields[1] else null
            }.toSet()
        }

    private fun loadCsv(context: Context, assetName: String): List<AnnouncementSource> =
        context.assets.open(assetName).bufferedReader().use { reader ->
            reader.readLines().drop(1).mapNotNull { line ->
                val fields = line.split(',').map { it.trim().removeSurrounding("\"").replace("\"\"", "\"") }
                if (fields.size < 7) return@mapNotNull null
                val school = fields[0]
                val group = fields[1]
                val parent = fields[2]
                val name = fields[3]
                val url = fields[4]
                if (school.isBlank() || group.isBlank() || name.isBlank() || url.isBlank()) return@mapNotNull null
                val parser = runCatching { AnnouncementParser.valueOf(fields[5]) }
                    .getOrDefault(AnnouncementParser.GENERIC)
                AnnouncementSource(
                    id = sourceId(school, group, parent, name), school = school, group = group,
                    name = name, url = url, parser = parser,
                    categories = fields[6].split('|').filter { it.isNotBlank() }, parent = parent
                )
            }
        }

    private fun sourceId(school: String, group: String, parent: String, name: String): String = when (school) {
        "國立中央大學" -> when (name) {
            "教務處" -> "ncu-academic"
            "電子計算機中心" -> "ncu-computer"
            "圖書館" -> "ncu-library"
            "住宿服務組" -> "ncu-accommodation"
            "資訊工程學系" -> "ncu-csie"
            else -> stableId(school, group, parent, name)
        }
        "國立臺灣海洋大學" -> when (name) {
            "學校公告" -> "ntou-campus"
            "教務處" -> "ntou-academic"
            "學務處" -> "ntou-student"
            "生活輔導組" -> "ntou-life"
            "課外活動指導組" -> "ntou-club"
            "校安中心" -> "ntou-safety"
            "國際事務處" -> "ntou-oia"
            "秘書組" -> "ntou-secretariat"
            "工學院" -> "ntou-engineering"
            "國際學院" -> "ntou-international"
            else -> stableId(school, group, parent, name)
        }
        else -> stableId(school, group, parent, name)
    }

    private fun stableId(school: String, group: String, parent: String, name: String) =
        "source-" + Integer.toUnsignedString("$school|$group|$parent|$name".hashCode(), 16)

    /* Retained only as a migration reference. Runtime sources are loaded from CSV assets above.
    private val fallbackSources = listOf(
        AnnouncementSource(
            "ncu-campus",
            "國立中央大學",
            "全校公告",
            "校園公告",
            "https://www.ncu.edu.tw/p/403-1000-15-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ncu-academic",
            "國立中央大學",
            "行政單位",
            "教務處",
            "https://pdc.adm.ncu.edu.tw/p/403-1019-495-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ncu-computer",
            "國立中央大學",
            "行政單位",
            "電算中心",
            "https://www.cc.ncu.edu.tw/p/403-1033-14-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ncu-library",
            "國立中央大學",
            "行政單位",
            "圖書館",
            "https://www.lib.ncu.edu.tw/"
        ),
        AnnouncementSource(
            "ncu-accommodation",
            "國立中央大學",
            "行政單位",
            "住宿服務組",
            "https://shsd.ncu.edu.tw/",
            AnnouncementParser.SHSD_JSON,
            listOf("一般公告", "修繕相關", "活動相關", "住宿相關", "徵才訊息", "重要公告", "其他")
        ),
        AnnouncementSource(
            "ncu-csie",
            "國立中央大學",
            "教學單位",
            "資訊工程學系",
            "https://www.csie.ncu.edu.tw/",
            AnnouncementParser.CSIE_SECTIONS,
            listOf("得獎訊息", "徵才訊息", "招生快訊", "演講公告", "活動快訊", "課程訊息", "系辦公告")
        ),
        AnnouncementSource(
            "ncu-engineering",
            "國立中央大學",
            "教學單位",
            "工學院",
            "https://www.ec.ncu.edu.tw/"
        ),
        AnnouncementSource(
            "ncu-law",
            "國立中央大學",
            "教學單位",
            "法律與政府研究所",
            "https://www.lawgov.ncu.edu.tw/p/403-1002-34-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ntou-campus",
            "國立臺灣海洋大學",
            "全校公告",
            "學校公告",
            "https://www.ntou.edu.tw/"
        ),
        AnnouncementSource(
            "ntou-academic",
            "國立臺灣海洋大學",
            "行政單位",
            "教務處",
            "https://academics.ntou.edu.tw/main-board.aspx"
        ),
        AnnouncementSource(
            "ntou-student",
            "國立臺灣海洋大學",
            "行政單位",
            "學務處",
            "https://stu.ntou.edu.tw/"
        ),
        AnnouncementSource(
            "ntou-life",
            "國立臺灣海洋大學",
            "行政單位",
            "生活輔導組",
            "https://stu.ntou.edu.tw/p/403-1023-1095-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ntou-club",
            "國立臺灣海洋大學",
            "行政單位",
            "課外活動指導組",
            "https://stu.ntou.edu.tw/p/403-1023-1024-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ntou-safety",
            "國立臺灣海洋大學",
            "行政單位",
            "校安中心",
            "https://stu.ntou.edu.tw/p/403-1023-1054-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ntou-oia",
            "國立臺灣海洋大學",
            "行政單位",
            "國際事務處",
            "https://oia.ntou.edu.tw/p/403-1022-11-1.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ntou-secretariat",
            "國立臺灣海洋大學",
            "行政單位",
            "秘書組",
            "https://secretariat.ntou.edu.tw/app/home.php?Lang=zh-tw"
        ),
        AnnouncementSource(
            "ntou-engineering",
            "國立臺灣海洋大學",
            "教學單位",
            "工學院",
            "https://ce.ntou.edu.tw/index.php"
        ),
        AnnouncementSource(
            "ntou-international",
            "國立臺灣海洋大學",
            "教學單位",
            "國際學院",
            "https://ic.ntou.edu.tw/"
        )
    ) */
}

object Backup {
    const val MAX_BYTES = 5 * 1024 * 1024
    private val quickDestinations = setOf("money", "course", "task", "announcements")
    data class Archive(
        val entries: List<Entry>,
        val subscriptions: List<Subscription>? = null,
        val quick: List<String>? = null
    )
    fun validate(e: Entry) {
        require(e.id.isNotBlank() && e.id.length <= 100) { "資料識別碼無效" }
        require(e.title.isNotBlank() && e.title.length <= 200) { "名稱不可空白或超過 200 字" }
        require(
            listOf(
                e.note,
                e.room,
                e.teacher,
                e.category,
                e.courseId
            ).all { it.length <= 2000 }) { "文字欄位過長" }
        when (e.type) {
            "money" -> {
                require(LocalDate.parse(e.date).toString() == e.date) { "日期須為 YYYY-MM-DD" }
                require(e.cents in 1..99999999999L) { "金額須大於 0，且不超過 999,999,999.99" }
                require(e.direction in listOf("支出", "收入")) { "收支類型無效" }
                require(e.category in AnnouncementCategories.values) { "請選擇常用分類" }
            }

            "course" -> {
                require(e.day in 1..7) { "星期須為 1～7" }
                require(LocalTime.parse(e.start) < LocalTime.parse(e.end)) { "結束時間須晚於開始時間" }
            }

            "task" -> if (e.date.isNotEmpty()) require(
                LocalDate.parse(e.date).toString() == e.date
            ) { "日期須為 YYYY-MM-DD" }

            else -> error("未知資料類型")
        }
    }

    fun encode(
        entries: List<Entry>,
        subscriptions: List<Subscription> = emptyList(),
        quick: List<String> = emptyList()
    ): String =
        JSONObject().put("app", "simple-app").put("schemaVersion", 3)
            .put("exportedAt", java.time.Instant.now().toString())
            .put("entries", JSONArray().apply {
                entries.forEach { e ->
                    put(
                        JSONObject().put("id", e.id).put("type", e.type).put("title", e.title)
                            .put("date", e.date).put("cents", e.cents).put("category", e.category)
                            .put("note", e.note)
                            .put("day", e.day).put("start", e.start).put("end", e.end)
                            .put("room", e.room)
                            .put("teacher", e.teacher).put("courseId", e.courseId)
                            .put("done", e.done).put("direction", e.direction)
                    )
                }
            }).put("subscriptions", JSONArray().apply {
                subscriptions.forEach { put(it.sourceId) }
            }).put("quick", JSONArray().apply {
                quick.forEach { put(it) }
            }).toString(2)

    fun decode(raw: String): List<Entry> = decodeArchive(raw).entries

    fun decodeArchive(raw: String): Archive {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "備份檔不得超過 5 MB" }
        val root = JSONObject(raw)
        val version = root.getInt("schemaVersion")
        require(root.getString("app") == "simple-app" && version in 1..3) { "不是支援的Simple App備份" }
        val array = root.getJSONArray("entries")
        require(array.length() <= 20000) { "備份資料過多" }
        val entries = (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            require(o.get("cents") is Int || o.get("cents") is Long) { "金額必須以整數分儲存" }
            require(o.get("day") is Int && o.get("done") is Boolean) { "欄位型別無效" }
            val oldCategory = o.getString("category")
            Entry(
                o.getString("id"),
                o.getString("type"),
                o.getString("title"),
                o.getString("date"),
                o.getLong("cents"),
                o.getString("category"),
                o.getString("note"),
                o.getInt("day"),
                o.getString("start"),
                o.getString("end"),
                o.getString("room"),
                o.getString("teacher"),
                o.getString("courseId"),
                o.getBoolean("done"),
                if (version == 1 && oldCategory in listOf(
                        "支出",
                        "收入"
                    )
                ) oldCategory else o.optString("direction", "支出")
            )
                .let {
                    if (version == 1 && it.type == "money") it.copy(category = it.note.takeIf { n -> n in AnnouncementCategories.values }
                        ?: "日用品", note = "") else it
                }.also(::validate)
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "備份含重複識別碼" }
        val courses = entries.filter { it.type == "course" }.map { it.id }.toSet()
        require(entries.all { it.courseId.isEmpty() || it.courseId in courses }) { "關聯課程不存在" }
        if (version < 3) return Archive(entries)
        val subscriptions = root.getJSONArray("subscriptions").let { array ->
            (0 until array.length()).map { Subscription(array.getString(it)) }.also { items ->
                require(items.all { it.sourceId.isNotBlank() && it.sourceId.length <= 300 }) { "公告訂閱無效" }
                require(items.map { it.sourceId }.distinct().size == items.size) { "備份含重複公告訂閱" }
            }
        }
        val quick = root.getJSONArray("quick").let { array ->
            (0 until array.length()).map { array.getString(it) }.also { items ->
                require(items.size <= 4 && items.all { it in quickDestinations } && items.distinct().size == items.size) { "常用功能設定無效" }
            }
        }
        return Archive(entries, subscriptions, quick)
    }
}

object AnnouncementCategories {
    val values = listOf(
        "早餐",
        "午餐",
        "晚餐",
        "飲品",
        "點心",
        "酒類",
        "交通",
        "購物",
        "娛樂",
        "日用品",
        "房租",
        "醫療",
        "社交",
        "禮物",
        "數位"
    )
}

package tw.thorsheep.studentjournal

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity(tableName = "entries")
data class Entry(@PrimaryKey val id: String = UUID.randomUUID().toString(), val type: String,
    val title: String, val date: String = "", val cents: Long = 0, val category: String = "",
    val note: String = "", val day: Int = 1, val start: String = "", val end: String = "",
    val room: String = "", val teacher: String = "", val courseId: String = "", val done: Boolean = false)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries") fun observe(): Flow<List<Entry>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(entry: Entry)
    @Insert suspend fun insertAll(entries: List<Entry>)
    @Query("DELETE FROM entries") suspend fun clear()
    @Query("DELETE FROM entries WHERE id = :id") suspend fun deleteId(id: String)
    @Query("UPDATE entries SET courseId = '' WHERE courseId = :id") suspend fun unlink(id: String)
    @Transaction suspend fun delete(entry: Entry) { unlink(entry.id); deleteId(entry.id) }
    @Transaction suspend fun replace(entries: List<Entry>) { clear(); insertAll(entries) }
}
@Database(entities = [Entry::class], version = 1, exportSchema = false)
abstract class JournalDb : RoomDatabase() {
    abstract fun entries(): EntryDao
    companion object {
        @Volatile private var instance: JournalDb? = null
        fun get(context: Context): JournalDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, JournalDb::class.java, "journal.db").build().also { instance = it }
        }
    }
}

object Backup {
    const val MAX_BYTES = 5 * 1024 * 1024
    fun validate(e: Entry) {
        require(e.id.isNotBlank() && e.id.length <= 100) { "資料識別碼無效" }
        require(e.title.isNotBlank() && e.title.length <= 200) { "名稱不可空白或超過 200 字" }
        require(listOf(e.note, e.room, e.teacher, e.category, e.courseId).all { it.length <= 2000 }) { "文字欄位過長" }
        when(e.type) {
            "money" -> {
                require(LocalDate.parse(e.date).toString() == e.date) { "日期須為 YYYY-MM-DD" }
                require(e.cents in 1..99999999999L) { "金額須大於 0，且不超過 999,999,999.99" }
                require(e.category in listOf("支出", "收入")) { "收支類型無效" }
            }
            "course" -> {
                require(e.day in 1..7) { "星期須為 1～7" }
                require(LocalTime.parse(e.start) < LocalTime.parse(e.end)) { "結束時間須晚於開始時間" }
            }
            "task" -> if(e.date.isNotEmpty()) require(LocalDate.parse(e.date).toString() == e.date) { "日期須為 YYYY-MM-DD" }
            else -> error("未知資料類型")
        }
    }
    fun encode(entries: List<Entry>): String = JSONObject().put("app", "simple-app").put("schemaVersion", 1)
        .put("exportedAt", java.time.Instant.now().toString()).put("entries", JSONArray().apply {
            entries.forEach { e -> put(JSONObject().put("id", e.id).put("type", e.type).put("title", e.title)
                .put("date", e.date).put("cents", e.cents).put("category", e.category).put("note", e.note)
                .put("day", e.day).put("start", e.start).put("end", e.end).put("room", e.room)
                .put("teacher", e.teacher).put("courseId", e.courseId).put("done", e.done)) }
        }).toString(2)
    fun decode(raw: String): List<Entry> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "備份檔不得超過 5 MB" }
        val root = JSONObject(raw)
        require(root.getString("app") == "simple-app" && root.getInt("schemaVersion") == 1) { "不是支援的Simple App備份" }
        val array = root.getJSONArray("entries")
        require(array.length() <= 20000) { "備份資料過多" }
        val entries = (0 until array.length()).map { index ->
            val o = array.getJSONObject(index)
            require(o.get("cents") is Int || o.get("cents") is Long) { "金額必須以整數分儲存" }
            require(o.get("day") is Int && o.get("done") is Boolean) { "欄位型別無效" }
            Entry(o.getString("id"), o.getString("type"), o.getString("title"), o.getString("date"),
                o.getLong("cents"), o.getString("category"), o.getString("note"), o.getInt("day"),
                o.getString("start"), o.getString("end"), o.getString("room"), o.getString("teacher"),
                o.getString("courseId"), o.getBoolean("done")).also(::validate)
        }
        require(entries.map { it.id }.distinct().size == entries.size) { "備份含重複識別碼" }
        val courses = entries.filter { it.type == "course" }.map { it.id }.toSet()
        require(entries.all { it.courseId.isEmpty() || it.courseId in courses }) { "關聯課程不存在" }
        return entries
    }
}

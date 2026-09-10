package tw.thorsheep.studentjournal

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class ArchiveV4(val money: List<Entry>, val courses: List<Course>, val meetings: List<CourseMeeting>,
    val items: List<AcademicItem>, val subscriptions: List<Subscription>?, val keywords: List<AnnouncementKeyword>, val options: AppOptions?)

object BackupV4 {
    fun encode(a: ArchiveV4): String {
        val options = a.options ?: AppOptions()
        val root = JSONObject(Backup.encode(a.money, a.subscriptions.orEmpty(), options.quick.filter { it != "agenda" }))
        root.put("schemaVersion", 4)
        root.put("courses", JSONArray().apply { a.courses.forEach { c -> put(JSONObject().put("id", c.id).put("title", c.title).put("teacher", c.teacher).put("room", c.room).put("color", c.color).put("archived", c.archived)) } })
        root.put("meetings", JSONArray().apply { a.meetings.forEach { m -> put(JSONObject().put("id", m.id).put("courseId", m.courseId).put("day", m.day).put("start", m.start).put("end", m.end).put("room", m.room)) } })
        root.put("items", JSONArray().apply { a.items.forEach { i -> put(JSONObject().put("id", i.id).put("title", i.title).put("courseId", i.courseId ?: JSONObject.NULL).put("kind", i.kind).put("date", i.date).put("time", i.time).put("presentationDate", i.presentationDate).put("presentationTime", i.presentationTime).put("grouped", i.grouped).put("groupNote", i.groupNote).put("note", i.note).put("done", i.done).put("important", i.important)) } })
        root.put("keywords", JSONArray().apply { a.keywords.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("enabled", it.enabled)) } })
        root.put("options", JSONObject().put("quick", JSONArray(options.quick)).put("showCourses", options.showCourses).put("showTasks", options.showTasks).put("intervalHours", options.intervalHours).put("wifiOnly", options.wifiOnly).put("notify", options.notify))
        return root.toString(2).also { require(it.toByteArray().size <= Backup.MAX_BYTES) { "備份超過 5 MB" } }
    }
    fun decode(raw: String): ArchiveV4 {
        require(raw.toByteArray().size <= Backup.MAX_BYTES) { "備份不得超過 5 MB" }
        val root = JSONObject(raw)
        if (root.getInt("schemaVersion") < 4) {
            val old = Backup.decodeArchive(raw)
            val courses = old.entries.filter { it.type == "course" }.map { Course(it.id, it.title, it.teacher, it.room) }
            return ArchiveV4(old.entries.filter { it.type == "money" }, courses,
                old.entries.filter { it.type == "course" }.map { CourseMeeting(it.id, it.id, it.day, it.start, it.end, it.room) },
                old.entries.filter { it.type == "task" }.map { AcademicItem(id = it.id, title = it.title, courseId = it.courseId.ifBlank { null }, date = it.date, note = it.note, done = it.done) },
                old.subscriptions, emptyList(), old.quick?.let { AppOptions(quick = it) })
        }
        require(root.getInt("schemaVersion") == 4 && root.getString("app") == "simple-app") { "不支援的備份版本" }
        val base = Backup.decodeArchive(JSONObject(root.toString()).put("schemaVersion", 3).toString())
        require(base.entries.all { it.type == "money" }) { "新版 entries 僅接受記帳資料" }
        fun objects(key: String): List<JSONObject> = root.getJSONArray(key).let { arr ->
            require(arr.length() <= 20000) { "備份資料過多" }; (0 until arr.length()).map { arr.getJSONObject(it) }
        }
        fun JSONObject.bool(key: String): Boolean { require(get(key) is Boolean) { "布林欄位無效" }; return getBoolean(key) }
        val courses = objects("courses").map { Course(it.getString("id"), it.getString("title"), it.getString("teacher"), it.getString("room"), it.getInt("color"), it.bool("archived")) }
        val meetings = objects("meetings").map { CourseMeeting(it.getString("id"), it.getString("courseId"), it.getInt("day"), it.getString("start"), it.getString("end"), it.getString("room")) }
        val items = objects("items").map { AcademicItem(it.getString("id"), it.getString("title"), if (it.isNull("courseId")) null else it.getString("courseId"), it.getString("kind"), it.getString("date"), it.getString("time"), it.getString("presentationDate"), it.getString("presentationTime"), it.bool("grouped"), it.getString("groupNote"), it.getString("note"), it.bool("done"), it.bool("important")) }
        val keywords = objects("keywords").map { AnnouncementKeyword(it.getString("id"), it.getString("text"), it.bool("enabled")) }
        listOf(courses.map { it.id }, meetings.map { it.id }, items.map { it.id }, keywords.map { it.id }).forEach { require(it.distinct().size == it.size) { "重複識別碼" } }
        require(base.entries.size + courses.size + meetings.size + items.size + keywords.size <= 20000) { "備份資料過多" }
        courses.forEach { c -> validateCourse(c, meetings.filter { it.courseId == c.id }) }
        val ids = courses.map { it.id }.toSet()
        require(meetings.all { it.courseId in ids } && items.all { it.courseId == null || it.courseId in ids }) { "關聯課程不存在" }
        items.forEach(::validateItem)
        require(keywords.all { it.id.isNotBlank() && it.id.length <= 100 && it.text.isNotBlank() && it.text.length <= 100 }) { "關鍵字無效" }
        val o = root.getJSONObject("options")
        val quick = o.getJSONArray("quick").let { a -> (0 until a.length()).map { a.getString(it) } }
        val options = AppOptions(quick, o.bool("showCourses"), o.bool("showTasks"), o.getInt("intervalHours"), o.bool("wifiOnly"), o.bool("notify")).also { it.validate() }
        return ArchiveV4(base.entries, courses, meetings, items, base.subscriptions, keywords, options)
    }
    suspend fun snapshot(context: Context): ArchiveV4 {
        val db = JournalDb.get(context)
        return db.withTransaction { ArchiveV4(db.automation().entries(), db.academic().allCourses(), db.academic().allMeetings(), db.academic().allItems(), db.automation().subscriptions(), db.automation().keywords(), AppOptions.read(context)) }
    }
    suspend fun restore(context: Context, a: ArchiveV4) = AnnouncementUpdates.mutex.withLock {
        val db = JournalDb.get(context)
        db.withTransaction {
            db.academic().clearItems(); db.academic().clearCourses()
            db.entries().replace(a.money)
            a.courses.forEach { db.academic().saveCourse(it) }
            db.academic().insertMeetings(a.meetings)
            a.items.forEach { db.academic().saveItem(it) }
            a.subscriptions?.let { db.subscriptions().replace(it) }
            db.automation().clearKeywords(); a.keywords.forEach { db.automation().save(it) }
            db.automation().clearNews(); db.automation().clearSeen()
        }
        context.getSharedPreferences("announcement-state", 0).edit().clear().apply()
        a.options?.persist(context)
    }
}

package tw.thorsheep.studentjournal

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

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
            listOf(e.note, e.room, e.teacher, e.category, e.courseId).all { it.length <= 2000 }
        ) { "文字欄位過長" }
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
                            .put("note", e.note).put("day", e.day).put("start", e.start).put("end", e.end)
                            .put("room", e.room).put("teacher", e.teacher).put("courseId", e.courseId)
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
                o.getString("id"), o.getString("type"), o.getString("title"), o.getString("date"),
                o.getLong("cents"), o.getString("category"), o.getString("note"), o.getInt("day"),
                o.getString("start"), o.getString("end"), o.getString("room"), o.getString("teacher"),
                o.getString("courseId"), o.getBoolean("done"),
                if (version == 1 && oldCategory in listOf("支出", "收入")) oldCategory else o.optString("direction", "支出")
            ).let {
                if (version == 1 && it.type == "money") it.copy(
                    category = it.note.takeIf { note -> note in AnnouncementCategories.values } ?: "日用品",
                    note = ""
                ) else it
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

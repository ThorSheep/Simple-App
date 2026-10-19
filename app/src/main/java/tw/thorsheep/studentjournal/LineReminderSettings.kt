package tw.thorsheep.studentjournal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

const val LINE_REMINDER_ENTITY = "lineReminderSettings"
const val LINE_REMINDER_ID = "personal"

data class LineReminderSettings(
    val enabled: Boolean = false,
    val dailySummary: Boolean = true,
    val dailyTime: String = "07:00",
    val includeCourses: Boolean = true,
    val includeToday: Boolean = true,
    val includeTomorrow: Boolean = true,
    val dueTime: String = "09:00",
    val dueDays: List<Int> = listOf(3, 1, 0),
    val aiProvider: String = AI_PROVIDER_OLLAMA
) {
    fun validate() {
        require(validReminderTime(dailyTime) && validReminderTime(dueTime)) { "提醒時間無效" }
        require(dueDays.distinct().size == dueDays.size && dueDays.all { it in reminderDays }) { "到期提醒天數無效" }
        require(aiProvider in aiProviders) { "AI 回覆模式無效" }
    }

    fun persist(context: Context) {
        validate()
        context.getSharedPreferences("line-reminders", 0).edit()
            .putBoolean("enabled", enabled).putBoolean("dailySummary", dailySummary).putString("dailyTime", dailyTime)
            .putBoolean("includeCourses", includeCourses).putBoolean("includeToday", includeToday).putBoolean("includeTomorrow", includeTomorrow)
            .putString("dueTime", dueTime).putString("dueDays", dueDays.joinToString(",")).putString("aiProvider", aiProvider).apply()
    }

    fun payload(): String = JSONObject().put("enabled", enabled).put("dailySummary", dailySummary).put("dailyTime", dailyTime)
        .put("includeCourses", includeCourses).put("includeToday", includeToday).put("includeTomorrow", includeTomorrow)
        .put("dueTime", dueTime).put("dueDays", JSONArray(dueDays)).put("aiProvider", aiProvider).toString()

    companion object {
        fun read(context: Context): LineReminderSettings {
            val p = context.getSharedPreferences("line-reminders", 0)
            val days = p.getString("dueDays", "3,1,0")!!.split(',').mapNotNull { it.toIntOrNull() }.distinct().filter { it in reminderDays }
            return LineReminderSettings(p.getBoolean("enabled", false), p.getBoolean("dailySummary", true), p.getString("dailyTime", "07:00")!!,
                p.getBoolean("includeCourses", true), p.getBoolean("includeToday", true), p.getBoolean("includeTomorrow", true),
                p.getString("dueTime", "09:00")!!, days.ifEmpty { listOf(3, 1, 0) },
                p.getString("aiProvider", AI_PROVIDER_OLLAMA)!!.takeIf { it in aiProviders } ?: AI_PROVIDER_OLLAMA).also { it.validate() }
        }

        fun fromPayload(raw: String): LineReminderSettings = JSONObject(raw).let { json ->
            val days = json.getJSONArray("dueDays").let { values -> (0 until values.length()).map { values.getInt(it) } }
            LineReminderSettings(json.getBoolean("enabled"), json.getBoolean("dailySummary"), json.getString("dailyTime"),
                json.getBoolean("includeCourses"), json.getBoolean("includeToday"), json.getBoolean("includeTomorrow"),
                json.getString("dueTime"), days, json.optString("aiProvider", AI_PROVIDER_OLLAMA).takeIf { it in aiProviders } ?: AI_PROVIDER_OLLAMA).also { it.validate() }
        }
    }
}

val reminderDays = listOf(7, 3, 1, 0)
const val AI_PROVIDER_OLLAMA = "ollama"
const val AI_PROVIDER_OPENAI = "openai"
val aiProviders = setOf(AI_PROVIDER_OLLAMA, AI_PROVIDER_OPENAI)

fun validReminderTime(value: String): Boolean = runCatching { LocalTime.parse(value).toString() == value }.getOrDefault(false)

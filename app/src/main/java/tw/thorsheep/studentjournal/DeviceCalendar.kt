package tw.thorsheep.studentjournal

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.*

data class DeviceCalendar(val id: Long, val name: String, val account: String, val color: Int)
data class AgendaEvent(val key: String, val title: String, val date: LocalDate, val time: String,
    val detail: String, val source: String, val color: Int = 0)

object CalendarReader {
    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    fun calendars(context: Context): List<DeviceCalendar> {
        if (!allowed(context)) return emptyList()
        val result = mutableListOf<DeviceCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf("_id", "calendar_displayName", "account_name", "calendar_color"), null, null, "calendar_displayName")?.use { c ->
            while (c.moveToNext()) result += DeviceCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))
        }
        return result
    }
    fun events(context: Context, ids: Set<String>, from: LocalDate, until: LocalDate): List<AgendaEvent> {
        if (!allowed(context) || ids.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        // Expand query boundaries for all-day events, whose dates are expressed in UTC.
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        ContentUris.appendId(builder, until.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli())
        val result = mutableListOf<AgendaEvent>()
        context.contentResolver.query(builder.build(), arrayOf("event_id", "title", "begin", "end", "allDay", "eventLocation", "calendar_id", "displayColor"),
            "calendar_id IN (${ids.joinToString(",") { "?" }}) AND deleted = 0 AND (eventStatus IS NULL OR eventStatus != 2)", ids.toTypedArray(), "begin ASC")?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(4) == 1
                val eventZone = if (allDay) ZoneOffset.UTC else zone
                val begin = Instant.ofEpochMilli(c.getLong(2)).atZone(eventZone)
                val end = Instant.ofEpochMilli(maxOf(c.getLong(2), c.getLong(3) - 1)).atZone(eventZone)
                var date = maxOf(begin.toLocalDate(), from)
                while (date <= minOf(end.toLocalDate(), until)) {
                    result += AgendaEvent("calendar:${c.getLong(0)}:${c.getLong(2)}:$date", c.getString(1) ?: "未命名事件", date,
                        if (allDay) "" else if (date == begin.toLocalDate()) begin.toLocalTime().toString().take(5) else "00:00",
                        (if (allDay) "全天" else "${begin.toLocalTime().toString().take(5)}–${Instant.ofEpochMilli(c.getLong(3)).atZone(eventZone).toLocalTime().toString().take(5)}") + " · ${c.getString(5).orEmpty()}", "手機行事曆", c.getInt(7))
                    date = date.plusDays(1)
                }
            }
        }
        return result
    }
}

fun localAgenda(courses: List<Course>, meetings: List<CourseMeeting>, items: List<AcademicItem>, from: LocalDate, until: LocalDate): List<AgendaEvent> {
    val result = mutableListOf<AgendaEvent>()
    val active = courses.filter { !it.archived }.associateBy { it.id }
    var date = from
    while (date <= until) {
        meetings.filter { it.day == date.dayOfWeek.value && it.courseId in active }.forEach { m ->
            val c = active.getValue(m.courseId)
            result += AgendaEvent("meeting:${m.id}:$date", c.title, date, m.start, "${m.start}–${m.end} · ${m.room.ifBlank { c.room }}", "課程", c.color)
        }
        date = date.plusDays(1)
    }
    items.filter { !it.done }.forEach { i ->
        fun add(value: String, time: String, label: String) {
            if (value.isBlank()) return
            val d = LocalDate.parse(value)
            if (d in from..until) result += AgendaEvent("item:${i.id}:$label", "${if (i.important) "★ " else ""}${i.title} · $label", d, time,
                courses.find { it.id == i.courseId }?.title.orEmpty(), i.kind)
        }
        add(i.date, i.time, if (i.kind == "考試") "考試" else "截止／繳交")
        if (i.kind == "報告") add(i.presentationDate, i.presentationTime, "報告")
    }
    return result.sortedWith(compareBy<AgendaEvent> { it.date }.thenBy { it.time })
}

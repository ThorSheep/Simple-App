package tw.thorsheep.studentjournal

import androidx.room.withTransaction
import org.json.JSONObject

private const val COURSE_ENTITY = "course"
private const val MEETING_ENTITY = "courseMeeting"
private const val ITEM_ENTITY = "academicItem"

object SyncAcademicPayload {
    fun course(v: Course) = JSONObject().put("id", v.id).put("title", v.title).put("teacher", v.teacher).put("room", v.room).put("color", v.color).put("archived", v.archived).toString()
    fun meeting(v: CourseMeeting) = JSONObject().put("id", v.id).put("courseId", v.courseId).put("day", v.day).put("start", v.start).put("end", v.end).put("room", v.room).toString()
    fun item(v: AcademicItem) = JSONObject().put("id", v.id).put("title", v.title).put("courseId", v.courseId ?: JSONObject.NULL).put("kind", v.kind).put("date", v.date).put("time", v.time).put("presentationDate", v.presentationDate).put("presentationTime", v.presentationTime).put("grouped", v.grouped).put("groupNote", v.groupNote).put("note", v.note).put("done", v.done).put("important", v.important).toString()
    fun decodeCourse(raw: String) = JSONObject(raw).let { Course(it.getString("id"), it.getString("title"), it.getString("teacher"), it.getString("room"), it.getInt("color"), it.getBoolean("archived")) }
    fun decodeMeeting(raw: String) = JSONObject(raw).let { CourseMeeting(it.getString("id"), it.getString("courseId"), it.getInt("day"), it.getString("start"), it.getString("end"), it.getString("room")) }
    fun decodeItem(raw: String) = JSONObject(raw).let { AcademicItem(it.getString("id"), it.getString("title"), if (it.isNull("courseId")) null else it.getString("courseId"), it.getString("kind"), it.getString("date"), it.getString("time"), it.getString("presentationDate"), it.getString("presentationTime"), it.getBoolean("grouped"), it.getString("groupNote"), it.getString("note"), it.getBoolean("done"), it.getBoolean("important")) }
}

class SyncAcademicRepository(private val db: JournalDb, private val deviceId: String) {
    suspend fun bootstrap() = db.withTransaction {
        db.academic().allCourses().forEach { bootstrapRecord(COURSE_ENTITY, it.id, SyncAcademicPayload.course(it)) }
        db.academic().allMeetings().forEach { bootstrapRecord(MEETING_ENTITY, it.id, SyncAcademicPayload.meeting(it)) }
        db.academic().allItems().forEach { bootstrapRecord(ITEM_ENTITY, it.id, SyncAcademicPayload.item(it)) }
    }
    suspend fun saveCourse(course: Course, meetings: List<CourseMeeting>) = db.withTransaction {
        val old = db.academic().allMeetings().filter { it.courseId == course.id }
        db.academic().save(course, meetings)
        writeRecord(COURSE_ENTITY, course.id, SyncAcademicPayload.course(course))
        meetings.forEach { writeRecord(MEETING_ENTITY, it.id, SyncAcademicPayload.meeting(it)) }
        old.filter { prior -> meetings.none { it.id == prior.id } }.forEach { deleteRecord(MEETING_ENTITY, it.id) }
    }
    suspend fun saveItem(item: AcademicItem) = db.withTransaction { validateItem(item); db.academic().saveItem(item); writeRecord(ITEM_ENTITY, item.id, SyncAcademicPayload.item(item)) }
    suspend fun deleteItem(id: String) = db.withTransaction { db.academic().deleteItem(id); deleteRecord(ITEM_ENTITY, id) }
    suspend fun deleteCourse(id: String) = db.withTransaction {
        val meetings = db.academic().allMeetings().filter { it.courseId == id }; val items = db.academic().allItems().filter { it.courseId == id }
        db.academic().deleteCourse(id); deleteRecord(COURSE_ENTITY, id); meetings.forEach { deleteRecord(MEETING_ENTITY, it.id) }
        db.academic().allItems().filter { it.id in items.map { row -> row.id }.toSet() }.forEach { writeRecord(ITEM_ENTITY, it.id, SyncAcademicPayload.item(it)) }
    }
    suspend fun apply(change: RemoteSyncChange): Boolean = db.withTransaction {
        val op = change.operation; if (op.entityType !in setOf(COURSE_ENTITY, MEETING_ENTITY, ITEM_ENTITY)) return@withTransaction false
        val old = db.sync().metadata(op.entityType, op.entityId); if (!SyncClock.isNewer(op.revision, old?.revision)) return@withTransaction false
        when (op.entityType) {
            COURSE_ENTITY -> if (op.deleted) db.academic().deleteCourse(op.entityId) else db.academic().saveCourse(SyncAcademicPayload.decodeCourse(op.payload))
            MEETING_ENTITY -> if (op.deleted) db.academic().deleteMeeting(op.entityId) else db.academic().insertMeetings(listOf(SyncAcademicPayload.decodeMeeting(op.payload)))
            else -> if (op.deleted) db.academic().deleteItem(op.entityId) else db.academic().saveItem(SyncAcademicPayload.decodeItem(op.payload))
        }
        db.sync().saveMetadata(SyncMetadata(op.entityType, op.entityId, op.revision, op.deviceId, op.deleted)); true
    }
    private suspend fun bootstrapRecord(type: String, id: String, payload: String) { if (db.sync().metadata(type, id) == null) record(type, id, false, payload) }
    private suspend fun writeRecord(type: String, id: String, payload: String) = record(type, id, false, payload)
    private suspend fun deleteRecord(type: String, id: String) = record(type, id, true, "{}")
    private suspend fun record(type: String, id: String, deleted: Boolean, payload: String) { val revision = SyncClock.next(db.sync().metadata(type, id)?.revision, deviceId); db.sync().saveMetadata(SyncMetadata(type, id, revision, deviceId, deleted)); db.sync().enqueue(SyncOutbox(entityType = type, entityId = id, revision = revision, deviceId = deviceId, deleted = deleted, payload = payload)) }
}

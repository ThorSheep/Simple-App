package tw.thorsheep.studentjournal

import androidx.room.withTransaction
import org.json.JSONObject

private const val MONEY_ENTITY = "money"

object SyncMoneyPayload {
    fun encode(entry: Entry): String {
        require(entry.type == MONEY_ENTITY) { "僅能同步收支資料" }
        return JSONObject().put("id", entry.id).put("type", entry.type).put("title", entry.title).put("date", entry.date)
            .put("cents", entry.cents).put("category", entry.category).put("note", entry.note).put("day", entry.day)
            .put("start", entry.start).put("end", entry.end).put("room", entry.room).put("teacher", entry.teacher)
            .put("courseId", entry.courseId).put("done", entry.done).put("direction", entry.direction).toString()
    }

    fun decode(raw: String): Entry {
        val value = JSONObject(raw)
        val entry = Entry(value.getString("id"), value.getString("type"), value.getString("title"), value.getString("date"),
            value.getLong("cents"), value.getString("category"), value.getString("note"), value.getInt("day"), value.getString("start"),
            value.getString("end"), value.getString("room"), value.getString("teacher"), value.getString("courseId"), value.getBoolean("done"), value.getString("direction"))
        require(entry.type == MONEY_ENTITY) { "同步收支類型無效" }
        Backup.validate(entry)
        return entry
    }
}

class SyncMoneyRepository(private val db: JournalDb, private val deviceId: String) {
    suspend fun save(entry: Entry) = db.withTransaction {
        require(entry.type == MONEY_ENTITY) { "僅能同步收支資料" }
        Backup.validate(entry)
        val previous = db.sync().metadata(MONEY_ENTITY, entry.id)
        val revision = SyncClock.next(previous?.revision, deviceId)
        db.entries().save(entry)
        record(entry.id, revision, false, SyncMoneyPayload.encode(entry))
    }

    suspend fun delete(id: String) = db.withTransaction {
        val previous = db.sync().metadata(MONEY_ENTITY, id)
        val revision = SyncClock.next(previous?.revision, deviceId)
        db.entries().deleteId(id)
        record(id, revision, true, "{}")
    }

    suspend fun apply(change: RemoteSyncChange): Boolean = db.withTransaction {
        val operation = change.operation
        if (operation.entityType != MONEY_ENTITY) return@withTransaction false
        val current = db.sync().metadata(MONEY_ENTITY, operation.entityId)
        if (!SyncClock.isNewer(operation.revision, current?.revision)) return@withTransaction false
        if (operation.deleted) db.entries().deleteId(operation.entityId)
        else db.entries().save(SyncMoneyPayload.decode(operation.payload))
        db.sync().saveMetadata(SyncMetadata(MONEY_ENTITY, operation.entityId, operation.revision, operation.deviceId, operation.deleted))
        true
    }

    private suspend fun record(id: String, revision: String, deleted: Boolean, payload: String) {
        db.sync().saveMetadata(SyncMetadata(MONEY_ENTITY, id, revision, deviceId, deleted))
        db.sync().enqueue(SyncOutbox(entityType = MONEY_ENTITY, entityId = id, revision = revision, deviceId = deviceId, deleted = deleted, payload = payload))
    }
}

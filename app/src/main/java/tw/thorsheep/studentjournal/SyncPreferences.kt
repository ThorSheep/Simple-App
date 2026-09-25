package tw.thorsheep.studentjournal

import androidx.room.withTransaction
import org.json.JSONObject

private const val SUBSCRIPTION_ENTITY = "subscription"
private const val KEYWORD_ENTITY = "announcementKeyword"

class SyncPreferencesRepository(private val db: JournalDb, private val deviceId: String) {
    suspend fun bootstrap() = db.withTransaction {
        db.automation().subscriptions().forEach { bootstrap(SUBSCRIPTION_ENTITY, it.sourceId, JSONObject().put("sourceId", it.sourceId).toString()) }
        db.automation().keywords().forEach { bootstrap(KEYWORD_ENTITY, it.id, JSONObject().put("id", it.id).put("text", it.text).put("enabled", it.enabled).toString()) }
    }
    suspend fun save(subscription: Subscription) = db.withTransaction { db.subscriptions().save(subscription); write(SUBSCRIPTION_ENTITY, subscription.sourceId, JSONObject().put("sourceId", subscription.sourceId).toString()) }
    suspend fun deleteSubscription(id: String) = db.withTransaction { db.subscriptions().delete(id); delete(SUBSCRIPTION_ENTITY, id) }
    suspend fun save(keyword: AnnouncementKeyword) = db.withTransaction { require(keyword.text.isNotBlank() && keyword.text.length <= 100); db.automation().save(keyword); write(KEYWORD_ENTITY, keyword.id, JSONObject().put("id", keyword.id).put("text", keyword.text).put("enabled", keyword.enabled).toString()) }
    suspend fun deleteKeyword(id: String) = db.withTransaction { db.automation().delete(id); delete(KEYWORD_ENTITY, id) }
    suspend fun apply(change: RemoteSyncChange): Boolean = db.withTransaction {
        val op = change.operation; if (op.entityType !in setOf(SUBSCRIPTION_ENTITY, KEYWORD_ENTITY)) return@withTransaction false
        if (!SyncClock.isNewer(op.revision, db.sync().metadata(op.entityType, op.entityId)?.revision)) return@withTransaction false
        if (op.entityType == SUBSCRIPTION_ENTITY) { if (op.deleted) db.subscriptions().delete(op.entityId) else db.subscriptions().save(Subscription(JSONObject(op.payload).getString("sourceId"))) }
        else { if (op.deleted) db.automation().delete(op.entityId) else JSONObject(op.payload).let { db.automation().save(AnnouncementKeyword(it.getString("id"), it.getString("text"), it.getBoolean("enabled"))) } }
        db.sync().saveMetadata(SyncMetadata(op.entityType, op.entityId, op.revision, op.deviceId, op.deleted)); true
    }
    private suspend fun bootstrap(type: String, id: String, payload: String) { if (db.sync().metadata(type, id) == null) write(type, id, payload) }
    private suspend fun delete(type: String, id: String) { val revision = SyncClock.next(db.sync().metadata(type, id)?.revision, deviceId); db.sync().saveMetadata(SyncMetadata(type, id, revision, deviceId, true)); db.sync().enqueue(SyncOutbox(entityType = type, entityId = id, revision = revision, deviceId = deviceId, deleted = true, payload = "{}")) }
    private suspend fun write(type: String, id: String, payload: String) { val revision = SyncClock.next(db.sync().metadata(type, id)?.revision, deviceId); db.sync().saveMetadata(SyncMetadata(type, id, revision, deviceId)); db.sync().enqueue(SyncOutbox(entityType = type, entityId = id, revision = revision, deviceId = deviceId, deleted = false, payload = payload)) }
}

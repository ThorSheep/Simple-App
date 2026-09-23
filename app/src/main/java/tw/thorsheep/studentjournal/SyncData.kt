package tw.thorsheep.studentjournal

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.Locale
import java.util.UUID

/** Version stamps use a fixed-width hybrid logical clock. */
object SyncClock {
    data class Value(val wallTimeMillis: Long, val logical: Int, val deviceId: String) : Comparable<Value> {
        override fun compareTo(other: Value): Int = compareValuesBy(this, other, Value::wallTimeMillis, Value::logical, Value::deviceId)
        fun encode(): String = String.format(Locale.ROOT, "%013d-%05d-%s", wallTimeMillis, logical, deviceId)
    }

    fun parse(encoded: String): Value {
        require(encoded.length >= 21 && encoded[13] == '-' && encoded[19] == '-') { "同步版本格式無效" }
        val wallTime = encoded.substring(0, 13).toLong()
        val logical = encoded.substring(14, 19).toInt()
        val deviceId = encoded.substring(20)
        require(deviceId.isNotBlank()) { "同步裝置識別碼無效" }
        return Value(wallTime, logical, deviceId)
    }

    fun next(previous: String?, deviceId: String, nowMillis: Long = System.currentTimeMillis()): String {
        require(deviceId.isNotBlank() && deviceId.length <= 100) { "同步裝置識別碼無效" }
        val previousValue = previous?.let(::parse)
        val wallTime = maxOf(nowMillis, previousValue?.wallTimeMillis ?: 0)
        val logical = if (previousValue?.wallTimeMillis == wallTime) previousValue.logical + 1 else 0
        require(logical <= 99_999) { "同步版本邏輯時鐘溢位" }
        return Value(wallTime, logical, deviceId).encode()
    }

    fun isNewer(candidate: String, current: String?): Boolean = current == null || parse(candidate) > parse(current)
}

@Entity(tableName = "sync_metadata", primaryKeys = ["entityType", "entityId"])
data class SyncMetadata(val entityType: String, val entityId: String, val revision: String, val deviceId: String, val deleted: Boolean = false)

@Entity(tableName = "sync_outbox")
data class SyncOutbox(
    @PrimaryKey val operationId: String = UUID.randomUUID().toString(),
    val entityType: String, val entityId: String, val revision: String, val deviceId: String,
    val deleted: Boolean, val payload: String, val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: String = SINGLETON_ID,
    val cursor: Long = 0, val lastRevision: String = "", val lastSuccessAt: Long = 0, val lastError: String = ""
) { companion object { const val SINGLETON_ID = "singleton" } }

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_metadata WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun metadata(entityType: String, entityId: String): SyncMetadata?
    @Upsert suspend fun saveMetadata(metadata: SyncMetadata)
    @Query("SELECT * FROM sync_outbox ORDER BY createdAt, operationId LIMIT :limit")
    suspend fun pending(limit: Int = 1000): List<SyncOutbox>
    @Upsert suspend fun enqueue(operation: SyncOutbox)
    @Query("DELETE FROM sync_outbox WHERE operationId IN (:operationIds)")
    suspend fun acknowledge(operationIds: List<String>)
    @Query("SELECT * FROM sync_state WHERE id = :id")
    suspend fun state(id: String = SyncState.SINGLETON_ID): SyncState?
    @Upsert suspend fun saveState(state: SyncState)
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE sync_metadata (entityType TEXT NOT NULL, entityId TEXT NOT NULL, revision TEXT NOT NULL, deviceId TEXT NOT NULL, deleted INTEGER NOT NULL, PRIMARY KEY(entityType, entityId))")
        db.execSQL("CREATE TABLE sync_outbox (operationId TEXT NOT NULL PRIMARY KEY, entityType TEXT NOT NULL, entityId TEXT NOT NULL, revision TEXT NOT NULL, deviceId TEXT NOT NULL, deleted INTEGER NOT NULL, payload TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE sync_state (id TEXT NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL, lastRevision TEXT NOT NULL, lastSuccessAt INTEGER NOT NULL, lastError TEXT NOT NULL)")
        db.execSQL("INSERT INTO sync_state(id, cursor, lastRevision, lastSuccessAt, lastError) VALUES ('singleton', 0, '', 0, '')")
    }
}

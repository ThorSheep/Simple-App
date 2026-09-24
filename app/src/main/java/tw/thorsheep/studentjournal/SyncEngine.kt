package tw.thorsheep.studentjournal

import androidx.room.withTransaction

class SyncEngine(
    private val db: JournalDb,
    private val deviceId: String,
    private val send: suspend (SyncConnection, Long, List<SyncOutbox>) -> SyncResult = SyncHttpClient::sync
) {
    suspend fun synchronize(connection: SyncConnection): SyncResult {
        require(connection.deviceId == deviceId) { "同步裝置不符" }
        val state = db.sync().state() ?: SyncState()
        val pending = db.sync().pending()
        val result = send(connection, state.cursor, pending)
        db.withTransaction {
            if (result.acceptedOperationIds.isNotEmpty()) db.sync().acknowledge(result.acceptedOperationIds)
            val money = SyncMoneyRepository(db, deviceId); val academic = SyncAcademicRepository(db, deviceId); val preferences = SyncPreferencesRepository(db, deviceId)
            result.changes.sortedWith(compareBy<RemoteSyncChange> { when (it.operation.entityType) { "course" -> 0; "courseMeeting" -> 1; "academicItem" -> 2; else -> 3 } }.thenBy { it.cursor }).forEach { change ->
                if (!academic.apply(change) && !money.apply(change)) preferences.apply(change)
            }
            db.sync().saveState(state.copy(cursor = maxOf(state.cursor, result.cursor), lastSuccessAt = System.currentTimeMillis(), lastError = ""))
        }
        return result
    }

    suspend fun recordFailure(message: String) = db.withTransaction {
        val state = db.sync().state() ?: SyncState()
        db.sync().saveState(state.copy(lastError = message.take(500)))
    }
}

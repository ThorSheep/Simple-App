package tw.thorsheep.studentjournal

import androidx.room.withTransaction

class SyncEngine(
    private val db: JournalDb,
    private val deviceId: String,
    private val send: (SyncConnection, Long, List<SyncOutbox>) -> SyncResult = SyncHttpClient::sync
) {
    suspend fun synchronize(connection: SyncConnection): SyncResult {
        require(connection.deviceId == deviceId) { "同步裝置不符" }
        val state = db.sync().state() ?: SyncState()
        val pending = db.sync().pending()
        val result = send(connection, state.cursor, pending)
        db.withTransaction {
            if (result.acceptedOperationIds.isNotEmpty()) db.sync().acknowledge(result.acceptedOperationIds)
            val money = SyncMoneyRepository(db, deviceId)
            result.changes.sortedBy { it.cursor }.forEach { change -> money.apply(change) }
            db.sync().saveState(state.copy(cursor = maxOf(state.cursor, result.cursor), lastSuccessAt = System.currentTimeMillis(), lastError = ""))
        }
        return result
    }

    suspend fun recordFailure(message: String) = db.withTransaction {
        val state = db.sync().state() ?: SyncState()
        db.sync().saveState(state.copy(lastError = message.take(500)))
    }
}

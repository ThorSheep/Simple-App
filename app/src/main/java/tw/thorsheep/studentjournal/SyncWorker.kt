package tw.thorsheep.studentjournal

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val connection = SyncSettings.connection(applicationContext) ?: return Result.success()
        return try {
            SyncEngine(JournalDb.get(applicationContext), connection.deviceId).synchronize(connection)
            Result.success()
        } catch (e: Exception) {
            SyncEngine(JournalDb.get(applicationContext), connection.deviceId).recordFailure(e.message ?: "同步失敗")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("self-hosted-sync", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun cancel(context: Context) { WorkManager.getInstance(context).cancelUniqueWork("self-hosted-sync") }
    }
}

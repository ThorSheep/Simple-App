package tw.thorsheep.studentjournal

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

@Entity(tableName = "announcement_keywords")
data class AnnouncementKeyword(@PrimaryKey val id: String = UUID.randomUUID().toString(), val text: String, val enabled: Boolean = true)
@Entity(tableName = "seen_announcements")
data class SeenAnnouncement(@PrimaryKey val url: String)

@Dao
interface AutomationDao {
    @Query("SELECT * FROM announcement_keywords ORDER BY text") fun observeKeywords(): Flow<List<AnnouncementKeyword>>
    @Query("SELECT * FROM announcement_keywords") suspend fun keywords(): List<AnnouncementKeyword>
    @Upsert suspend fun save(keyword: AnnouncementKeyword)
    @Query("DELETE FROM announcement_keywords WHERE id = :id") suspend fun delete(id: String)
    @Query("DELETE FROM announcement_keywords") suspend fun clearKeywords()
    @Query("SELECT * FROM subscriptions") suspend fun subscriptions(): List<Subscription>
    @Query("SELECT url FROM seen_announcements") suspend fun seen(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun remember(items: List<SeenAnnouncement>)
    @Query("DELETE FROM seen_announcements") suspend fun clearSeen()
    @Query("DELETE FROM announcements") suspend fun clearNews()
    @Query("SELECT * FROM entries") suspend fun entries(): List<Entry>
}

fun keywordMatches(a: Announcement, source: AnnouncementSource?, keywords: List<AnnouncementKeyword>): List<String> {
    val text = listOf(a.title, a.category, source?.name.orEmpty()).joinToString(" ")
    return keywords.filter { it.enabled && it.text.isNotBlank() && text.contains(it.text, ignoreCase = true) }.map { it.text }
}

data class AppOptions(val quick: List<String> = listOf("money", "course", "task", "announcements"),
    val showCourses: Boolean = true, val showTasks: Boolean = true, val intervalHours: Int = 0,
    val wifiOnly: Boolean = false, val notify: Boolean = false, val homeSections: List<String> = listOf("money", "course", "task", "announcements"),
    val homePosition: Int = 2, val swipeNavigation: Boolean = false, val theme: String = "system",
    val checkAppUpdates: Boolean = true) {
    fun validate() {
        require(quick.size <= 4 && quick.distinct().size == quick.size && quick.all { it in appPages }) { "常用功能設定無效" }
        require(homeSections.distinct().size == homeSections.size && homeSections.all { it in homeSectionPages }) { "首頁內容設定無效" }
        require(homePosition in 0..quick.size) { "首頁位置無效" }
        require(intervalHours in listOf(0, 6, 12, 24)) { "更新間隔無效" }
        require(theme in themes) { "主題設定無效" }
    }
    fun persist(context: Context) {
        validate()
        context.getSharedPreferences("settings", 0).edit().putString("quick", quick.joinToString(","))
            .putBoolean("showCourses", showCourses).putBoolean("showTasks", showTasks)
            .putInt("intervalHours", intervalHours).putBoolean("wifiOnly", wifiOnly).putBoolean("notify", notify)
            .putString("homeSections", homeSections.joinToString(",")).putInt("homePosition", homePosition)
            .putBoolean("swipeNavigation", swipeNavigation).putString("theme", theme).putBoolean("checkAppUpdates", checkAppUpdates).apply()
        schedule(context, this)
    }
    companion object {
        fun read(context: Context): AppOptions {
            val p = context.getSharedPreferences("settings", 0)
            val quick = p.getString("quick", "money,course,task,announcements")!!.split(',')
                .map { if (it == "courses") "course" else it }
                .filter { it in appPages }.distinct().take(4)
            val sections = p.getString("homeSections", "money,course,task,announcements")!!.split(',')
                .filter { it in homeSectionPages }.distinct().ifEmpty { homeSectionPages }
            val hours = p.getInt("intervalHours", p.getLong("announcement_interval", 0).toInt()).takeIf { it in listOf(0, 6, 12, 24) } ?: 0
            return AppOptions(quick, p.getBoolean("showCourses", true), p.getBoolean("showTasks", true), hours,
                p.getBoolean("wifiOnly", false), p.getBoolean("notify", p.getBoolean("keyword_notifications", false)), sections,
                p.getInt("homePosition", 2).coerceIn(0, quick.size), p.getBoolean("swipeNavigation", false),
                p.getString("theme", "system")!!.takeIf { it in themes } ?: "system", p.getBoolean("checkAppUpdates", true))
        }
        fun schedule(context: Context, options: AppOptions = read(context)) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork("announcement-auto-update")
            if (options.intervalHours == 0) manager.cancelUniqueWork("announcement-update")
            else manager.enqueueUniquePeriodicWork("announcement-update", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AnnouncementUpdateWorker>(options.intervalHours.toLong(), TimeUnit.HOURS)
                    .setInitialDelay(options.intervalHours.toLong(), TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(if (options.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()).build())
        }
    }
}

val appPages = listOf("money", "course", "task", "agenda", "announcements")
val homeSectionPages = listOf("money", "course", "task", "agenda", "announcements")
val themes = listOf("system", "light", "dark")

object AnnouncementUpdates {
    val mutex = Mutex()
    suspend fun update(context: Context, sources: List<AnnouncementSource>, fetch: suspend (AnnouncementSource) -> List<Announcement> = AnnouncementFetcher::fetch): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val db = JournalDb.get(context)
            val state = context.getSharedPreferences("announcement-state", 0)
            val subscribed = db.automation().subscriptions().map { it.sourceId }.toSet()
            val keywords = db.automation().keywords()
            var count = 0
            val errors = mutableListOf<String>()
            for (source in sources) {
                try {
                    val rows = fetch(source).distinctBy { it.url }
                    val seen = db.automation().seen().toHashSet()
                    val initialized = state.getBoolean(source.id, false)
                    val fresh = rows.filter { it.url !in seen && (it.sourceId in subscribed || "${it.sourceId}#${it.category}" in subscribed) }
                    db.withTransaction {
                        db.announcements().insertAll(rows)
                        db.automation().remember(rows.map { SeenAnnouncement(it.url) })
                    }
                    state.edit().putBoolean(source.id, true).apply()
                    if (initialized && AppOptions.read(context).notify) fresh.filter { keywordMatches(it, source, keywords).isNotEmpty() }.forEach { notify(context, it) }
                    count += rows.size
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "${source.name}：${e.message ?: "更新失敗"}" }
            }
            db.announcements().trim(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(120))
            val message = "讀取 $count 筆公告" + if (errors.isEmpty()) "" else "；${errors.size} 個來源失敗：${errors.joinToString("；") }"
            state.edit().putLong("lastAttempt", System.currentTimeMillis()).putString("lastResult", message).apply()
            message
        }
    }
    private fun notify(context: Context, item: Announcement) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("keywords", "關鍵字公告", NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW, Uri.parse(item.url)), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, "keywords").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("關鍵字命中：${item.title}").setContentText(item.category.ifBlank { "點擊查看公告原文" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title)).setContentIntent(intent).setAutoCancel(true).build()
        manager.notify(item.url, 1, notification)
    }
}

class AnnouncementUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (AppOptions.read(applicationContext).intervalHours == 0) return Result.success()
        return try {
            val ids = JournalDb.get(applicationContext).automation().subscriptions().map { it.sourceId.substringBefore('#') }.toSet()
            AnnouncementUpdates.update(applicationContext, AnnouncementCatalog.sources(applicationContext).filter { it.id in ids })
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}

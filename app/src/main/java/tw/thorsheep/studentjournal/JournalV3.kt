package tw.thorsheep.studentjournal

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*

private val pagesV3 = linkedMapOf("home" to "首頁", "money" to "記帳", "course" to "課程", "task" to "待辦", "agenda" to "行事曆／行程", "announcements" to "公告", "settings" to "設定")
private fun pageIcon(page: String) = when (page) { "money" -> Icons.Outlined.AccountBalanceWallet; "course" -> Icons.Outlined.School; "task" -> Icons.Outlined.CheckCircle; "agenda" -> Icons.Outlined.DateRange; "announcements" -> Icons.Outlined.Campaign; "settings" -> Icons.Outlined.Settings; else -> Icons.Outlined.Home }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun JournalV3(onThemeChanged: (String) -> Unit) {
    val context = LocalContext.current
    val db = remember { JournalDb.get(context) }
    val courses by db.academic().courses().collectAsStateWithLifecycle(emptyList())
    val meetings by db.academic().meetings().collectAsStateWithLifecycle(emptyList())
    val tasks by db.academic().items().collectAsStateWithLifecycle(emptyList())
    val money by db.entries().observe().collectAsStateWithLifecycle(emptyList())
    val subscriptions by db.subscriptions().observe().collectAsStateWithLifecycle(emptyList())
    val keywords by db.automation().observeKeywords().collectAsStateWithLifecycle(emptyList())
    val sources = remember { AnnouncementCatalog.sources(context) }
    val sourceIds = subscriptions.map { it.sourceId.substringBefore('#') }.distinct()
    val news by remember(sourceIds) { db.announcements().observe(sourceIds) }.collectAsStateWithLifecycle(emptyList())
    val selectedSubs = subscriptions.map { it.sourceId }
    val visibleNews = news.filter { it.sourceId in selectedSubs || "${it.sourceId}#${it.category}" in selectedSubs }
    var page by rememberSaveable { mutableStateOf("home") }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var options by remember { mutableStateOf(AppOptions.read(context)) }
    val snack = remember { SnackbarHostState() }; val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var courseEdit by remember { mutableStateOf<Course?>(null) }
    var itemEdit by remember { mutableStateOf<AcademicItem?>(null) }
    var moneyEdit by remember { mutableStateOf<Entry?>(null) }
    var deletion by remember { mutableStateOf<Pair<String, String>?>(null) }
    var archive by remember { mutableStateOf<ArchiveV4?>(null) }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var syncConnection by remember { mutableStateOf(SyncSettings.connection(context)) }
    var syncInfo by remember { mutableStateOf("") }
    var settingsSection by rememberSaveable { mutableStateOf("interface") }
    fun message(text: String) { scope.launch { snack.showSnackbar(text) } }
    fun work(action: suspend () -> Unit) { if (!busy) scope.launch { busy = true; try { action() } catch (e: CancellationException) { throw e } catch (e: Exception) { message(e.message ?: "操作失敗") } finally { busy = false } } }
    fun saveOptions(value: AppOptions) { options = value; value.persist(context); onThemeChanged(value.theme) }
    val noticePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> saveOptions(options.copy(notify = allowed)); if (!allowed) message("通知未啟用，公告仍會醒目顯示") }
    LaunchedEffect(Unit) { AppOptions.schedule(context); if (options.checkAppUpdates) updateState = AppUpdates.latest(context) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) work {
        withContext(Dispatchers.IO) { val raw = BackupV4.encode(BackupV4.snapshot(context)); (context.contentResolver.openOutputStream(uri, "wt") ?: error("無法開啟備份檔")).bufferedWriter().use { it.write(raw) } }; message("備份已匯出")
    } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) work {
        archive = withContext(Dispatchers.IO) { (context.contentResolver.openInputStream(uri) ?: error("無法讀取備份檔")).use { input ->
            val buffer = java.io.ByteArrayOutputStream(); val bytes = ByteArray(8192)
            while (true) { val count = input.read(bytes); if (count < 0) break; require(buffer.size() + count <= Backup.MAX_BYTES) { "備份不得超過 5 MB" }; buffer.write(bytes, 0, count) }
            BackupV4.decode(buffer.toString("UTF-8"))
        } }
    } }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet {
        Text("Simple App 3.1.0", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        pagesV3.forEach { (id, title) -> NavigationDrawerItem(label = { Text(title) }, selected = page == id, onClick = { page = id; scope.launch { drawer.close() } }, icon = { Icon(pageIcon(id), null) }) }
    } }) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = { TopAppBar(title = { Text(pagesV3[page] ?: "首頁") }, navigationIcon = { IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "開啟選單") } }) },
            bottomBar = { if (page != "settings") NavigationBar {
                val quick = options.quick.filter { it in pagesV3 }.take(4)
                quick.toMutableList().also { it.add(options.homePosition.coerceIn(0, quick.size), "home") }.forEach { id -> NavigationBarItem(selected = page == id, onClick = { page = id }, icon = { Icon(pageIcon(id), null) }, label = { Text(pagesV3[id] ?: id) }) }
            } }, floatingActionButton = { if (page in listOf("course", "task", "money")) FloatingActionButton(onClick = {
                when (page) { "course" -> courseEdit = Course(title = ""); "task" -> itemEdit = AcademicItem(title = ""); "money" -> moneyEdit = Entry(type = "money", title = "", date = LocalDate.now().toString()) }
            }) { Icon(Icons.Outlined.Add, "新增") } }) { padding ->
            val swipePages = options.quick.filter { it in pagesV3 }.toMutableList().also { it.add(options.homePosition.coerceIn(0, options.quick.size), "home") }
            var drag by remember(page, swipePages, options.swipeNavigation) { mutableFloatStateOf(0f) }
            Box(Modifier.padding(padding).then(if (options.swipeNavigation && page in swipePages) Modifier.pointerInput(page, swipePages) { detectHorizontalDragGestures(onHorizontalDrag = { _, amount -> drag += amount }, onDragEnd = { val index = swipePages.indexOf(page); val next = when { drag > 110 && index > 0 -> swipePages[index - 1]; drag < -110 && index < swipePages.lastIndex -> swipePages[index + 1]; else -> null }; if (next != null) page = next; drag = 0f }, onDragCancel = { drag = 0f }) } else Modifier)) {
                when (page) {
                    "home" -> HomeV3(money, courses, meetings, tasks, visibleNews, keywords, sources, options) { page = it }
                    "money" -> MoneyScreen(money, month, { month = it }, { moneyEdit = it }, { deletion = "money" to it.id })
                    "course" -> CoursePage(courses, meetings, tasks, { courseEdit = it }, { deletion = "course" to it.id }, { itemEdit = AcademicItem(title = "", courseId = it) }, { itemEdit = it }, { item, done -> work { db.academic().saveItem(item.copy(done = done)) } }, { deletion = "task" to it.id })
                    "task" -> TaskPage(courses, tasks, { itemEdit = it }, { item, done -> work { db.academic().saveItem(item.copy(done = done)) } }, { deletion = "task" to it.id })
                    "agenda" -> AgendaPage(courses, meetings, tasks)
                    "announcements" -> NewsPage(sources, visibleNews, selectedSubs, keywords, options, { id, enabled -> work { if (enabled) db.subscriptions().save(Subscription(id)) else db.subscriptions().delete(id) } }, { source -> work { db.subscriptions().delete(source.id); source.categories.forEach { db.subscriptions().delete("${source.id}#$it") } } }, { targets -> work { message(AnnouncementUpdates.update(context, targets)) } }, { a -> work { db.announcements().markRead(a.id); context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) } }, ::saveOptions, { enabled -> if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) noticePermission.launch(Manifest.permission.POST_NOTIFICATIONS) else saveOptions(options.copy(notify = enabled)) }, { word -> work { require(word.text.isNotBlank() && word.text.length <= 100) { "關鍵字須為 1～100 字" }; db.automation().save(word) } }, { word -> work { db.automation().delete(word.id) } })
                    else -> SettingsV3(
                        options = options, update = updateState, save = ::saveOptions,
                        export = { export.launch("simple-app-${LocalDateTime.now().toString().replace(':', '-')}.json") }, import = { import.launch(arrayOf("application/json", "text/plain")) },
                        checkUpdate = { work { updateState = UpdateState.Checking; updateState = AppUpdates.latest(context) } },
                        downloadUpdate = { release -> work { updateState = UpdateState.Downloading(release); val file = AppUpdates.download(context, release).getOrElse { throw it }; updateState = UpdateState.Ready(release, file); if (!AppUpdates.install(context, file)) message("請在系統設定允許安裝後，回到這裡按「安裝已下載版本」") } },
                        installUpdate = { _, file -> if (!AppUpdates.install(context, file)) message("請先允許此 App 安裝更新") },
                        selectedSection = settingsSection, selectSection = { settingsSection = it }, syncConnection = syncConnection, syncInfo = syncInfo,
                        pairSync = { url, code -> work { val token = SyncHttpClient.pair(url, code, SyncSettings.deviceId(context), "Android" ); SyncSettings.save(context, url, token); syncConnection = SyncSettings.connection(context); syncInfo = "配對完成" } },
                        runSync = { syncConnection?.let { connection -> work { val result = SyncEngine(db, connection.deviceId).synchronize(connection); syncInfo = "同步完成：收到 ${result.changes.size} 筆變更" } } },
                        disconnectSync = { SyncSettings.clear(context); syncConnection = null; syncInfo = "已中斷同步" }
                    )
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    courseEdit?.let { c -> CourseDialog(c, meetings.filter { it.courseId == c.id }.ifEmpty { if (c.title.isBlank()) listOf(CourseMeeting(courseId = c.id, day = LocalDate.now().dayOfWeek.value)) else emptyList() }, { courseEdit = null }) { course, times -> work { db.academic().save(course, times); courseEdit = null } } }
    itemEdit?.let { ItemDialog(it, courses, { itemEdit = null }) { item -> work { db.academic().saveItem(item); itemEdit = null } } }
    moneyEdit?.let { MoneyEditor(it, { moneyEdit = null }) { row -> work { db.entries().save(row); moneyEdit = null } } }
    deletion?.let { (kind, id) -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("確認刪除") }, text = { Text(if (kind == "course") "課程與上課時段會移除；相關待辦保留並解除課程關聯。" else "刪除後無法復原。") }, confirmButton = { TextButton({ work { when (kind) { "course" -> db.academic().deleteCourse(id); "task" -> db.academic().deleteItem(id); else -> db.entries().deleteId(id) }; deletion = null } }) { Text("刪除") } }, dismissButton = { TextButton({ deletion = null }) { Text("取消") } }) }
    archive?.let { data -> AlertDialog(onDismissRequest = { archive = null }, title = { Text("確認還原備份") }, text = { Text("${data.courses.size} 門課、${data.items.size} 件事項、${data.money.size} 筆收支。還原會取代現有 App 資料與備份設定。手機行事曆不受影響。") }, confirmButton = { TextButton({ work { withContext(Dispatchers.IO) { BackupV4.restore(context, data) }; options = AppOptions.read(context); archive = null; message("還原完成") } }) { Text("取代並還原") } }, dismissButton = { TextButton({ archive = null }) { Text("取消") } }) }
}

@Composable private fun HomeV3(money: List<Entry>, courses: List<Course>, meetings: List<CourseMeeting>, tasks: List<AcademicItem>, news: List<Announcement>, keywords: List<AnnouncementKeyword>, sources: List<AnnouncementSource>, options: AppOptions, go: (String) -> Unit) = PageList {
    val today = LocalDate.now()
    item { SectionTitle("${today.monthValue} 月 ${today.dayOfMonth} 日 · 星期${weekNames[today.dayOfWeek.value - 1]}") }
    options.homeSections.forEach { section -> when (section) {
        "money" -> item { Card(Modifier.fillMaxWidth().clickable { go("money") }) { Column(Modifier.padding(18.dp)) { Text("本月生活收支"); val balance = money.filter { it.date.startsWith(YearMonth.now().toString()) }.sumOf { if (it.direction == "收入") it.cents else -it.cents }; Text("NT$ ${java.math.BigDecimal.valueOf(balance, 2).toPlainString()}", style = MaterialTheme.typography.headlineMedium) } } }
        "course" -> {
            item { TextButton({ go("course") }) { SectionTitle("今日課程 ›") } }
            val events = localAgenda(courses, meetings, emptyList(), today, today)
            if (events.isEmpty()) item { Text("今日無課程") } else items(events, key = { it.key }) { e -> Card(Modifier.fillMaxWidth().clickable { go("course") }) { Column(Modifier.padding(16.dp)) { Text(e.title, fontWeight = FontWeight.Bold); Text(e.detail) } } }
        }
        "task" -> {
            item { TextButton({ go("task") }) { SectionTitle("近期事項 ›") } }
            val events = localAgenda(emptyList(), emptyList(), tasks, today.minusYears(10), today.plusDays(30)).take(8)
            if (events.isEmpty()) item { Text("近期沒有已排定事項") } else items(events, key = { it.key }) { e -> Text("${e.date} ${e.time} · ${e.title}${if (e.date < today) "（逾期）" else ""}", Modifier.fillMaxWidth().clickable { go("task") }.padding(8.dp)) }
        }
        "agenda" -> item { Card(Modifier.fillMaxWidth().clickable { go("agenda") }) { Column(Modifier.padding(16.dp)) { Text("行事曆／行程", fontWeight = FontWeight.Bold); Text("查看 App 課程、事項與已選擇的手機行事曆。") } } }
        "announcements" -> {
            item { TextButton({ go("announcements") }) { SectionTitle("校園公告 ›") } }
            if (news.isEmpty()) item { Text("尚無公告，請訂閱單位並更新。") } else items(news.sortedByDescending { keywordMatches(it, sources.find { s -> s.id == it.sourceId }, keywords).isNotEmpty() }.take(6), key = { it.id }) { a -> val hit = keywordMatches(a, sources.find { it.id == a.sourceId }, keywords); Text("${if (hit.isEmpty()) "" else "★ "}${a.title}\n${a.date}", Modifier.fillMaxWidth().clickable { go("announcements") }.padding(8.dp), color = if (hit.isEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary) }
        }
    } }
    if (options.homeSections.isEmpty()) item { Text("首頁目前沒有顯示項目，可到設定加入。") }
}

@Composable private fun AgendaPage(courses: List<Course>, meetings: List<CourseMeeting>, tasks: List<AcademicItem>) {
    val context = LocalContext.current; val owner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("calendar-display", 0) }
    var selected by remember { mutableStateOf(prefs.getStringSet("selected", emptySet())!!.toSet()) }
    var showCourses by rememberSaveable { mutableStateOf(prefs.getBoolean("courses", true)) }
    var showItems by rememberSaveable { mutableStateOf(prefs.getBoolean("items", true)) }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var revision by remember { mutableIntStateOf(0) }
    var allowed by remember { mutableStateOf(CalendarReader.allowed(context)) }
    var calendars by remember { mutableStateOf(emptyList<DeviceCalendar>()) }
    var external by remember { mutableStateOf(emptyList<AgendaEvent>()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; revision++ }
    DisposableEffect(owner) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { allowed = CalendarReader.allowed(context); revision++ } }; owner.lifecycle.addObserver(observer); onDispose { owner.lifecycle.removeObserver(observer) } }
    DisposableEffect(allowed) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { revision++ } }
        if (allowed) runCatching { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    LaunchedEffect(allowed, selected, date, revision) {
        external = emptyList(); error = ""; loading = true
        try { if (allowed) { calendars = withContext(Dispatchers.IO) { CalendarReader.calendars(context) }; external = withContext(Dispatchers.IO) { CalendarReader.events(context, selected.intersect(calendars.map { it.id.toString() }.toSet()), LocalDate.parse(date), LocalDate.parse(date).plusDays(6)) } } else calendars = emptyList() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = "無法讀取行事曆：${e.message}"; if (!CalendarReader.allowed(context)) allowed = false }
        finally { loading = false }
    }
    PageList {
        item { SectionTitle("行事曆與近期行程"); PickDate(date, "起始日期") { date = it }; Row { TextButton({ date = LocalDate.parse(date).minusWeeks(1).toString() }) { Text("前七天") }; TextButton({ date = LocalDate.now().toString() }) { Text("今天") }; TextButton({ date = LocalDate.parse(date).plusWeeks(1).toString() }) { Text("後七天") } } }
        item { ToggleRow("顯示 App 課程", showCourses) { showCourses = it; prefs.edit().putBoolean("courses", it).apply() }; ToggleRow("顯示作業、報告、考試與待辦", showItems) { showItems = it; prefs.edit().putBoolean("items", it).apply() } }
        item { SectionTitle("手機行事曆分類"); Text("勾選手機已同步的日曆。僅讀取事件，App 事項不會寫入手機行事曆。")
            if (!allowed) { Button({ permission.launch(Manifest.permission.READ_CALENDAR) }) { Text("允許讀取手機行事曆") }; TextButton({ context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("開啟權限設定") } }
            else if (calendars.isEmpty() && !loading) Text("手機尚無可讀取的日曆，請先在手機行事曆同步帳號。")
        }
        items(calendars, key = { it.id }) { c -> ToggleRow("${c.name}\n${c.account}", c.id.toString() in selected) { checked -> selected = if (checked) selected + c.id.toString() else selected - c.id.toString(); prefs.edit().putStringSet("selected", selected).apply() } }
        item { if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()); if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error); TextButton({ revision++ }) { Text("重新讀取") } }
        val start = LocalDate.parse(date)
        val events = (localAgenda(if (showCourses) courses else emptyList(), if (showCourses) meetings else emptyList(), if (showItems) tasks else emptyList(), start, start.plusDays(6)) + if (allowed) external else emptyList()).sortedWith(compareBy<AgendaEvent> { it.date }.thenBy { it.time })
        if (events.isEmpty() && !loading) item { Text("這七天沒有符合所選分類的行程") }
        events.groupBy { it.date }.forEach { (day, rows) -> item { SectionTitle("$day · 星期${weekNames[day.dayOfWeek.value - 1]}") }; items(rows, key = { it.key }) { e -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("${e.time.ifBlank { "全天／未指定時間" }} · ${e.title}", fontWeight = FontWeight.Bold); Text("${e.source} · ${e.detail}") } } } }
    }
}

package tw.thorsheep.studentjournal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF356B58), secondary = Color(0xFF9B6C3C),
                background = Color(0xFFF6F8F3), surface = Color(0xFFF6F8F3), primaryContainer = Color(0xFFD8EBDC))) {
                Journal()
            }
        }
    }
}
private fun money(cents: Long) = "NT$ " + "%,.2f".format(java.util.Locale.US, cents / 100.0)
private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Journal() {
    val context = LocalContext.current
    val dao = remember { JournalDb.get(context).entries() }
    val entries by dao.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf<Entry?>(null) }
    var deleting by remember { mutableStateOf<Entry?>(null) }
    var restoring by remember { mutableStateOf<List<Entry>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var day by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    fun notify(message: String) { scope.launch { snack.showSnackbar(message) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) scope.launch {
            busy = true
            try {
                val snapshot = entries.toList()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(Backup.encode(snapshot)) }
                        ?: error("無法開啟檔案")
                }
                notify("備份已匯出，共 ${snapshot.size} 筆")
            } catch(e: Exception) { notify("匯出失敗：${e.message}") } finally { busy = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            busy = true
            try {
                restoring = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while(true) {
                            val n = stream.read(buffer)
                            if(n == -1) break
                            require(out.size() + n <= Backup.MAX_BYTES) { "備份檔不得超過 5 MB" }
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    } ?: error("無法讀取檔案")
                    Backup.decode(String(bytes, Charsets.UTF_8))
                }
            } catch(e: Exception) { notify("無法匯入：${e.message}") } finally { busy = false }
        }
    }
    val labels = listOf("首頁", "記帳", "課表", "待辦", "備份")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.AccountBalanceWallet, Icons.Outlined.DateRange, Icons.Outlined.CheckCircle, Icons.Outlined.Folder)
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = { TopAppBar(title = { Column { Text("Simple App", fontWeight = FontWeight.Bold); Text("把生活，安排成喜歡的樣子", style = MaterialTheme.typography.labelMedium) } }) },
        bottomBar = { NavigationBar { labels.forEachIndexed { i, label -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], label) }, label = { Text(label) }) } } },
        floatingActionButton = { if(tab in 1..3) FloatingActionButton(onClick = {
            editor = Entry(type = listOf("", "money", "course", "task")[tab], title = "", date = if(tab == 1) LocalDate.now().toString() else "", category = if(tab == 1) "支出" else "", day = day, start = "09:00", end = "10:00")
        }) { Icon(Icons.Outlined.Add, "新增${labels[tab]}") } }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            when(tab) {
                0 -> {
                    val today = LocalDate.now()
                    val monthly = entries.filter { it.type == "money" && it.date.startsWith(YearMonth.now().toString()) }
                    item { Text("${today.monthValue} 月 ${today.dayOfMonth} 日 · 星期${weekdays[today.dayOfWeek.value - 1]}", style = MaterialTheme.typography.titleLarge) }
                    item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.padding(20.dp)) {
                        Text("本月生活收支", style = MaterialTheme.typography.labelLarge)
                        Text(money(monthly.sumOf { if(it.category == "收入") it.cents else -it.cents }), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text("收入 ${money(monthly.filter { it.category == "收入" }.sumOf { it.cents })}  /  支出 ${money(monthly.filter { it.category == "支出" }.sumOf { it.cents })}")
                    } } }
                    item { Section("今日課程") }
                    val courses = entries.filter { it.type == "course" && it.day == today.dayOfWeek.value }.sortedBy { it.start }
                    if(courses.isEmpty()) item { Empty("今天沒有課程", "到課表加入你的第一堂課。") }
                    items(courses, key = { "home-${it.id}" }) { RowCard(it, entries, { editor = it }, { deleting = it }) }
                    item { Section("待完成") }
                    val tasks = entries.filter { it.type == "task" && !it.done }.sortedBy { it.date.ifEmpty { "9999" } }
                    if(tasks.isEmpty()) item { Empty("目前沒有待辦", "把作業、考試或生活小事記下來。") }
                    items(tasks.take(5), key = { "home-task-${it.id}" }) { RowCard(it, entries, { editor = it }, { deleting = it }) { value -> scope.launch { dao.save(it.copy(done = value)) } } }
                }
                1 -> {
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { month = YearMonth.parse(month).minusMonths(1).toString() }) { Text("上個月") }
                        Text(month, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { month = YearMonth.parse(month).plusMonths(1).toString() }) { Text("下個月") }
                    } }
                    val rows = entries.filter { it.type == "money" && it.date.startsWith(month) }.sortedByDescending { it.date }
                    item { Card { Column(Modifier.padding(16.dp)) {
                        Text("收入  ${money(rows.filter { it.category == "收入" }.sumOf { it.cents })}")
                        Text("支出  ${money(rows.filter { it.category == "支出" }.sumOf { it.cents })}")
                        Text("結餘  ${money(rows.sumOf { if(it.category == "收入") it.cents else -it.cents })}", fontWeight = FontWeight.Bold)
                    } } }
                    if(rows.isEmpty()) item { Empty("這個月還沒有紀錄", "點右下角 ＋ 記下第一筆收支。") }
                    items(rows, key = { it.id }) { RowCard(it, entries, { editor = it }, { deleting = it }) }
                }
                2 -> {
                    item { Section("每週課表") }
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { weekdays.forEachIndexed { index, label ->
                        FilterChip(selected = day == index + 1, onClick = { day = index + 1 }, label = { Text(label) })
                    } } }
                    val rows = entries.filter { it.type == "course" && it.day == day }.sortedBy { it.start }
                    if(rows.isEmpty()) item { Empty("星期${weekdays[day - 1]}沒有課程", "每個上課時段新增一筆，同一課程可新增多堂。") }
                    items(rows, key = { it.id }) { RowCard(it, entries, { editor = it }, { deleting = it }) }
                }
                3 -> {
                    item { Section("我的待辦") }
                    val rows = entries.filter { it.type == "task" }.sortedWith(compareBy<Entry> { it.done }.thenBy { it.date.ifEmpty { "9999" } })
                    if(rows.isEmpty()) item { Empty("留一個位置給下一件事", "新增作業、考試準備或生活待辦。") }
                    items(rows, key = { it.id }) { RowCard(it, entries, { editor = it }, { deleting = it }) { value -> scope.launch { dao.save(it.copy(done = value)) } } }
                }
                4 -> {
                    item { Section("備份與還原") }
                    item { Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("你的資料，留在你的手機", style = MaterialTheme.typography.titleMedium)
                        Text("目前有 ${entries.count { it.type == "money" }} 筆收支、${entries.count { it.type == "course" }} 堂課、${entries.count { it.type == "task" }} 個待辦。")
                        Text("匯出 JSON 檔後，可保存到你選擇的位置，或傳到另一台手機還原。備份未加密，請妥善保存。")
                        Button(enabled = !busy, onClick = { export.launch("simple-app-${LocalDate.now()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("匯出全部資料") }
                        OutlinedButton(enabled = !busy, onClick = { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("選擇備份檔還原") }
                        Text("還原會完整取代目前資料，建議先匯出備份。兩台手機不會自動同步。", style = MaterialTheme.typography.bodySmall)
                    } } }
                    item { Text("Simple App 1.0.0 · 離線版", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
    editor?.let { original -> Editor(original, entries.filter { it.type == "course" }, onDismiss = { editor = null }) { updated ->
        scope.launch {
            try { dao.save(updated); editor = null; notify("已儲存") }
            catch(e: Exception) { notify("儲存失敗：${e.message}") }
        }
    } }
    deleting?.let { e -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("刪除「${e.title}」？") },
        text = { Text(if(e.type == "course") "相關待辦會保留，並解除課程關聯。" else "刪除後無法復原。") },
        confirmButton = { TextButton(onClick = { scope.launch { try { dao.delete(e); deleting = null } catch(ex: Exception) { notify("刪除失敗：${ex.message}") } } }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    restoring?.let { backup -> AlertDialog(onDismissRequest = { if(!busy) restoring = null }, title = { Text("確認還原備份") },
        text = { Text("將以 ${backup.count { it.type == "money" }} 筆收支、${backup.count { it.type == "course" }} 堂課、${backup.count { it.type == "task" }} 個待辦，取代目前全部 ${entries.size} 筆資料。此操作無法復原。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { dao.replace(backup); restoring = null; notify("資料已還原") }
            catch(e: Exception) { notify("還原失敗，原資料保留：${e.message}") } finally { busy = false }
        } }) { Text("取代並還原") } }, dismissButton = { TextButton(enabled = !busy, onClick = { restoring = null }) { Text("取消") } }) }
}
@Composable private fun Section(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
@Composable private fun Empty(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun RowCard(e: Entry, entries: List<Entry>, edit: () -> Unit, delete: () -> Unit, check: ((Boolean) -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().clickable(onClick = edit), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if(check != null) Checkbox(checked = e.done, onCheckedChange = check)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(e.title, fontWeight = FontWeight.SemiBold)
                when(e.type) {
                    "money" -> { Text("${e.category} · ${e.note.ifEmpty { "未分類" }} · ${e.date}", style = MaterialTheme.typography.bodySmall); Text((if(e.category == "收入") "+ " else "− ") + money(e.cents), color = MaterialTheme.colorScheme.primary) }
                    "course" -> { Text("${e.start}–${e.end} · ${e.room.ifEmpty { "未填教室" }}"); if(e.teacher.isNotEmpty()) Text(e.teacher, style = MaterialTheme.typography.bodySmall) }
                    "task" -> {
                        val overdue = !e.done && e.date.isNotEmpty() && e.date < LocalDate.now().toString()
                        Text(if(e.done) "已完成" else if(e.date.isEmpty()) "無截止日期" else "${if(overdue) "已逾期 · " else "截止 "}${e.date}", color = if(overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        entries.find { it.id == e.courseId }?.let { Text("課程：${it.title}", style = MaterialTheme.typography.bodySmall) }
                        if(e.note.isNotEmpty()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            IconButton(onClick = delete) { Icon(Icons.Outlined.Delete, "刪除${e.title}") }
        }
    }
}
@Composable private fun Editor(original: Entry, courses: List<Entry>, onDismiss: () -> Unit, save: (Entry) -> Unit) {
    var title by rememberSaveable(original.id) { mutableStateOf(original.title) }
    var date by rememberSaveable(original.id) { mutableStateOf(original.date) }
    var amount by rememberSaveable(original.id) { mutableStateOf(if(original.cents > 0) BigDecimal.valueOf(original.cents, 2).toPlainString() else "") }
    var category by rememberSaveable(original.id) { mutableStateOf(original.category) }
    var note by rememberSaveable(original.id) { mutableStateOf(original.note) }
    var day by rememberSaveable(original.id) { mutableStateOf(original.day.toString()) }
    var start by rememberSaveable(original.id) { mutableStateOf(original.start) }
    var end by rememberSaveable(original.id) { mutableStateOf(original.end) }
    var room by rememberSaveable(original.id) { mutableStateOf(original.room) }
    var teacher by rememberSaveable(original.id) { mutableStateOf(original.teacher) }
    var courseId by rememberSaveable(original.id) { mutableStateOf(original.courseId) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(original.title.isEmpty()) "新增${when(original.type) { "money" -> "收支"; "course" -> "課程"; else -> "待辦" }}" else "編輯${original.title}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text(if(original.type == "money") "項目 / 備註" else "名稱") }, singleLine = true)
            when(original.type) {
                "money" -> {
                    Row { listOf("支出", "收入").forEach { label -> FilterChip(category == label, { category = label }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp)) } }
                    OutlinedTextField(amount, { amount = it }, label = { Text("金額（新台幣）") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    OutlinedTextField(date, { date = it }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("分類，例如餐飲、交通、薪資") }, singleLine = true)
                }
                "course" -> {
                    OutlinedTextField(day, { day = it }, label = { Text("星期 1～7（7 為週日）") }, singleLine = true)
                    OutlinedTextField(start, { start = it }, label = { Text("開始 HH:mm，例如 09:00") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, label = { Text("結束 HH:mm，例如 10:00") }, singleLine = true)
                    OutlinedTextField(room, { room = it }, label = { Text("教室（選填）") }, singleLine = true)
                    OutlinedTextField(teacher, { teacher = it }, label = { Text("教師（選填）") }, singleLine = true)
                }
                "task" -> {
                    OutlinedTextField(date, { date = it }, label = { Text("截止 YYYY-MM-DD（選填）") }, singleLine = true)
                    OutlinedTextField(note, { note = it }, label = { Text("備註（選填）") })
                    Box {
                        OutlinedButton(onClick = { expanded = true }) { Text(courses.find { it.id == courseId }?.title ?: "關聯課程（選填）") }
                        DropdownMenu(expanded, { expanded = false }) {
                            DropdownMenuItem(text = { Text("不關聯課程") }, onClick = { courseId = ""; expanded = false })
                            courses.forEach { c -> DropdownMenuItem(text = { Text("${c.title} · 週${weekdays[c.day - 1]} ${c.start}") }, onClick = { courseId = c.id; expanded = false }) }
                        }
                    }
                }
            }
            if(error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val updated = original.copy(title = title.trim(), date = date.trim(),
                cents = if(original.type == "money") amount.toBigDecimal().movePointRight(2).longValueExact() else 0,
                category = category, note = note.trim(), day = if(original.type == "course") day.toInt() else original.day,
                start = start.trim(), end = end.trim(), room = room.trim(), teacher = teacher.trim(), courseId = courseId)
            Backup.validate(updated)
            save(updated)
        } catch(e: Exception) { error = when(e) {
            is java.time.format.DateTimeParseException -> "請輸入有效日期 YYYY-MM-DD 或時間 HH:mm"
            is NumberFormatException, is ArithmeticException -> "請輸入有效金額（最多兩位小數）或星期數字"
            else -> e.message ?: "請檢查輸入內容"
        } }
    }) { Text("儲存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

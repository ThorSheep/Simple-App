package tw.thorsheep.studentjournal

import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun NewsPage(sources: List<AnnouncementSource>, rows: List<Announcement>, subscribed: List<String>, keywords: List<AnnouncementKeyword>,
    toggle: (String, Boolean) -> Unit, clear: (AnnouncementSource) -> Unit, refresh: (List<AnnouncementSource>) -> Unit, open: (Announcement) -> Unit, settings: () -> Unit) {
    var hitsOnly by rememberSaveable { mutableStateOf(false) }
    PageList {
        item { SectionTitle("校園公告"); SourceSelector(sources, subscribed) { toggle(it.id, true) }; TextButton(settings) { Text("設定關鍵字與自動更新") } }
        val selected = sources.filter { s -> s.id in subscribed || subscribed.any { it.startsWith("${s.id}#") } }
        item { SectionTitle("我的訂閱"); if (selected.isNotEmpty()) OutlinedButton({ refresh(selected) }) { Text("更新全部訂閱") } }
        if (selected.isEmpty()) item { Text("選擇學校與單位後即可讀取公告") }
        items(selected, key = { it.id }) { source -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
            Text(source.name, fontWeight = FontWeight.Bold); Text(source.school, style = MaterialTheme.typography.bodySmall)
            Row { TextButton({ refresh(listOf(source)) }) { Text("更新") }; TextButton({ clear(source) }) { Text("取消訂閱") } }
            if (source.categories.isNotEmpty()) {
                ToggleRow("全部分類", source.id in subscribed) { toggle(source.id, it) }
                source.categories.forEach { category -> ToggleRow(category, "${source.id}#$category" in subscribed) { toggle("${source.id}#$category", it) } }
            }
        } } }
        item { SectionTitle("已接收公告"); ToggleRow("只看關鍵字命中", hitsOnly) { hitsOnly = it } }
        val visible = rows.filter { !hitsOnly || keywordMatches(it, sources.find { s -> s.id == it.sourceId }, keywords).isNotEmpty() }
        if (visible.isEmpty()) item { Text(if (hitsOnly) "沒有命中關鍵字的公告" else "尚無公告，請更新訂閱單位。") }
        items(visible, key = { it.id }) { a -> val hits = keywordMatches(a, sources.find { it.id == a.sourceId }, keywords)
            Card(Modifier.fillMaxWidth().clickable { open(a) }, colors = CardDefaults.cardColors(containerColor = if (hits.isEmpty()) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(sources.find { it.id == a.sourceId }?.name ?: "公告", style = MaterialTheme.typography.labelMedium)
                    if (hits.isNotEmpty()) Text("★ ${hits.joinToString("、")}", color = MaterialTheme.colorScheme.primary)
                    Text(if (a.category.isBlank()) a.title else "[${a.category}] ${a.title}", fontWeight = FontWeight.Bold)
                    Text(a.date + if (a.read) " · 已讀" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable fun SettingsV3(options: AppOptions, keywords: List<AnnouncementKeyword>, save: (AppOptions) -> Unit, setNotify: (Boolean) -> Unit,
    saveKeyword: (AnnouncementKeyword) -> Unit, deleteKeyword: (AnnouncementKeyword) -> Unit, export: () -> Unit, import: () -> Unit) {
    val context = LocalContext.current
    var word by rememberSaveable { mutableStateOf("") }; var wordError by remember { mutableStateOf("") }
    val state = remember { context.getSharedPreferences("announcement-state", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(state) { val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }; state.registerOnSharedPreferenceChangeListener(listener); onDispose { state.unregisterOnSharedPreferenceChangeListener(listener) } }
    PageList {
        item { SectionTitle("底部常用功能"); Text("首頁固定顯示，其餘最多四項。") }
        items(listOf("money" to "記帳", "course" to "課程", "task" to "待辦", "agenda" to "行事曆／行程", "announcements" to "公告")) { (id, name) ->
            ToggleRow(name, id in options.quick) { checked -> if (!checked) save(options.copy(quick = options.quick - id)) else if (options.quick.size < 4) save(options.copy(quick = options.quick + id)) }
        }
        if (options.quick.size == 4) item { Text("已選四項；請先取消一項再加入其他功能。") }
        item { SectionTitle("首頁內容"); ToggleRow("顯示今日課程", options.showCourses) { save(options.copy(showCourses = it)) }; ToggleRow("顯示近期事項", options.showTasks) { save(options.copy(showTasks = it)) } }
        item { SectionTitle("公告自動更新"); Choice("更新頻率", options.intervalHours.toString(), listOf("0" to "關閉（僅手動）", "6" to "每 6 小時", "12" to "每 12 小時", "24" to "每天一次")) { save(options.copy(intervalHours = it.toInt())) }
            ToggleRow("僅使用 Wi-Fi／不計量網路自動更新", options.wifiOnly) { save(options.copy(wifiOnly = it)) }
            Text("背景更新只抓取已訂閱單位；實際時間可能因系統省電與網路狀態延後。")
            val last = remember(revision) { state.getLong("lastAttempt", 0) }
            Text(if (last == 0L) "尚未更新" else "最後更新：${Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
            Text(remember(revision) { state.getString("lastResult", "").orEmpty() })
        }
        item { SectionTitle("公告關鍵字"); Text("比對公告標題、單位及分類，不分英文大小寫；不讀取原文內文。")
            OutlinedTextField(word, { word = it; wordError = "" }, label = { Text("新增關鍵字") }, modifier = Modifier.fillMaxWidth())
            TextButton({ val text = word.trim(); if (text.isBlank() || text.length > 100) wordError = "請輸入 1～100 字" else if (keywords.any { it.text.equals(text, true) }) wordError = "此關鍵字已存在" else { saveKeyword(AnnouncementKeyword(text = text)); word = "" } }) { Text("新增") }
            if (wordError.isNotBlank()) Text(wordError, color = MaterialTheme.colorScheme.error)
        }
        items(keywords, key = { it.id }) { keyword -> Row { Box(Modifier.weight(1f)) { ToggleRow(keyword.text, keyword.enabled) { saveKeyword(keyword.copy(enabled = it)) } }; TextButton({ deleteKeyword(keyword) }) { Text("刪除") } } }
        item { ToggleRow("命中關鍵字的新公告發送通知", options.notify, setNotify); Text("每個單位首次更新只建立現有公告紀錄，不補發舊公告。手動與自動更新皆會檢測；同一公告不重複通知。") }
        item { SectionTitle("資料備份"); Button(export, Modifier.fillMaxWidth()) { Text("匯出資料與設定") }; OutlinedButton(import, Modifier.fillMaxWidth()) { Text("選擇備份檔還原") }; Text("備份包含記帳、課程、事項、訂閱、關鍵字與 App 設定。手機行事曆事件與日曆選擇僅留在本機，換機後需重新選擇。") }
        item { Text("Simple App 3.0.0", style = MaterialTheme.typography.bodySmall) }
    }
}

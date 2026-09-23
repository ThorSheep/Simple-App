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
    options: AppOptions, toggle: (String, Boolean) -> Unit, clear: (AnnouncementSource) -> Unit, refresh: (List<AnnouncementSource>) -> Unit, open: (Announcement) -> Unit,
    saveOptions: (AppOptions) -> Unit, setNotify: (Boolean) -> Unit, saveKeyword: (AnnouncementKeyword) -> Unit, deleteKeyword: (AnnouncementKeyword) -> Unit) {
    var hitsOnly by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf("content") }
    var word by rememberSaveable { mutableStateOf("") }; var wordError by remember { mutableStateOf("") }
    val context = LocalContext.current
    val state = remember { context.getSharedPreferences("announcement-state", 0) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(state) { val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }; state.registerOnSharedPreferenceChangeListener(listener); onDispose { state.unregisterOnSharedPreferenceChangeListener(listener) } }
    val selected = sources.filter { source -> source.id in subscribed || subscribed.any { it.startsWith("${source.id}#") } }
    PageList {
        item { SectionTitle("校園公告"); CategoryTabs(section, listOf("content" to "公告內容", "subscriptions" to "訂閱管理", "settings" to "設定")) { section = it } }
        when (section) {
            "subscriptions" -> {
                item { Text("選擇學校與單位，再設定要訂閱全部或特定分類。") }
                item { SourceSelector(sources, subscribed) { toggle(it.id, true) } }
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
            }
            "content" -> {
                item { SectionTitle("已接收公告"); ToggleRow("只看關鍵字命中", hitsOnly) { hitsOnly = it } }
                val visible = rows.filter { !hitsOnly || keywordMatches(it, sources.find { s -> s.id == it.sourceId }, keywords).isNotEmpty() }
                if (visible.isEmpty()) item { Text(if (hitsOnly) "沒有命中關鍵字的公告" else "尚無公告，請至「訂閱管理」選擇單位並更新。") }
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
            else -> {
                item { SectionTitle("公告自動更新"); Choice("更新頻率", options.intervalHours.toString(), listOf("0" to "關閉（僅手動）", "6" to "每 6 小時", "12" to "每 12 小時", "24" to "每天一次")) { saveOptions(options.copy(intervalHours = it.toInt())) }
                    ToggleRow("僅使用 Wi-Fi／不計量網路自動更新", options.wifiOnly) { saveOptions(options.copy(wifiOnly = it)) }
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
            }
        }
    }
}

@Composable fun SettingsV3(options: AppOptions, update: UpdateState, save: (AppOptions) -> Unit, export: () -> Unit, import: () -> Unit,
    checkUpdate: () -> Unit, downloadUpdate: (AppRelease) -> Unit, installUpdate: (AppRelease, java.io.File) -> Unit,
    selectedSection: String, selectSection: (String) -> Unit, syncConnection: SyncConnection?, syncInfo: String,
    pairSync: (String, String) -> Unit, runSync: () -> Unit, disconnectSync: () -> Unit) {
    PageList {
        item { SectionTitle("設定"); CategoryTabs(selectedSection, listOf("interface" to "介面", "data" to "資料與版本"), selectSection) }
        when (selectedSection) {
            "interface" -> {
                item { SectionTitle("底部常用功能"); Text("可自由選擇、排序與設定首頁位置，最多四項。") }
                items(listOf("money" to "記帳", "course" to "課程", "task" to "待辦", "agenda" to "行事曆／行程", "announcements" to "公告")) { (id, name) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Box(Modifier.weight(1f)) { ToggleRow(name, id in options.quick) { checked -> if (!checked) save(options.copy(quick = options.quick - id, homePosition = options.homePosition.coerceIn(0, (options.quick - id).size))) else if (options.quick.size < 4) save(options.copy(quick = options.quick + id)) } }; val index = options.quick.indexOf(id); if (index >= 0) { TextButton({ save(options.copy(quick = options.quick.move(index, index - 1))) }, enabled = index > 0) { Text("↑") }; TextButton({ save(options.copy(quick = options.quick.move(index, index + 1))) }, enabled = index < options.quick.lastIndex) { Text("↓") } } }
                }
                if (options.quick.size == 4) item { Text("已選四項；請先取消一項再加入其他功能。") }
                item { Choice("首頁在底部的位置", options.homePosition.toString(), (0..options.quick.size).map { it.toString() to "第 ${it + 1} 個" }) { save(options.copy(homePosition = it.toInt())) }; ToggleRow("左右滑動切換底部功能", options.swipeNavigation) { save(options.copy(swipeNavigation = it)) }; Text("左右滑動只在已選擇的底部功能間切換，邊緣保留給 Android 系統返回手勢。") }
                item { SectionTitle("首頁內容"); Text("可開關並調整各區塊順序。") }
                items(listOf("money" to "本月生活收支", "course" to "今日課程", "task" to "近期事項", "agenda" to "行事曆／行程入口", "announcements" to "校園公告")) { (id, name) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Box(Modifier.weight(1f)) { ToggleRow(name, id in options.homeSections) { checked -> if (checked) save(options.copy(homeSections = options.homeSections + id)) else save(options.copy(homeSections = options.homeSections - id)) } }; val index = options.homeSections.indexOf(id); if (index >= 0) { TextButton({ save(options.copy(homeSections = options.homeSections.move(index, index - 1))) }, enabled = index > 0) { Text("↑") }; TextButton({ save(options.copy(homeSections = options.homeSections.move(index, index + 1))) }, enabled = index < options.homeSections.lastIndex) { Text("↓") } } }
                }
                item { SectionTitle("外觀"); Choice("主題", options.theme, listOf("system" to "跟隨系統", "light" to "淺色", "dark" to "深色")) { save(options.copy(theme = it)) } }
            }
            else -> {
                item { SectionTitle("自託管同步")
                    var serverUrl by rememberSaveable(syncConnection?.serverUrl) { mutableStateOf(syncConnection?.serverUrl ?: "") }
                    var pairCode by rememberSaveable { mutableStateOf("") }
                    if (syncConnection == null) {
                        Text("輸入你自行架設的 HTTPS 伺服器網址與一次性配對碼。")
                        OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("伺服器網址") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pairCode, { pairCode = it }, label = { Text("配對碼") }, modifier = Modifier.fillMaxWidth())
                        Button({ pairSync(serverUrl, pairCode) }, Modifier.fillMaxWidth(), enabled = serverUrl.isNotBlank() && pairCode.isNotBlank()) { Text("配對並同步") }
                    } else {
                        Text("已連線至 ${syncConnection.serverUrl}")
                        Button(runSync, Modifier.fillMaxWidth()) { Text("立即同步") }
                        OutlinedButton(disconnectSync, Modifier.fillMaxWidth()) { Text("中斷同步") }
                    }
                    if (syncInfo.isNotBlank()) Text(syncInfo, color = MaterialTheme.colorScheme.primary)
                }
                item { SectionTitle("App 版本更新"); ToggleRow("啟動時自動檢查新版本", options.checkAppUpdates) { save(options.copy(checkAppUpdates = it)) }
            when (update) {
                UpdateState.Idle -> TextButton(checkUpdate) { Text("檢查新版本") }
                UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                UpdateState.Current -> Text("目前已是最新版本。", color = MaterialTheme.colorScheme.primary)
                is UpdateState.Available -> { Text("可更新至 ${update.release.tag}", fontWeight = FontWeight.Bold); if (update.release.notes.isNotBlank()) Text(update.release.notes.lineSequence().take(3).joinToString("\n")); Button({ downloadUpdate(update.release) }) { Text("下載並更新") } }
                is UpdateState.Downloading -> { Text("正在下載 ${update.release.tag}…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                is UpdateState.Ready -> { Text("${update.release.tag} 已下載完成。"); Button({ installUpdate(update.release, update.file) }) { Text("安裝已下載版本") } }
                is UpdateState.Failed -> { Text("檢查或下載失敗：${update.message}", color = MaterialTheme.colorScheme.error); TextButton(checkUpdate) { Text("重新檢查") } }
                UpdateState.Unsupported -> Text("Android Studio 的 debug 測試版與正式 APK 簽章不同，請安裝正式版後使用 App 內更新。")
            }
            Text("更新僅從 GitHub Release 下載正式 APK。安裝前 Android 會驗證簽章並要求你確認；若系統尚未允許此 App 安裝更新，會先開啟系統設定。")
        }
        item { SectionTitle("資料備份"); Button(export, Modifier.fillMaxWidth()) { Text("匯出資料與設定") }; OutlinedButton(import, Modifier.fillMaxWidth()) { Text("選擇備份檔還原") }; Text("備份包含記帳、課程、事項、訂閱、關鍵字與 App 設定。手機行事曆事件與日曆選擇僅留在本機，換機後需重新選擇。") }
            }
        }
        item { Text("Simple App 3.1.0", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun CategoryTabs(selected: String, choices: List<Pair<String, String>>, choose: (String) -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    choices.forEach { (id, label) -> FilterChip(selected = selected == id, onClick = { choose(id) }, label = { Text(label) }, modifier = Modifier.weight(1f)) }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> = to.coerceIn(indices).let { destination -> toMutableList().also { value -> value.add(destination, value.removeAt(from)) } }

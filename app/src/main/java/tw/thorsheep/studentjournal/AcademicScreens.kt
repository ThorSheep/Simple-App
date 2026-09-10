package tw.thorsheep.studentjournal

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

val courseColors = listOf(Color(0xFF356B58), Color(0xFF346DA8), Color(0xFF915B9D), Color(0xFFAE6434), Color(0xFF9A4852), Color(0xFF557B80))
val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")

@Composable fun PageList(content: LazyListScope.() -> Unit) = LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
@Composable fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
@Composable fun Choice(label: String, value: String, choices: List<Pair<String, String>>, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box { OutlinedButton({ open = true }) { Text("$label：${choices.find { it.first == value }?.second ?: value}") }
        DropdownMenu(open, { open = false }) { choices.forEach { (id, name) -> DropdownMenuItem({ Text(name) }, { change(id); open = false }) } }
    }
}
@Composable fun ToggleRow(title: String, checked: Boolean, change: (Boolean) -> Unit) = Row(Modifier.fillMaxWidth().clickable { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
    Checkbox(checked, change); Text(title, Modifier.weight(1f))
}
@Composable fun AcademicCard(item: AcademicItem, courses: List<Course>, edit: () -> Unit, check: (Boolean) -> Unit, delete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = edit)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(item.done, check)
        Column(Modifier.weight(1f)) {
            Text("${if (item.important) "★ " else ""}${item.title}", fontWeight = FontWeight.Bold)
            Text("${item.kind} · ${courses.find { it.id == item.courseId }?.title ?: "未關聯課程"}", style = MaterialTheme.typography.bodySmall)
            Text(if (item.done) "已完成" else if (item.date.isBlank()) "未設定截止日期" else "${item.date} ${item.time}")
            if (!item.done && item.date.isNotBlank() && LocalDate.parse(item.date) < LocalDate.now()) Text("已逾期", color = MaterialTheme.colorScheme.error)
            if (item.kind == "報告" && item.presentationDate.isNotBlank()) Text("報告：${item.presentationDate} ${item.presentationTime}")
            if (item.grouped) Text("分組：${item.groupNote.ifBlank { "尚未填寫組別" }}")
        }
        TextButton(delete) { Text("刪除") }
    } }
}

@Composable fun CoursePage(courses: List<Course>, meetings: List<CourseMeeting>, tasks: List<AcademicItem>,
    editCourse: (Course) -> Unit, deleteCourse: (Course) -> Unit, newItem: (String) -> Unit,
    editItem: (AcademicItem) -> Unit, checkItem: (AcademicItem, Boolean) -> Unit, deleteItem: (AcademicItem) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("table") }
    var day by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val current = courses.find { it.id == selected }
    PageList {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(tab == "table", { tab = "table"; selected = null }, { Text("課表") }); FilterChip(tab == "list", { tab = "list"; selected = null }, { Text("課程清單") }) } }
        if (current != null) {
            item { TextButton({ selected = null }) { Text("返回課程") }; SectionTitle(current.title); Text("${current.teacher} · ${current.room}") }
            items(meetings.filter { it.courseId == current.id }, key = { it.id }) { Text("星期${weekNames[it.day - 1]} ${it.start}–${it.end} · ${it.room.ifBlank { current.room }}") }
            item { Row { TextButton({ editCourse(current) }) { Text("編輯課程／時段") }; TextButton({ deleteCourse(current) }) { Text("刪除課程") } } }
            item { SectionTitle("課程事項"); Button({ newItem(current.id) }) { Text("新增作業、報告或考試") } }
            val list = tasks.filter { it.courseId == current.id }.sortedWith(compareBy<AcademicItem> { it.done }.thenBy { it.date.ifBlank { "9999" } })
            if (list.isEmpty()) item { Text("尚無課程事項") }
            items(list, key = { it.id }) { AcademicCard(it, courses, { editItem(it) }, { value -> checkItem(it, value) }, { deleteItem(it) }) }
        } else if (tab == "table") {
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { weekNames.forEachIndexed { index, name -> FilterChip(day == index + 1, { day = index + 1 }, { Text(name) }) } } }
            val active = courses.filter { !it.archived }.associateBy { it.id }
            val list = meetings.filter { it.day == day && it.courseId in active }.sortedBy { it.start }
            if (list.isEmpty()) item { Text("今天沒有課程。使用右下角 ＋ 新增課程及時段。") }
            items(list, key = { it.id }) { m -> val c = active.getValue(m.courseId)
                Card(Modifier.fillMaxWidth().clickable { selected = c.id }) { Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(4.dp).height(48.dp).background(courseColors[c.color]))
                    Column { Text(c.title, fontWeight = FontWeight.Bold); Text("${m.start}–${m.end} · ${m.room.ifBlank { c.room }}"); Text(c.teacher) }
                } }
            }
        } else {
            item { ToggleRow("顯示已封存課程", showArchived) { showArchived = it } }
            val list = courses.filter { showArchived || !it.archived }
            if (list.isEmpty()) item { Text("尚無課程，按 ＋ 新增。") }
            items(list, key = { it.id }) { c -> Card(Modifier.fillMaxWidth().clickable { selected = c.id }) { Column(Modifier.padding(16.dp)) {
                Text(c.title + if (c.archived) "（已封存）" else "", color = courseColors[c.color], fontWeight = FontWeight.Bold)
                Text("${c.teacher} · ${meetings.count { it.courseId == c.id }} 個上課時段")
                Text("${tasks.count { it.courseId == c.id && !it.done }} 件未完成事項")
            } } }
        }
    }
}

@Composable fun TaskPage(courses: List<Course>, items: List<AcademicItem>, edit: (AcademicItem) -> Unit, check: (AcademicItem, Boolean) -> Unit, delete: (AcademicItem) -> Unit) {
    var course by rememberSaveable { mutableStateOf("all") }; var kind by rememberSaveable { mutableStateOf("all") }; var status by rememberSaveable { mutableStateOf("all") }
    PageList {
        item { SectionTitle("我的待辦"); Choice("課程", course, listOf("all" to "全部", "" to "未關聯課程") + courses.map { it.id to it.title }) { course = it }
            Choice("類型", kind, listOf("all" to "全部") + itemKinds.map { it to it }) { kind = it }
            Choice("狀態", status, listOf("all" to "全部", "open" to "未完成", "done" to "已完成")) { status = it } }
        val list = items.filter { (course == "all" || it.courseId.orEmpty() == course) && (kind == "all" || it.kind == kind) && (status == "all" || it.done == (status == "done")) }.sortedWith(compareBy<AcademicItem> { it.done }.thenBy { it.date.ifBlank { "9999" } }.thenBy { it.time })
        if (list.isEmpty()) item { Text("沒有符合條件的事項") }
        items(list, key = { it.id }) { AcademicCard(it, courses, { edit(it) }, { value -> check(it, value) }, { delete(it) }) }
    }
}

@Composable fun CourseDialog(old: Course, oldMeetings: List<CourseMeeting>, dismiss: () -> Unit, save: (Course, List<CourseMeeting>) -> Unit) {
    var course by remember(old.id) { mutableStateOf(old) }
    var meetings by remember(old.id) { mutableStateOf(oldMeetings) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("課程與上課時段") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(course.title, { course = course.copy(title = it) }, label = { Text("課程名稱") })
        OutlinedTextField(course.teacher, { course = course.copy(teacher = it) }, label = { Text("教師") })
        OutlinedTextField(course.room, { course = course.copy(room = it) }, label = { Text("預設教室") })
        Choice("顏色", course.color.toString(), listOf("綠", "藍", "紫", "橘", "紅", "青").mapIndexed { i, s -> i.toString() to s }) { course = course.copy(color = it.toInt()) }
        ToggleRow("封存課程（課表不再顯示）", course.archived) { course = course.copy(archived = it) }
        meetings.forEach { m ->
            fun change(next: CourseMeeting) { meetings = meetings.map { if (it.id == next.id) next else it } }
            HorizontalDivider(); Choice("星期", m.day.toString(), weekNames.mapIndexed { i, s -> (i + 1).toString() to s }) { change(m.copy(day = it.toInt())) }
            PickTime("開始", m.start) { change(m.copy(start = it)) }; PickTime("結束", m.end) { change(m.copy(end = it)) }
            OutlinedTextField(m.room, { change(m.copy(room = it)) }, label = { Text("此時段教室（空白使用預設）") })
            TextButton({ meetings = meetings.filter { it.id != m.id } }) { Text("移除此時段") }
        }
        OutlinedButton({ meetings = meetings + CourseMeeting(courseId = course.id, day = LocalDate.now().dayOfWeek.value) }) { Text("新增上課時段") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton({ try { validateCourse(course, meetings); save(course.copy(title = course.title.trim()), meetings) } catch (e: Exception) { error = e.message ?: "資料無效" } }) { Text("儲存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

@Composable fun ItemDialog(old: AcademicItem, courses: List<Course>, dismiss: () -> Unit, save: (AcademicItem) -> Unit) {
    var item by remember(old.id) { mutableStateOf(old) }; var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("課程事項／待辦") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(item.title, { item = item.copy(title = it) }, label = { Text("名稱") })
        Choice("類型", item.kind, itemKinds.map { it to it }) { item = item.copy(kind = it) }
        Choice("課程", item.courseId.orEmpty(), listOf("" to "無（生活待辦）") + courses.map { it.id to it.title }) { item = item.copy(courseId = it.ifBlank { null }) }
        PickDate(item.date, if (item.kind == "考試") "考試日期" else "截止／繳交日期") { item = item.copy(date = it) }
        if (item.date.isNotBlank()) { PickTime("時間（選填）", item.time) { item = item.copy(time = it) }; TextButton({ item = item.copy(date = "", time = "") }) { Text("清除日期") }; if (item.time.isNotBlank()) TextButton({ item = item.copy(time = "") }) { Text("清除時間") } }
        if (item.kind == "報告") {
            PickDate(item.presentationDate, "報告日期（選填）") { item = item.copy(presentationDate = it) }
            if (item.presentationDate.isNotBlank()) { PickTime("報告時間", item.presentationTime) { item = item.copy(presentationTime = it) }; TextButton({ item = item.copy(presentationDate = "", presentationTime = "") }) { Text("清除報告日期") }; if (item.presentationTime.isNotBlank()) TextButton({ item = item.copy(presentationTime = "") }) { Text("清除報告時間") } }
            ToggleRow("分組報告", item.grouped) { item = item.copy(grouped = it) }
            if (item.grouped) OutlinedTextField(item.groupNote, { item = item.copy(groupNote = it) }, label = { Text("組別／成員／分工") })
        }
        ToggleRow("重要事項", item.important) { item = item.copy(important = it) }
        ToggleRow("已完成", item.done) { item = item.copy(done = it) }
        OutlinedTextField(item.note, { item = item.copy(note = it) }, label = { Text("備註") })
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    } }, confirmButton = { TextButton({ try { val saved = if (item.kind == "報告") item else item.copy(presentationDate = "", presentationTime = "", grouped = false, groupNote = ""); validateItem(saved); save(saved.copy(title = saved.title.trim())) } catch (e: Exception) { error = e.message ?: "資料無效" } }) { Text("儲存") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

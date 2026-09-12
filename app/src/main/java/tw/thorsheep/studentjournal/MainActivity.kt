package tw.thorsheep.studentjournal

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { var theme by remember { mutableStateOf(AppOptions.read(this).theme) }; AppTheme(theme) { JournalV3 { theme = it } } } } }

@Composable fun MoneyScreen(rows: List<Entry>, month: String, setMonth: (String)->Unit, edit: (Entry)->Unit, delete: (Entry)->Unit) = Money(rows, month, setMonth, edit, delete)
@Composable fun MoneyEditor(old: Entry, dismiss: ()->Unit, save: (Entry)->Unit) = Editor(old, emptyList(), dismiss, save)
@Composable fun SourceSelector(sources: List<AnnouncementSource>, subscribed: List<String>, add: (AnnouncementSource)->Unit) = AnnouncementSelector(sources, subscribed, add)
@Composable fun PickDate(value: String, label: String, set: (String)->Unit) = DateButton(value, label, set)
@Composable fun PickTime(label: String, value: String, set: (String)->Unit) = TimeButton(label, value, set)
@Composable private fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(primary=Color(0xFF9ACDB4), secondary=Color(0xFFE9B98A), background=Color(0xFF101512), surface=Color(0xFF101512), primaryContainer=Color(0xFF254B3A)) else lightColorScheme(primary=Color(0xFF356B58), secondary=Color(0xFF9B6C3C), background=Color(0xFFF6F8F3), surface=Color(0xFFF6F8F3), primaryContainer=Color(0xFFD8EBDC))
    MaterialTheme(colorScheme = colors, content = content)
}
private fun money(c:Long)="NT$ "+"%,.2f".format(java.util.Locale.US,c/100.0)
private val weekdays=listOf("一","二","三","四","五","六","日")
private val categories=listOf("早餐","午餐","晚餐","飲品","點心","酒類","交通","購物","娛樂","日用品","房租","醫療","社交","禮物","數位")

@Composable private fun ListScreen(content:LazyListScope.()->Unit)=LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp,12.dp,20.dp,96.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
@Composable private fun Money(rows:List<Entry>,month:String,setMonth:(String)->Unit,edit:(Entry)->Unit,delete:(Entry)->Unit)=ListScreen{item{Row(Modifier.fillMaxWidth(),Arrangement.SpaceBetween,Alignment.CenterVertically){TextButton({setMonth(YearMonth.parse(month).minusMonths(1).toString())}){Text("上個月")};Text(month,style=MaterialTheme.typography.titleLarge);TextButton({setMonth(YearMonth.parse(month).plusMonths(1).toString())}){Text("下個月")}}};val list=rows.filter{it.type=="money"&&it.date.startsWith(month)}.sortedByDescending{it.date};item{Text("收入 ${money(list.filter{it.direction=="收入"}.sumOf{it.cents})}　支出 ${money(list.filter{it.direction=="支出"}.sumOf{it.cents})}")};if(list.isEmpty())item{Empty("這個月還沒有紀錄","點右下角 ＋ 記下第一筆收支。")}else items(list){EntryCard(it,rows,{edit(it)},{delete(it)})}}
@Composable private fun AnnouncementSelector(sources:List<AnnouncementSource>,subscribed:List<String>,add:(AnnouncementSource)->Unit){var school by rememberSaveable{mutableStateOf("")};var group by rememberSaveable{mutableStateOf("")};var schoolOpen by remember{mutableStateOf(false)};var groupOpen by remember{mutableStateOf(false)};var unitOpen by remember{mutableStateOf(false)};val schools=sources.map{it.school}.distinct();val groups=sources.filter{it.school==school}.map{it.group}.distinct();val available=sources.filter{it.school==school&&it.group==group&&it.id !in subscribed&&!subscribed.any{id->id.startsWith("${it.id}#")}};Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Box{OutlinedButton({schoolOpen=true},Modifier.fillMaxWidth()){Text(school.ifBlank{"選擇學校"})};DropdownMenu(schoolOpen,{schoolOpen=false}){schools.forEach{option->DropdownMenuItem({Text(option)},{school=option;group="";schoolOpen=false})}}};if(school.isNotBlank())Box{OutlinedButton({groupOpen=true},Modifier.fillMaxWidth()){Text(group.ifBlank{"選擇全校公告、行政或教學單位"})};DropdownMenu(groupOpen,{groupOpen=false}){groups.forEach{option->DropdownMenuItem({Text(option)},{group=option;groupOpen=false})}}};if(group.isNotBlank())Box{OutlinedButton({unitOpen=true},Modifier.fillMaxWidth()){Text("新增單位")};DropdownMenu(unitOpen,{unitOpen=false}){if(available.isEmpty())DropdownMenuItem({Text("此類別已全部訂閱")},{},enabled=false)else available.forEach{s->DropdownMenuItem({Text(listOf(s.parent,s.name).filter{it.isNotBlank()}.joinToString("／"))},{add(s);unitOpen=false})}}}}}
@Composable private fun Heading(text:String)=Text(text,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
@Composable private fun Empty(title:String,detail:String)=Column(Modifier.padding(vertical=20.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable private fun EntryCard(e:Entry,all:List<Entry>,edit:()->Unit,delete:()->Unit,check:((Boolean)->Unit)?=null)=Card(Modifier.fillMaxWidth().clickable(onClick=edit),shape=RoundedCornerShape(18.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){if(check!=null)Checkbox(e.done,check);Column(Modifier.weight(1f)){Text(e.title,fontWeight=FontWeight.SemiBold);when(e.type){"money"->Text("${e.direction} · ${e.note.ifBlank{"未填備註"}} · ${e.date} · ${money(e.cents)}");"course"->Text("${e.start}–${e.end} · ${e.room.ifEmpty{"未填教室"}}");else->{Text(if(e.done)"已完成"else e.date.ifEmpty{"無截止日期"});all.find{it.id==e.courseId}?.let{Text("課程：${it.title}",style=MaterialTheme.typography.bodySmall)}}}};IconButton(delete){Icon(Icons.Outlined.Delete,"刪除")}}}
@Composable private fun Editor(old:Entry,courses:List<Entry>,dismiss:()->Unit,save:(Entry)->Unit){var title by rememberSaveable(old.id){mutableStateOf(old.title)};var date by rememberSaveable(old.id){mutableStateOf(old.date)};var amount by rememberSaveable(old.id){mutableStateOf(if(old.cents>0)BigDecimal.valueOf(old.cents,2).toPlainString()else"")};var direction by rememberSaveable(old.id){mutableStateOf(old.direction)};var category by rememberSaveable(old.id){mutableStateOf(old.category)};var note by rememberSaveable(old.id){mutableStateOf(old.note)};var day by rememberSaveable(old.id){mutableIntStateOf(old.day)};var start by rememberSaveable(old.id){mutableStateOf(old.start)};var end by rememberSaveable(old.id){mutableStateOf(old.end)};var room by rememberSaveable(old.id){mutableStateOf(old.room)};var teacher by rememberSaveable(old.id){mutableStateOf(old.teacher)};var courseId by rememberSaveable(old.id){mutableStateOf(old.courseId)};var err by remember{mutableStateOf("")};AlertDialog(dismiss,title={Text(if(old.title.isEmpty())"新增"else"編輯")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){when(old.type){"money"->{Row{listOf("支出","收入").forEach{FilterChip(direction==it,{direction=it},label={Text(it)},modifier=Modifier.padding(end=4.dp))}};CategoryPicker(category){category=it};OutlinedTextField(amount,{amount=it},label={Text("金額（新台幣）")});DateButton(date.ifBlank{LocalDate.now().toString()}){date=it};OutlinedTextField(note,{note=it},label={Text("備註（選填）")})};"course"->{OutlinedTextField(title,{title=it},label={Text("課程名稱")});Row{weekdays.forEachIndexed{i,t->FilterChip(day==i+1,{day=i+1},label={Text(t)},modifier=Modifier.padding(end=2.dp))}};TimeButton("開始時間",start){start=it};TimeButton("結束時間",end){end=it};OutlinedTextField(room,{room=it},label={Text("教室（選填）")});OutlinedTextField(teacher,{teacher=it},label={Text("教師（選填）")})};else->{OutlinedTextField(title,{title=it},label={Text("待辦名稱")});DateButton(date.ifBlank{LocalDate.now().toString()},"截止日期"){date=it};OutlinedTextField(note,{note=it},label={Text("備註（選填）")})}};if(err.isNotBlank())Text(err,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton({try{val e=old.copy(title=if(old.type=="money")category else title.trim(),date=date,cents=if(old.type=="money")amount.toBigDecimal().movePointRight(2).longValueExact()else 0,direction=direction,category=category,note=note,day=day,start=start,end=end,room=room,teacher=teacher,courseId=courseId);Backup.validate(e);save(e)}catch(e:Exception){err=e.message?:"請檢查輸入"}}){Text("儲存")}},dismissButton={TextButton(dismiss){Text("取消")}})}
@Composable private fun CategoryPicker(value:String,set:(String)->Unit){var open by remember{mutableStateOf(false)};Box{OutlinedButton({open=true}){Text(value.ifBlank{"選擇分類"})};DropdownMenu(open,{open=false}){categories.forEach{c->DropdownMenuItem({Text(c)},{set(c);open=false})}}}}
@Composable private fun DateButton(value:String,label:String="日期",set:(String)->Unit){val ctx=LocalContext.current;OutlinedButton({val d=runCatching{LocalDate.parse(value)}.getOrElse{LocalDate.now()};DatePickerDialog(ctx,{_,y,m,day->set("%04d-%02d-%02d".format(y,m+1,day))},d.year,d.monthValue-1,d.dayOfMonth).show()}){Text("$label：$value")}}
@Composable private fun TimeButton(label:String,value:String,set:(String)->Unit){val ctx=LocalContext.current;OutlinedButton({val t=runCatching{LocalTime.parse(value)}.getOrElse{LocalTime.of(9,0)};TimePickerDialog(ctx,{_,h,m->set("%02d:%02d".format(h,m))},t.hour,t.minute,true).show()}){Text("$label：$value")}}

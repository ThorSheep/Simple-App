package tw.thorsheep.studentjournal

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity(tableName = "courses")
data class Course(@PrimaryKey val id: String = UUID.randomUUID().toString(), val title: String,
    val teacher: String = "", val room: String = "", val color: Int = 0, val archived: Boolean = false)

@Entity(tableName = "course_meetings", foreignKeys = [ForeignKey(entity = Course::class,
    parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE)], indices = [Index("courseId")])
data class CourseMeeting(@PrimaryKey val id: String = UUID.randomUUID().toString(), val courseId: String,
    val day: Int = 1, val start: String = "09:00", val end: String = "10:00", val room: String = "")

@Entity(tableName = "academic_items", foreignKeys = [ForeignKey(entity = Course::class,
    parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.SET_NULL)], indices = [Index("courseId")])
data class AcademicItem(@PrimaryKey val id: String = UUID.randomUUID().toString(), val title: String,
    val courseId: String? = null, val kind: String = "其他", val date: String = "", val time: String = "",
    val presentationDate: String = "", val presentationTime: String = "", val grouped: Boolean = false,
    val groupNote: String = "", val note: String = "", val done: Boolean = false, val important: Boolean = false)

val itemKinds = listOf("作業", "報告", "考試", "其他")

fun validateCourse(course: Course, meetings: List<CourseMeeting>) {
    require(course.id.isNotBlank() && course.id.length <= 100 && course.title.isNotBlank() && course.title.length <= 200) { "請填寫課程名稱（最多 200 字）" }
    require(course.teacher.length <= 2000 && course.room.length <= 2000 && course.color in 0..5) { "課程欄位無效" }
    meetings.forEach {
        require(it.id.isNotBlank() && it.id.length <= 100 && it.courseId == course.id && it.day in 1..7 && it.room.length <= 2000) { "上課時段無效" }
        require(LocalTime.parse(it.start).toString() == it.start && LocalTime.parse(it.end).toString() == it.end && LocalTime.parse(it.start) < LocalTime.parse(it.end)) { "結束時間須晚於開始時間" }
    }
    require(meetings.map { it.id }.distinct().size == meetings.size) { "重複上課時段" }
}

fun validateItem(item: AcademicItem) {
    require(item.id.isNotBlank() && item.id.length <= 100 && item.title.isNotBlank() && item.title.length <= 200 && item.kind in itemKinds) { "請填寫事項名稱及類型" }
    require(item.note.length <= 2000 && item.groupNote.length <= 2000) { "備註最多 2000 字" }
    listOf(item.date, item.presentationDate).filter { it.isNotBlank() }.forEach { require(LocalDate.parse(it).toString() == it) { "日期無效" } }
    listOf(item.time, item.presentationTime).filter { it.isNotBlank() }.forEach { require(LocalTime.parse(it).toString() == it) { "時間無效" } }
    require(item.time.isEmpty() || item.date.isNotEmpty()) { "請先選擇日期" }
    require(item.presentationTime.isEmpty() || item.presentationDate.isNotEmpty()) { "請先選擇報告日期" }
}

@Dao
interface AcademicDao {
    @Query("SELECT * FROM courses ORDER BY title") fun courses(): Flow<List<Course>>
    @Query("SELECT * FROM course_meetings ORDER BY day, start") fun meetings(): Flow<List<CourseMeeting>>
    @Query("SELECT * FROM academic_items") fun items(): Flow<List<AcademicItem>>
    @Query("SELECT * FROM courses") suspend fun allCourses(): List<Course>
    @Query("SELECT * FROM course_meetings") suspend fun allMeetings(): List<CourseMeeting>
    @Query("SELECT * FROM academic_items") suspend fun allItems(): List<AcademicItem>
    @Upsert suspend fun saveCourse(course: Course)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMeetings(meetings: List<CourseMeeting>)
    @Upsert suspend fun saveItem(item: AcademicItem)
    @Query("DELETE FROM course_meetings WHERE courseId = :id") suspend fun clearMeetings(id: String)
    @Query("DELETE FROM course_meetings WHERE id = :id") suspend fun deleteMeeting(id: String)
    @Query("DELETE FROM courses WHERE id = :id") suspend fun deleteCourse(id: String)
    @Query("DELETE FROM academic_items WHERE id = :id") suspend fun deleteItem(id: String)
    @Query("DELETE FROM academic_items") suspend fun clearItems()
    @Query("DELETE FROM courses") suspend fun clearCourses()
    @Transaction suspend fun save(course: Course, meetings: List<CourseMeeting>) {
        validateCourse(course, meetings)
        saveCourse(course); clearMeetings(course.id); insertMeetings(meetings)
    }
}

val MIGRATION_3_5 = object : Migration(3, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE courses (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, teacher TEXT NOT NULL, room TEXT NOT NULL, color INTEGER NOT NULL, archived INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE course_meetings (id TEXT NOT NULL PRIMARY KEY, courseId TEXT NOT NULL, day INTEGER NOT NULL, start TEXT NOT NULL, end TEXT NOT NULL, room TEXT NOT NULL, FOREIGN KEY(courseId) REFERENCES courses(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_course_meetings_courseId ON course_meetings(courseId)")
        db.execSQL("CREATE TABLE academic_items (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, courseId TEXT, kind TEXT NOT NULL, date TEXT NOT NULL, time TEXT NOT NULL, presentationDate TEXT NOT NULL, presentationTime TEXT NOT NULL, grouped INTEGER NOT NULL, groupNote TEXT NOT NULL, note TEXT NOT NULL, done INTEGER NOT NULL, important INTEGER NOT NULL, FOREIGN KEY(courseId) REFERENCES courses(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
        db.execSQL("CREATE INDEX index_academic_items_courseId ON academic_items(courseId)")
        db.execSQL("CREATE TABLE announcement_keywords (id TEXT NOT NULL PRIMARY KEY, text TEXT NOT NULL, enabled INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE seen_announcements (url TEXT NOT NULL PRIMARY KEY)")
        // Preserve each old course ID so task links remain valid; do not merge courses by name.
        db.execSQL("INSERT INTO courses SELECT id,title,teacher,room,0,0 FROM entries WHERE type = 'course'")
        db.execSQL("INSERT INTO course_meetings SELECT id,id,day,start,end,room FROM entries WHERE type = 'course'")
        db.execSQL("INSERT INTO academic_items SELECT id,title,CASE WHEN courseId IN (SELECT id FROM courses) THEN courseId ELSE NULL END,'其他',date,'','','',0,'',note,done,0 FROM entries WHERE type = 'task'")
        db.execSQL("INSERT OR IGNORE INTO seen_announcements SELECT url FROM announcements")
        db.execSQL("DELETE FROM entries WHERE type IN ('course','task')")
    }
}

// The discarded local prototype already used database version 4. Give the rebuilt
// schema its own version so a device that ran that prototype can upgrade safely.
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("courses", "course_meetings", "academic_items", "announcement_keywords").forEach {
            db.execSQL("ALTER TABLE $it RENAME TO prototype_$it")
        }
        db.execSQL("DROP INDEX IF EXISTS index_course_meetings_courseId")
        db.execSQL("DROP INDEX IF EXISTS index_academic_items_courseId")
        db.execSQL("DROP INDEX IF EXISTS index_academic_items_dueDate")
        // Prototype retained stale Entry copies; its dedicated tables are authoritative.
        db.execSQL("DELETE FROM entries WHERE type IN ('course','task')")
        MIGRATION_3_5.migrate(db)
        db.execSQL("INSERT INTO courses SELECT id,title,teacher,'',CASE WHEN color BETWEEN 0 AND 5 THEN color ELSE 0 END,archived FROM prototype_courses")
        db.execSQL("INSERT INTO course_meetings SELECT id,courseId,day,start,end,room FROM prototype_course_meetings WHERE courseId IN (SELECT id FROM courses)")
        db.execSQL("INSERT INTO academic_items SELECT id,title,CASE WHEN courseId IN (SELECT id FROM courses) THEN courseId ELSE NULL END,CASE WHEN type IN ('作業','報告','考試','其他') THEN type ELSE '其他' END,dueDate,'',presentationDate,'',groupWork,groupNote,note,done,0 FROM prototype_academic_items")
        db.execSQL("INSERT INTO announcement_keywords SELECT lower(hex(randomblob(16))),text,enabled FROM prototype_announcement_keywords")
        listOf("course_meetings", "academic_items", "courses", "announcement_keywords").forEach { db.execSQL("DROP TABLE prototype_$it") }
    }
}

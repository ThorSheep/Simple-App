package tw.thorsheep.studentjournal
import org.junit.Test
import org.junit.Assert.*

class BackupTest {
    private val course = Entry(id = "course1", type = "course", title = "微積分", day = 2, start = "09:00", end = "10:00", room = "A101")
    @Test fun roundTripAllTypes() {
        val rows = listOf(course, Entry(type = "money", title = "午餐", date = "2026-09-08", cents = 12550, category = "午餐", note = "和同學", direction = "支出"), Entry(type = "task", title = "作業", courseId = course.id, done = true))
        assertEquals(rows, Backup.decode(Backup.encode(rows)))
    }
    @Test fun emptyBackupIsValid() { assertEquals(emptyList<Entry>(), Backup.decode(Backup.encode(emptyList()))) }
    @Test fun rejectsDuplicateIds() { assertThrows(IllegalArgumentException::class.java) { Backup.decode(Backup.encode(listOf(course, course))) } }
    @Test fun rejectsBrokenCourseLink() {
        assertThrows(IllegalArgumentException::class.java) { Backup.decode(Backup.encode(listOf(Entry(type = "task", title = "作業", courseId = "missing")))) }
    }
    @Test fun rejectsUnsupportedVersion() { assertThrows(IllegalArgumentException::class.java) { Backup.decode(Backup.encode(emptyList()).replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")) } }
    @Test fun rejectsInvalidMoney() {
        assertThrows(IllegalArgumentException::class.java) { Backup.validate(Entry(type = "money", title = "午餐", date = "2026-09-08", category = "午餐", cents = -1)) }
    }
    @Test fun rejectsInvalidTimeRange() { assertThrows(IllegalArgumentException::class.java) { Backup.validate(course.copy(end = "08:00")) } }
    @Test fun rejectsInvalidDate() { assertThrows(java.time.format.DateTimeParseException::class.java) { Backup.validate(Entry(type = "task", title = "考試", date = "2026-02-30")) } }
    @Test fun rejectsOversizedFile() { assertThrows(IllegalArgumentException::class.java) { Backup.decode(" ".repeat(Backup.MAX_BYTES + 1)) } }
}

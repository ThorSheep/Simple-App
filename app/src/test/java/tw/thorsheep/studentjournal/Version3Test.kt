package tw.thorsheep.studentjournal

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class Version3Test {
    private val course = Course(id = "course1", title = "軟體工程", color = 2)
    private val meeting = CourseMeeting(id = "m1", courseId = course.id, day = 4)
    private val report = AcademicItem(id = "report1", title = "期末報告", courseId = course.id, kind = "報告", date = "2026-09-10", time = "23:59", presentationDate = "2026-09-11", presentationTime = "10:00", grouped = true, groupNote = "第二組", important = true)
    private fun archive() = ArchiveV4(emptyList(), listOf(course), listOf(meeting), listOf(report), listOf(Subscription("source#活動")), listOf(AnnouncementKeyword(id = "word1", text = "獎學金")), AppOptions(quick = listOf("course", "agenda"), intervalHours = 6, notify = true))
    @Test fun roundTripReportAndSettings() { assertEquals(archive(), BackupV4.decode(BackupV4.encode(archive()))) }
    @Test fun legacyCoursesKeepLinksAndMultipleRows() {
        val old = listOf(Entry(id = "a", type = "course", title = "同名課", start = "09:00", end = "10:00"), Entry(id = "b", type = "course", title = "同名課", start = "10:00", end = "11:00"), Entry(type = "task", title = "作業", courseId = "b"))
        val converted = BackupV4.decode(Backup.encode(old))
        assertEquals(2, converted.courses.size); assertEquals("b", converted.items.single().courseId)
        assertEquals(listOf("a", "b"), converted.meetings.map { it.courseId })
    }
    @Test fun rejectsMissingCourse() { assertThrows(IllegalArgumentException::class.java) { BackupV4.decode(BackupV4.encode(archive().copy(courses = emptyList()))) } }
    @Test fun rejectsDuplicateMeeting() { assertThrows(IllegalArgumentException::class.java) { BackupV4.decode(BackupV4.encode(archive().copy(meetings = listOf(meeting, meeting)))) } }
    @Test fun rejectsInvalidTimeAndDate() {
        assertThrows(Exception::class.java) { validateItem(report.copy(date = "2026-02-30")) }
        assertThrows(IllegalArgumentException::class.java) { validateItem(report.copy(date = "", time = "10:00")) }
        assertThrows(IllegalArgumentException::class.java) { validateCourse(course, listOf(meeting.copy(end = "08:00"))) }
    }
    @Test fun agendaIncludesBothReportDatesAndExcludesArchivedMeetings() {
        val list = localAgenda(listOf(course.copy(archived = true)), listOf(meeting), listOf(report), LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-11"))
        assertEquals(2, list.size); assertTrue(list[0].title.endsWith("截止／繳交")); assertTrue(list[1].title.endsWith("報告"))
    }
    @Test fun completedTasksDisappearFromAgenda() { assertTrue(localAgenda(emptyList(), emptyList(), listOf(report.copy(done = true)), LocalDate.parse("2026-09-10"), LocalDate.parse("2026-09-11")).isEmpty()) }
    @Test fun keywordsIgnoreCaseAndDisabledWords() {
        val a = Announcement("id", "s", "Android 獎學金", "2026-09-10", "https://example.org/a", "活動")
        assertEquals(listOf("android", "活動"), keywordMatches(a, null, listOf(AnnouncementKeyword(text = "android"), AnnouncementKeyword(text = "活動"), AnnouncementKeyword(text = "獎學金", enabled = false))))
    }
    @Test fun invalidOptionsRejected() { assertThrows(IllegalArgumentException::class.java) { AppOptions(intervalHours = 1).validate() } }
}

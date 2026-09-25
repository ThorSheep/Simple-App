package tw.thorsheep.studentjournal

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncAcademicPayloadTest {
    @Test fun roundTripsAcademicRecords() {
        val course = Course("course-1", "資料結構", "老師", "A101", 2, true)
        val meeting = CourseMeeting("meeting-1", course.id, 2, "09:00", "10:00", "A101")
        val item = AcademicItem("item-1", "作業", course.id, "作業", "2026-09-23", "23:59", "", "", false, "", "", false, true)
        assertEquals(course, SyncAcademicPayload.decodeCourse(SyncAcademicPayload.course(course)))
        assertEquals(meeting, SyncAcademicPayload.decodeMeeting(SyncAcademicPayload.meeting(meeting)))
        assertEquals(item, SyncAcademicPayload.decodeItem(SyncAcademicPayload.item(item)))
    }
}

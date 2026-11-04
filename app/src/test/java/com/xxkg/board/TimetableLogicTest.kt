package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLogicTest {

    private val term = Term("2026-2027 第1学期", "2026-09-07", 20)

    @Test
    fun `第9周日期范围正确`() {
        val date = TimetableLogic.parseDate("2026-11-04")
        assertEquals(9, TimetableLogic.weekNumber(term, date))
        assertEquals("2026-11-02", TimetableLogic.formatDate(TimetableLogic.weekStart(term, 9)))
        assertEquals("11月2日 – 11月8日", TimetableLogic.weekRangeText(term, 9))
    }

    @Test
    fun `mondayOf 和 dayOfWeek`() {
        val date = TimetableLogic.parseDate("2026-11-04")
        assertEquals("2026-11-02", TimetableLogic.formatDate(TimetableLogic.mondayOf(date)))
        assertEquals(3, TimetableLogic.dayOfWeek(date))
        assertEquals("周三", TimetableLogic.weekday(3))
    }

    @Test
    fun `跨月显示`() {
        val start = TimetableLogic.weekStart(term, 8)
        assertEquals("10/11月", TimetableLogic.monthLabel(start))
        assertEquals("11月", TimetableLogic.monthLabel(TimetableLogic.weekStart(term, 9)))
    }

    @Test
    fun `周次文案压缩`() {
        val weeks = (1..8).toSet() + (10..16).toSet()
        assertEquals("第1-8、10-16周", TimetableLogic.weeksText(weeks))
        assertEquals("第1、3、5周（单周）", TimetableLogic.weeksText(setOf(1, 3, 5)))
        assertEquals("第2、4、6周（双周）", TimetableLogic.weeksText(setOf(2, 4, 6)))
    }

    @Test
    fun `节次时间与当前节`() {
        val periods = TimetableDefaults.defaultPeriods()
        assertEquals(13, periods.size)
        assertEquals("10:00–12:35", TimetableLogic.timeRange(periods, 3, 5))
        assertEquals("第3-5节", TimetableLogic.periodText(3, 5))
        val p = TimetableLogic.currentPeriod(periods, 10 * 60 + 10)
        assertNotNull(p)
        assertEquals(3, p?.no)
        val progress = TimetableLogic.progressInPeriod(p!!, 10 * 60 + 10)
        assertTrue(progress > 0.2f && progress < 0.3f)
        assertNull(TimetableLogic.currentPeriod(periods, 9 * 60 + 45))
    }

    @Test
    fun `未排课数量与删除课程`() {
        val data = TimetableData()
        val c1 = TtCourse(id = "c1", name = "大学物理")
        val c2 = TtCourse(id = "c2", name = "待安排")
        data.courses.add(c1)
        data.courses.add(c2)
        data.segments.add(
            Segment(
                id = "s1", courseId = "c1", day = 4,
                startPeriod = 3, endPeriod = 5,
                weeks = (1..16).toMutableSet(),
            )
        )
        data.unscheduled.add(Unscheduled(id = "u1", name = "马原实践", weeks = mutableSetOf(8, 9, 10)))
        assertEquals(2, TimetableLogic.unscheduledCount(data))
        TimetableLogic.deleteCourse(data, "c1")
        assertEquals(1, data.courses.size)
        assertTrue(data.segments.isEmpty())
    }

    @Test
    fun `默认13节时间`() {
        val periods = TimetableDefaults.defaultPeriods()
        assertEquals("08:00", periods.first().start)
        assertEquals("21:00", periods.last().end)
        assertEquals(Section.MORNING, periods.first().section)
        assertEquals(Section.EVENING, periods.last().section)
    }
}

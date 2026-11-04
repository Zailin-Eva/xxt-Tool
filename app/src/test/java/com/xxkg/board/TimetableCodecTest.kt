package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableCodecTest {

    @Test
    fun `JSON 往返保留课程时间段和未排课`() {
        val data = TimetableData(
            term = Term("2026-2027 第1学期", "2026-09-07", 20),
            periods = TimetableDefaults.defaultPeriods(),
        )
        val course = TtCourse(id = "c1", name = "大学物理", teacher = "王悦", credits = "3.0")
        data.courses.add(course)
        data.segments.add(
            Segment(
                id = "s1",
                courseId = "c1",
                day = 4,
                startPeriod = 3,
                endPeriod = 5,
                weeks = (1..12).toMutableSet(),
                campus = "沙河校区",
                location = "沙河二教306",
                teacher = "王悦",
                hidden = false,
            )
        )
        data.segments.add(
            Segment(
                id = "s2",
                courseId = "c1",
                day = 4,
                startPeriod = 3,
                endPeriod = 5,
                weeks = (13..16).toMutableSet(),
                campus = "沙河校区",
                location = "学院楼6号楼204实验室",
                teacher = "陈志新",
                hidden = true,
            )
        )
        data.unscheduled.add(
            Unscheduled(
                id = "u1",
                name = "马克思主义基本原理（实践）",
                teacher = "司晓静",
                weeks = mutableSetOf(8, 9, 10),
                note = "其他课程",
            )
        )

        val raw = TimetableCodec.encode(data)
        val decoded = TimetableCodec.decode(raw)
        assertNotNull(decoded)
        decoded!!
        assertEquals("2026-2027 第1学期", decoded.term.name)
        assertEquals(20, decoded.term.totalWeeks)
        assertEquals(13, decoded.periods.size)
        assertEquals(1, decoded.courses.size)
        assertEquals("大学物理", decoded.courses[0].name)
        assertEquals(2, decoded.segments.size)
        assertEquals(4, decoded.segments[0].day)
        assertEquals((1..12).toSet(), decoded.segments[0].weeks)
        assertTrue(decoded.segments[1].hidden)
        assertEquals("学院楼6号楼204实验室", decoded.segments[1].location)
        assertEquals(1, decoded.unscheduled.size)
        assertEquals(setOf(8, 9, 10), decoded.unscheduled[0].weeks)
    }

    @Test
    fun `坏 JSON 返回 null`() {
        assertNull(TimetableCodec.decode("not json"))
        assertNull(TimetableCodec.decode(""))
    }

    @Test
    fun `缺省字段仍能读出课程`() {
        val raw = """{"courses":[{"name":"高等数学"}],"segments":[]}"""
        val decoded = TimetableCodec.decode(raw)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.courses.size)
        assertEquals("高等数学", decoded.courses[0].name)
        assertTrue(decoded.courses[0].id.isNotEmpty())
        assertEquals(13, decoded.periods.size)
    }
}

package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxReaderTest {

    private fun sample(): ByteArray =
        javaClass.getResourceAsStream("/sample_timetable.xlsx")!!.use { it.readBytes() }

    @Test
    fun `读取 xlsx 单元格`() {
        val grid = XlsxReader.read(sample())
        assertTrue(grid.size >= 3)
        assertEquals("课程名称", grid[0][0])
        assertEquals("高等数学", grid[1][0])
        assertEquals("周三", grid[1][2])
        assertEquals("1-16周", grid[1][4])
    }

    @Test
    fun `xlsx 导入为课程和时间段`() {
        val r = TimetableImporter.fromXlsx(sample())
        assertEquals(2, r.courses.size)
        assertEquals(2, r.segments.size)
        val s = r.segments.first { it.courseName == "高等数学" }
        assertEquals(3, s.day)
        assertEquals(3, s.startPeriod)
        assertEquals(4, s.endPeriod)
        assertEquals("教室A103", s.location)
    }
}

package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImporterTest {

    private fun headerRuns(): List<PdfTimetableImporter.Run> {
        val names = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")
        return names.mapIndexed { i, n ->
            PdfTimetableImporter.Run(page = 1, x = 50f + i * 100f, y = 10f, w = 40f, text = n)
        }
    }

    @Test
    fun `解析单门课`() {
        val runs = headerRuns() + listOf(
            PdfTimetableImporter.Run(1, 60f, 30f, 60f, "高等数学★"),
            PdfTimetableImporter.Run(1, 60f, 42f, 80f, "(3-4节)1-16周/校区:主校区/场地:教学楼A101/教师:李老师"),
            PdfTimetableImporter.Run(1, 60f, 54f, 20f, "学分:3.0"),
        )
        val r = PdfTimetableImporter.fromRuns(runs, "test.pdf")
        assertEquals(1, r.segments.size)
        val s = r.segments[0]
        assertEquals("高等数学", s.courseName)
        assertEquals(1, s.day)
        assertEquals(3, s.startPeriod)
        assertEquals(4, s.endPeriod)
        assertEquals((1..16).toSet(), s.weeks)
        assertEquals("主校区", s.campus)
        assertEquals("教学楼A101", s.location)
        assertEquals("李老师", s.teacher)
    }

    @Test
    fun `同一门课多个时间段`() {
        val runs = headerRuns() + listOf(
            PdfTimetableImporter.Run(1, 60f, 30f, 60f, "大学物理"),
            PdfTimetableImporter.Run(1, 60f, 42f, 80f, "(3-5节)1-12周/校区:主校区/场地:二教306/教师:王老师"),
            PdfTimetableImporter.Run(1, 60f, 54f, 40f, "学分:3.0"),
            PdfTimetableImporter.Run(1, 60f, 66f, 80f, "(3-5节)13-16周/校区:主校区/场地:实验楼204/教师:陈老师"),
            PdfTimetableImporter.Run(1, 60f, 78f, 20f, "学分:3.0"),
        )
        val r = PdfTimetableImporter.fromRuns(runs, "test.pdf")
        assertEquals(2, r.segments.size)
        assertEquals("大学物理", r.segments[0].courseName)
        assertEquals("二教306", r.segments[0].location)
        assertEquals("大学物理", r.segments[1].courseName)
        assertEquals("实验楼204", r.segments[1].location)
        assertEquals((13..16).toSet(), r.segments[1].weeks)
    }

    @Test
    fun `没有表头时提示可能是扫描件`() {
        val runs = listOf(
            PdfTimetableImporter.Run(1, 60f, 30f, 60f, "高等数学"),
            PdfTimetableImporter.Run(1, 60f, 42f, 80f, "(3-4节)1-16周/场地:A101"),
        )
        val r = PdfTimetableImporter.fromRuns(runs, "scan.pdf")
        assertTrue(r.segments.isEmpty())
        assertTrue(r.warnings.any { it.contains("扫描件") })
    }
}

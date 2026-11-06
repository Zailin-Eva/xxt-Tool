package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableImporterTest {

    @Test
    fun `解析教务系统课程块`() {
        val warnings = ArrayList<String>()
        val result = TimetableImporter.parseCellBlocks(
            "高等数学★\n(3-5节)1-12周/校区:主校区/场地:教学楼A101/教师:李老师",
            4, 3, warnings
        )
        assertEquals(1, result.size)
        val s = result[0]
        assertEquals("高等数学", s.courseName)
        assertEquals(4, s.day)
        assertEquals(3, s.startPeriod)
        assertEquals(5, s.endPeriod)
        assertEquals((1..12).toSet(), s.weeks)
        assertEquals("主校区", s.campus)
        assertEquals("教学楼A101", s.location)
        assertEquals("李老师", s.teacher)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `同一单元格两段课程`() {
        val warnings = ArrayList<String>()
        val result = TimetableImporter.parseCellBlocks(
            "高等数学★(3-5节)1-12周/校区:主校区/场地:教学楼A101/教师:李老师" +
                "(3-5节)13-16周/校区:主校区/场地:实验楼C301/教师:刘老师",
            4, 3, warnings
        )
        assertEquals(2, result.size)
        assertEquals((1..12).toSet(), result[0].weeks)
        assertEquals((13..16).toSet(), result[1].weeks)
        assertEquals("实验楼C301", result[1].location)
    }

    @Test
    fun `周次表达式`() {
        assertEquals((1..7).toSet() + (11..16).toSet(), TimetableImporter.parseWeeks("1-7周,11-16周"))
        assertEquals(
            setOf(1, 2, 4, 6, 8, 10, 12, 14, 15, 16),
            TimetableImporter.parseWeeks("1-2周,4-14周(双),15-16周")
        )
        assertEquals(setOf(3, 5, 7, 9, 11, 13), TimetableImporter.parseWeeks("3-13周(单)"))
    }

    @Test
    fun `CSV 列表型导入`() {
        val csv = """
            课程名称,教师,星期,节次,周次,地点
            高等数学,李老师,周三,3-4节,1-16周,教室A103
            线性代数,王老师,周五,5-6节,1-8周,教室A104
        """.trimIndent()
        val r = TimetableImporter.fromCsv(csv)
        assertEquals(2, r.courses.size)
        assertEquals(2, r.segments.size)
        val s = r.segments.first { it.courseName == "高等数学" }
        assertEquals(3, s.day)
        assertEquals(3, s.startPeriod)
        assertEquals(4, s.endPeriod)
        assertEquals("教室A103", s.location)
        assertEquals("李老师", s.teacher)
    }

    @Test
    fun `HTML 网格型导入`() {
        val html = """
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr><td>3</td><td>高等数学<br>(3-5节)1-12周/校区:主校区/场地:教学楼A101/教师:李老师</td><td></td><td></td></tr>
            </table>
        """.trimIndent()
        val r = TimetableImporter.fromHtml(html)
        assertEquals(1, r.courses.size)
        assertEquals(1, r.segments.size)
        val s = r.segments[0]
        assertEquals("高等数学", s.courseName)
        assertEquals(1, s.day)
        assertEquals(3, s.startPeriod)
        assertEquals(5, s.endPeriod)
        assertEquals("教学楼A101", s.location)
    }

    @Test
    fun `CSV 引号与逗号`() {
        val grid = TimetableImporter.parseCsv("a,\"b,c\",d\n1,2,3")
        assertEquals(listOf("a", "b,c", "d"), grid[0])
        assertEquals(listOf("1", "2", "3"), grid[1])
    }
}

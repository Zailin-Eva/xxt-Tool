package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ParserTest {

    // 真实页面结构（摘自线上抓包）
    private val workHtml = """
        <html><body><dl class="worksList">
          <dt class="clearfix" data="/work/phone/task-work?taskrefId=57616708&amp;courseId=267388254&amp;classId=155463739&amp;enc_task=abc" status="1">
            <div class="fl workLeft"><h3>  第六章 商业银行的资产负债管理</h3><p>2026-10-27</p></div>
            <span class="fr workRight doWOrk"><b>做作业</b></span>
          </dt>
          <dt class="clearfix" data="/work/phone/task-work?taskrefId=2&amp;courseId=267388254&amp;classId=155463739" status="3">
            <div class="fl workLeft"><h3>第三章 作业</h3><p>2026-10-20</p></div>
          </dt>
          <dt class="clearfix" data="/work/phone/task-work?taskrefId=3&amp;courseId=267388254&amp;classId=155463739" status="4">
            <div class="fl workLeft"><h3>第二章 作业</h3><p>2026-10-13</p></div>
          </dt>
          <dt class="clearfix" data="/work/phone/task-work?taskrefId=4&amp;courseId=267388254&amp;classId=155463739" status="2">
            <div class="fl workLeft"><h3>第一章 作业</h3><p>长期有效</p></div>
          </dt>
        </dl></body></html>
    """.trimIndent()

    private val examHtml = """
        <html><body><table><thead>
          <tr><th>考试名称</th><th>得分</th><th>状态</th><th></th></tr>
        </thead><tbody>
          <tr data="/exam/phone/task-exam?taskrefId=9992092&amp;courseId=260470717&amp;classId=139121478" title="2025-2026(2)综合英语3听力试题D" status="4">
            <td align="left" class="ltd">2025-2026(2)综合英语3听力试题D</td>
            <td align="center">92.0</td>
            <td align="center" class="green">完成</td>
            <td align="center"><span class="go"></span></td>
          </tr>
          <tr data="/exam/phone/task-exam?taskrefId=1&amp;courseId=260470717&amp;classId=139121478" title="期末测试" status="1">
            <td>期末测试</td><td>—</td><td>未开始</td><td></td>
          </tr>
        </tbody></table></body></html>
    """.trimIndent()

    private val workDetailHtml = """
        <html><body><div class="wid750">
          <p>作业 第四章 信息不对称、金融共识机制...</p>
          <p>开始时间：10-29 10:06 截止时间：2027-01-01 00:00</p>
          <p>共包含15道题目，其中单选题10道，多选题5道。</p>
        </div></body></html>
    """.trimIndent()

    private val workDetailDone = """
        <html><body><div class="wid750">
          <p>作业 100 分 共1题，客观题0道，主观题1道</p>
        </div></body></html>
    """.trimIndent()

    private val examDetailHtml = """
        <html><body><div>
          <p>交卷成功 当前时间 2026-11-02 21:42:00</p>
          <p>本次成绩 92 分 姓名：某同学 学号：2025312410</p>
          <p>领取时间： 2026-06-18 14:10 提交时间： 2026-06-18 14:39</p>
          <p>考试用时： 28 分钟 提交方式：学生交卷</p>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `解析作业列表与状态码`() {
        val list = HtmlParsers.parseWorkList(workHtml, "267388254", "155463739")
        assertEquals(4, list.size)
        assertEquals("第六章 商业银行的资产负债管理", list[0].title)
        assertEquals(TaskStatus.TODO, list[0].status)
        assertEquals(TaskStatus.SUBMITTED, list[1].status)
        assertEquals(TaskStatus.GRADED, list[2].status)
        assertEquals(TaskStatus.EXPIRED, list[3].status)
        assertTrue(list[0].url.startsWith("https://mooc1-api.chaoxing.com/work/phone/task-work"))
        assertEquals(TimeFmt.parseDate("2026-10-27"), list[0].publishTime)
        assertEquals(0L, list[3].publishTime) // 「长期有效」不应被当成日期
    }

    @Test
    fun `解析考试列表带分数`() {
        val list = HtmlParsers.parseExamList(examHtml, "260470717", "139121478")
        assertEquals(2, list.size)
        assertEquals("2025-2026(2)综合英语3听力试题D", list[0].title)
        assertEquals(TaskStatus.GRADED, list[0].status)
        assertEquals("92.0", list[0].grade)
        assertEquals(TaskStatus.TODO, list[1].status)
        assertEquals("", list[1].grade)
    }

    @Test
    fun `解析作业详情的时间`() {
        val d = HtmlParsers.parseWorkDetail(workDetailHtml)
        assertTrue(d.start > 0)
        assertTrue(d.end > 0)
        val cal = Calendar.getInstance()
        cal.timeInMillis = d.start
        assertEquals(10, cal.get(Calendar.MONTH) + 1)
        assertEquals(29, cal.get(Calendar.DAY_OF_MONTH))
        cal.timeInMillis = d.end
        assertEquals(2027, cal.get(Calendar.YEAR))
        assertEquals(1, cal.get(Calendar.MONTH) + 1)
    }

    @Test
    fun `解析已完成作业的分数`() {
        val d = HtmlParsers.parseWorkDetail(workDetailDone)
        assertEquals("100", d.score)
    }

    @Test
    fun `解析考试详情的提交时间与成绩`() {
        val d = HtmlParsers.parseExamDetail(examDetailHtml)
        assertEquals(TimeFmt.parseDate("2026-06-18 14:39"), d.submit)
        assertEquals("92", d.score)
        assertEquals(0L, d.start)
    }

    @Test
    fun `相对链接补全域名`() {
        assertEquals(
            "https://mooc1-api.chaoxing.com/a/b",
            HtmlParsers.normalizeUrl("/a/b")
        )
    }

    @Test
    fun `同课程同名作业key稳定`() {
        val k1 = HtmlParsers.keyOf(TaskType.WORK, "111", "作业A")
        val k2 = HtmlParsers.keyOf(TaskType.WORK, "111", "作业A ")
        assertEquals(k1, k2)
    }
}

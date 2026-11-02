package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerUrlTest {

    @Test
    fun `解析 _CP_ 形式的答题地址`() {
        val html = """
            <script>
              var url = location.origin + _CP_ + '/work/doHomeWorkNew?courseId=1&classId=2';
            </script>
        """.trimIndent()

        assertEquals(
            "https://mooc1-api.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=1&classId=2",
            HtmlParsers.extractAnswerUrl(html)
        )
    }

    @Test
    fun `解析 href 形式并还原 HTML 实体`() {
        val html = """
            <a href="/mooc-ans/work/doHomeWorkNew?courseId=1&amp;classId=2">开始答题</a>
        """.trimIndent()

        assertEquals(
            "https://mooc1-api.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=1&classId=2",
            HtmlParsers.extractAnswerUrl(html)
        )
    }

    @Test
    fun `解析只有 _CP_ 的地址`() {
        val html = """<script>var url = _CP_ + '/work/doHomeWorkNew?courseId=3';</script>"""

        assertEquals(
            "https://mooc1-api.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=3",
            HtmlParsers.extractAnswerUrl(html)
        )
    }

    @Test
    fun `没有答题地址时返回 null`() {
        assertNull(HtmlParsers.extractAnswerUrl("<html><body>课程首页</body></html>"))
    }

    @Test
    fun `解析修改作业入口`() {
        val html = """
            <a href="/mooc-ans/work/doHomeWorkNew?courseId=1&amp;classId=2&amp;edit=1">修改作业</a>
        """.trimIndent()

        assertEquals(
            "https://mooc1-api.chaoxing.com/mooc-ans/work/doHomeWorkNew?courseId=1&classId=2&edit=1",
            HtmlParsers.extractModifyUrl(html)
        )
    }

    @Test
    fun `修改作业优先取离文字最近的链接`() {
        val html = """
            <a href="/mooc-ans/work/selectWorkQuestion?view=1">查看作业</a>
            <a href="/mooc-ans/work/doHomeWorkNew?edit=1">修改作业</a>
        """.trimIndent()

        assertEquals(
            "https://mooc1-api.chaoxing.com/mooc-ans/work/doHomeWorkNew?edit=1",
            HtmlParsers.extractModifyUrl(html)
        )
    }
}

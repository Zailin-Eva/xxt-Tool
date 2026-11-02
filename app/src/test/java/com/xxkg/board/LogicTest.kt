package com.xxkg.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    private val now = 1_700_000_000_000L
    private val day = TimeFmt.DAY

    private fun task(
        key: String,
        status: TaskStatus,
        end: Long = 0L,
        pub: Long = 0L,
        stage: Long = 0L,
        type: TaskType = TaskType.WORK,
        course: String = "课程A",
        title: String = key,
    ) = Task(
        key = key, title = title, courseName = course, type = type,
        status = status, endTime = end, publishTime = pub, stageTime = stage
    )

    @Test
    fun `未交且在截止前进入未完成`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.TODO, end = now + day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(1, b.todo.size)
        assertEquals(0, b.expired.size)
    }

    @Test
    fun `未交但已过截止进入已过期`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.TODO, end = now - day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(1, b.expired.size)
        assertEquals(0, b.todo.size)
    }

    @Test
    fun `服务端标记过期直接进已过期`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.EXPIRED, end = now - 30 * day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(1, b.expired.size)
        assertEquals(0, b.todo.size)
    }

    @Test
    fun `已交未批在x日内进入待批阅`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.SUBMITTED, stage = now - 3 * day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(1, b.pending.size)
        assertEquals(0, b.done.size)
    }

    @Test
    fun `已交未批超过x日未超过x加y日进入已完成`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.SUBMITTED, stage = now - 9 * day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(0, b.pending.size)
        assertEquals(1, b.done.size)
    }

    @Test
    fun `已交未批超过x加y日彻底不显示`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.SUBMITTED, stage = now - 15 * day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(0, b.pending.size + b.done.size + b.todo.size + b.expired.size)
    }

    @Test
    fun `已批阅在y日内显示`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.GRADED, stage = now - 2 * day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(1, b.done.size)
    }

    @Test
    fun `已批阅超过y日不显示`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.GRADED, stage = now - 8 * day)),
            7, 7, now, emptySet(), 0
        )
        assertEquals(0, b.done.size)
    }

    @Test
    fun `窗口可调`() {
        val t = task("a", TaskStatus.SUBMITTED, stage = now - 10 * day)
        val narrow = Categorizer.split(listOf(t), 7, 7, now, emptySet(), 0)
        val wide = Categorizer.split(listOf(t), 30, 30, now, emptySet(), 0)
        assertEquals(1, narrow.done.size)
        assertEquals(1, wide.pending.size)
    }

    @Test
    fun `旧学期的历史条目不会挤进待批阅`() {
        // 一年前发布的待批阅，靠发布时间兜底 → 超出窗口，不显示
        val t = task("old", TaskStatus.SUBMITTED, stage = now - 400 * day)
        val b = Categorizer.split(listOf(t), 7, 7, now, emptySet(), 0)
        assertEquals(0, b.pending.size + b.done.size)
    }

    @Test
    fun `手动删除的条目不出现`() {
        val b = Categorizer.split(
            listOf(task("a", TaskStatus.TODO, end = now + day)),
            7, 7, now, setOf("a"), 0
        )
        assertEquals(0, b.todo.size)
    }

    @Test
    fun `未完成默认按截止时间升序`() {
        val b = Categorizer.split(
            listOf(
                task("late", TaskStatus.TODO, end = now + 5 * day, title = "late"),
                task("soon", TaskStatus.TODO, end = now + day, title = "soon"),
                task("none", TaskStatus.TODO, end = 0L, title = "none"),
            ),
            7, 7, now, emptySet(), 0
        )
        assertEquals(listOf("soon", "late", "none"), b.todo.map { it.title })
    }

    @Test
    fun `降序排序`() {
        val b = Categorizer.split(
            listOf(
                task("late", TaskStatus.TODO, end = now + 5 * day, title = "late"),
                task("soon", TaskStatus.TODO, end = now + day, title = "soon"),
            ),
            7, 7, now, emptySet(), 1
        )
        assertEquals(listOf("late", "soon"), b.todo.map { it.title })
    }

    @Test
    fun `剩余时间文案分级`() {
        assertTrue(TimeFmt.remain(now, now + 5 * day).text.startsWith("剩 5天"))
        assertEquals(TimeFmt.C_MUTED, TimeFmt.remain(now, now + 5 * day).color)
        assertEquals(TimeFmt.C_SOON, TimeFmt.remain(now, now + 2 * day).color)
        assertEquals(TimeFmt.C_URGENT, TimeFmt.remain(now, now + 3600_000L).color)
        assertEquals(4, TimeFmt.remain(now, now - 2 * day).level)
    }

    @Test
    fun `日期解析支持多种格式`() {
        val a = TimeFmt.parseDate("2024-03-05 08:30")
        val b = TimeFmt.parseDate("2024/03/05 08:30")
        val c = TimeFmt.parseDate("2024年3月5日 08:30")
        assertTrue(a > 0)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `省略年份的时间能推断当年`() {
        val v = TimeFmt.parseFlexible("10-29 19:22")
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = v
        assertEquals(10, cal.get(java.util.Calendar.MONTH) + 1)
        assertEquals(29, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(19, cal.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `带关键字抓时间`() {
        val v = TimeFmt.grabTime("开始时间：10-29 10:06 截止时间：2027-01-01 00:00", listOf("截止时间"))
        assertEquals(TimeFmt.parseDate("2027-01-01 00:00"), v)
    }
}

package com.xxkg.board

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 周次、日期、节次、文案。全部用 Calendar，保持 minSdk 24 不引 desugaring。 */
object TimetableLogic {

    const val DAY_MS = 86_400_000L
    private const val WEEK_MS = 7 * DAY_MS

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val mdFmt = SimpleDateFormat("M月d日", Locale.CHINA)
    private val slashFmt = SimpleDateFormat("M/d", Locale.CHINA)

    fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            dateFmt.parse(raw.trim())?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun formatDate(ms: Long): String = dateFmt.format(Date(ms))

    fun md(ms: Long): String = mdFmt.format(Date(ms))

    fun slash(ms: Long): String = slashFmt.format(Date(ms))

    fun mondayOf(ms: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val dow = c.get(Calendar.DAY_OF_WEEK)
        val delta = when (dow) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> -1
            Calendar.WEDNESDAY -> -2
            Calendar.THURSDAY -> -3
            Calendar.FRIDAY -> -4
            Calendar.SATURDAY -> -5
            else -> -6
        }
        c.add(Calendar.DAY_OF_MONTH, delta)
        return c.timeInMillis
    }

    fun addDays(ms: Long, days: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ms
        c.add(Calendar.DAY_OF_MONTH, days)
        return c.timeInMillis
    }

    fun weekStart(term: Term, week: Int): Long {
        val start = parseDate(term.week1Monday)
        return if (start <= 0L) 0L else start + (week - 1) * WEEK_MS
    }

    /** 当前日期属于第几周；不在学期内会返回 <=0 或 > totalWeeks。 */
    fun weekNumber(term: Term, date: Long): Int {
        val start = parseDate(term.week1Monday)
        if (start <= 0L) return 0
        val diff = mondayOf(date) - mondayOf(start)
        return Math.floorDiv(diff, WEEK_MS).toInt() + 1
    }

    fun currentWeek(term: Term, now: Long = System.currentTimeMillis()): Int =
        weekNumber(term, now).coerceIn(1, term.totalWeeks.coerceAtLeast(1))

    fun isInTerm(term: Term, now: Long = System.currentTimeMillis()): Boolean {
        val w = weekNumber(term, now)
        return w in 1..term.totalWeeks
    }

    fun monthLabel(start: Long): String {
        if (start <= 0L) return ""
        val c1 = Calendar.getInstance().apply { timeInMillis = start }
        val c2 = Calendar.getInstance().apply { timeInMillis = start + 6 * DAY_MS }
        val m1 = c1.get(Calendar.MONTH) + 1
        val m2 = c2.get(Calendar.MONTH) + 1
        return if (m1 == m2) "${m1}月" else "${m1}/${m2}月"
    }

    fun weekday(day: Int): String =
        arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .getOrElse(day - 1) { "周一" }

    fun weekdayShort(day: Int): String =
        arrayOf("一", "二", "三", "四", "五", "六", "日")
            .getOrElse(day - 1) { "一" }

    fun dayOfWeek(date: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = date
        return when (c.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }
    }

    fun weekRangeText(term: Term, week: Int): String {
        val start = weekStart(term, week)
        if (start <= 0L) return "日期未设置"
        return "${md(start)} – ${md(start + 6 * DAY_MS)}"
    }

    fun weekShortRange(term: Term, week: Int): String {
        val start = weekStart(term, week)
        if (start <= 0L) return ""
        return "${slash(start)}–${slash(start + 6 * DAY_MS)}"
    }

    /** 周次集合 → “第1-8、10-16周”；全奇数/偶数会加单双周提示。 */
    fun weeksText(weeks: Set<Int>): String {
        if (weeks.isEmpty()) return "未设置周次"
        val sorted = weeks.sorted()
        val runs = ArrayList<String>()
        var start = sorted[0]
        var prev = start
        for (w in sorted.drop(1)) {
            if (w == prev + 1) {
                prev = w
            } else {
                runs.add(runText(start, prev))
                start = w
                prev = w
            }
        }
        runs.add(runText(start, prev))
        val suffix = when {
            sorted.size > 1 && sorted.all { it % 2 == 1 } -> "（单周）"
            sorted.size > 1 && sorted.all { it % 2 == 0 } -> "（双周）"
            else -> ""
        }
        return "第" + runs.joinToString("、") + "周" + suffix
    }

    private fun runText(start: Int, end: Int): String =
        if (start == end) "$start" else "$start-$end"

    fun timeRange(periods: List<Period>, startPeriod: Int, endPeriod: Int): String {
        val a = periods.firstOrNull { it.no == startPeriod }?.start
        val b = periods.firstOrNull { it.no == endPeriod }?.end
        return if (a != null && b != null) "$a–$b" else "第${startPeriod}-${endPeriod}节"
    }

    fun periodText(startPeriod: Int, endPeriod: Int): String =
        if (startPeriod == endPeriod) "第${startPeriod}节" else "第${startPeriod}-${endPeriod}节"

    fun minutesOfDay(hhmm: String): Int {
        val parts = hhmm.trim().split(":")
        if (parts.size < 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun currentPeriod(periods: List<Period>, nowMinutes: Int = nowMinutes()): Period? {
        for (p in periods) {
            val a = minutesOfDay(p.start)
            val b = minutesOfDay(p.end)
            if (a >= 0 && b >= a && nowMinutes in a..b) return p
        }
        return null
    }

    /** 当前时间在某一节课中的进度 0..1；不在课内返回 -1。 */
    fun progressInPeriod(period: Period, nowMinutes: Int = nowMinutes()): Float {
        val a = minutesOfDay(period.start)
        val b = minutesOfDay(period.end)
        if (a < 0 || b <= a || nowMinutes !in a..b) return -1f
        return (nowMinutes - a).toFloat() / (b - a).toFloat()
    }

    fun courseName(data: TimetableData, courseId: String): String =
        data.course(courseId)?.name ?: "未知课程"

    /** 详情/网格用：当前周生效的排前面，其余按星期、节次排序。 */
    fun sortedSegments(data: TimetableData, courseId: String, week: Int): List<Segment> =
        data.segmentsOf(courseId)
            .sortedWith(
                compareByDescending<Segment> { it.weeks.contains(week) }
                    .thenBy { it.day }
                    .thenBy { it.startPeriod }
            )

    fun activeInSlot(data: TimetableData, courseId: String, day: Int, start: Int, end: Int, week: Int): Boolean =
        data.segmentsOf(courseId).any {
            !it.hidden && it.weeks.contains(week) &&
                it.day == day && it.startPeriod == start && it.endPeriod == end
        }

    fun unscheduledCount(data: TimetableData): Int {
        val emptyCourses = data.courses.count { c ->
            data.segments.none { it.courseId == c.id }
        }
        return data.unscheduled.size + emptyCourses
    }

    data class Conflict(val courseName: String, val segment: Segment)

    /** 检查同一星期、节次重叠、且有共同周次的已有时间段。 */
    fun conflicts(
        data: TimetableData,
        excludeSegmentId: String?,
        day: Int,
        startPeriod: Int,
        endPeriod: Int,
        weeks: Set<Int>,
    ): List<Conflict> {
        if (weeks.isEmpty() || startPeriod <= 0 || endPeriod < startPeriod) return emptyList()
        val out = ArrayList<Conflict>()
        for (s in data.segments) {
            if (excludeSegmentId != null && s.id == excludeSegmentId) continue
            if (s.day != day) continue
            if (startPeriod > s.endPeriod || endPeriod < s.startPeriod) continue
            if (s.weeks.none { it in weeks }) continue
            out.add(Conflict(courseName(data, s.courseId), s))
        }
        return out.distinctBy { it.segment.id }
    }

    fun conflictMessage(conflicts: List<Conflict>): String {
        if (conflicts.isEmpty()) return ""
        val lines = conflicts.take(5).map {
            "「${it.courseName}」${weekday(it.segment.day)} " +
                "${periodText(it.segment.startPeriod, it.segment.endPeriod)} " +
                "（${weeksText(it.segment.weeks)}）"
        }
        val more = if (conflicts.size > 5) "\n……还有 ${conflicts.size - 5} 条冲突" else ""
        return "与以下课程时间重叠：\n" + lines.joinToString("\n") { "· $it" } + more +
            "\n\n请调整结束节次，或换一个空白格子。"
    }

    fun deleteCourse(data: TimetableData, courseId: String) {
        data.courses.removeAll { it.id == courseId }
        data.segments.removeAll { it.courseId == courseId }
    }

    fun periodByNo(periods: List<Period>, no: Int): Period? =
        periods.firstOrNull { it.no == no }
}


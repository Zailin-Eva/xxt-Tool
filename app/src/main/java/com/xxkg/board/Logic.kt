package com.xxkg.board

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TimeFmt {
    const val DAY = 86_400_000L
    const val HOUR = 3_600_000L
    const val MIN = 60_000L

    val C_MUTED = 0xFF999999.toInt()
    val C_SOON = 0xFFE8890C.toInt()
    val C_URGENT = 0xFFD0021B.toInt()
    val C_EXPIRED = 0xFF8B0000.toInt()
    val C_TEXT = 0xFF111111.toInt()

    fun hmss(ms: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(ms))

    fun dateTime(ms: Long): String {
        if (ms <= 0) return "—"
        val c = Calendar.getInstance()
        val now = Calendar.getInstance()
        c.timeInMillis = ms
        val sameYear = c.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val fmt = if (sameYear) "M月d日 HH:mm" else "yyyy年M月d日 HH:mm"
        return SimpleDateFormat(fmt, Locale.CHINA).format(Date(ms))
    }

    /** 从任意文本里解析出一个日期时间；失败返回 0。 */
    fun parseDate(raw: String?): Long {
        if (raw == null) return 0L
        var s = raw.trim()
        if (s.isEmpty()) return 0L
        s = s.replace("年", "-").replace("月", "-").replace("日", " ")
            .replace("/", "-")
            .replace("：", ":")
            .replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        s = s.replace(Regex("\\s*(?:-\\s*)+"), "-").trim()
        val patterns = listOf("yyyy-M-d H:m:s", "yyyy-M-d H:m", "yyyy-M-d")
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.CHINA)
                f.isLenient = false
                val d = f.parse(s) ?: continue
                return d.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    /**
     * 解析学习通详情页里的时间，支持省略年份的写法（如「10-29 19:22」）。
     * 若推断出的时间比现在晚 60 天以上，按去年算。
     */
    fun parseFlexible(raw: String?): Long {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return 0L
        s = s.replace("年", "-").replace("月", "-").replace("日", " ")
            .replace("/", "-").replace("：", ":").replace(Regex("\\s+"), " ")
        if (Regex("^\\d{4}-").containsMatchIn(s)) return parseDate(s)
        val m = Regex("^(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})").find(s)
        if (m != null) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.MONTH, m.groupValues[1].toInt() - 1)
            cal.set(Calendar.DAY_OF_MONTH, m.groupValues[2].toInt())
            cal.set(Calendar.HOUR_OF_DAY, m.groupValues[3].toInt())
            cal.set(Calendar.MINUTE, m.groupValues[4].toInt())
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            if (cal.timeInMillis > System.currentTimeMillis() + 60L * DAY) {
                cal.add(Calendar.YEAR, -1)
            }
            return cal.timeInMillis
        }
        return parseDate(s)
    }

    /** 抓「关键字：某时间」里的时间，带年份或不带年份都行。 */
    fun grabTime(text: String, keywords: List<String>): Long {
        for (kw in keywords) {
            val re = Regex(
                Regex.escape(kw) +
                    "[:：]?\\s*(\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?\\s*\\d{1,2}:\\d{2}" +
                    "|\\d{1,2}-\\d{1,2}\\s*\\d{1,2}:\\d{2})"
            )
            val m = re.find(text)
            if (m != null) {
                val v = parseFlexible(m.groupValues[1])
                if (v > 0) return v
            }
        }
        return 0L
    }

    /** 从一段文本里按关键字抓取带年份的时间（兼容旧调用）。 */
    fun grabDate(text: String, keywords: List<String>): Long = grabTime(text, keywords)

    data class Remain(val text: String, val color: Int, val level: Int)

    /** level: 0 未知, 1 宽松, 2 临近, 3 紧急, 4 已过期 */
    fun remain(now: Long, end: Long): Remain {
        if (end <= 0L) return Remain("截止未知", C_MUTED, 0)
        val d = end - now
        if (d <= 0L) {
            val days = (-d) / DAY
            return Remain(if (days >= 1) "已过期 ${days} 天" else "已过期", C_EXPIRED, 4)
        }
        val days = d / DAY
        val hours = (d % DAY) / HOUR
        val mins = (d % HOUR) / MIN
        val text = when {
            days >= 1 -> "${days}天${hours}时"
            hours >= 1 -> "${hours}时${mins}分"
            else -> "${mins}分"
        }
        val color = when {
            days >= 3 -> C_MUTED
            days >= 1 -> C_SOON
            else -> C_URGENT
        }
        val level = when {
            days >= 3 -> 1
            days >= 1 -> 2
            else -> 3
        }
        return Remain("剩 $text", color, level)
    }

    /** 顶部大字号倒计时：X 日 x 时 */
    fun bigCountdown(now: Long, end: Long): String {
        if (end <= 0L) return "--"
        val d = end - now
        if (d <= 0L) return "已过期"
        val days = d / DAY
        val hours = (d % DAY) / HOUR
        val mins = (d % HOUR) / MIN
        return if (days > 0) "$days 日 $hours 时" else "$hours 时 $mins 分"
    }

    /** 距某时刻过了多少天（不足一天返回 0） */
    fun daysAgo(now: Long, then: Long): Int {
        if (then <= 0L) return -1
        return ((now - then) / DAY).toInt().coerceAtLeast(0)
    }
}

data class Buckets(
    val todo: List<Task> = emptyList(),
    val pending: List<Task> = emptyList(),
    val done: List<Task> = emptyList(),
    val expired: List<Task> = emptyList(),
)

object Categorizer {

    val SORT_LABELS = listOf(
        "截止时间由近到远",
        "截止时间由远到近",
        "按课程",
        "按类型"
    )

    private fun base(t: Task): Long =
        if (t.stageTime > 0) t.stageTime else if (t.publishTime > 0) t.publishTime else 0L

    fun split(
        all: List<Task>,
        xDays: Int,
        yDays: Int,
        now: Long,
        deleted: Set<String>,
        sortMode: Int,
    ): Buckets {
        val xMs = xDays * TimeFmt.DAY
        val yMs = yDays * TimeFmt.DAY

        val todo = ArrayList<Task>()
        val pending = ArrayList<Task>()
        val done = ArrayList<Task>()
        val expired = ArrayList<Task>()

        for (t in all) {
            if (t.key in deleted) continue
            when (t.status) {
                TaskStatus.EXPIRED -> expired.add(t)

                TaskStatus.TODO -> {
                    if (t.endTime > 0 && t.endTime < now) expired.add(t) else todo.add(t)
                }

                TaskStatus.SUBMITTED -> {
                    val b = base(t)
                    if (b <= 0L) {
                        pending.add(t)
                    } else {
                        val age = now - b
                        when {
                            age <= xMs -> pending.add(t)
                            age <= xMs + yMs -> done.add(t)
                        }
                    }
                }

                TaskStatus.GRADED -> {
                    val b = base(t)
                    if (b <= 0L || now - b <= yMs) done.add(t)
                }
            }
        }

        val todoSorted = when (sortMode) {
            1 -> todo.sortedWith(compareBy<Task> { if (it.endTime == 0L) Long.MIN_VALUE else -it.endTime })
            2 -> todo.sortedWith(compareBy<Task> { it.courseName }.thenBy { it.endTime })
            3 -> todo.sortedWith(compareBy<Task> { it.type.ordinal }.thenBy { it.endTime })
            else -> todo.sortedWith(compareBy<Task> { if (it.endTime == 0L) Long.MAX_VALUE else it.endTime })
        }

        return Buckets(
            todo = todoSorted,
            pending = pending.sortedByDescending { base(it) },
            done = done.sortedByDescending { base(it) },
            expired = expired.sortedByDescending { if (it.endTime > 0) it.endTime else base(it) },
        )
    }
}

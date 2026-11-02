package com.xxkg.board

import org.jsoup.Jsoup

/** 解析学习通移动端 H5 页面（列表页 + 详情页）。 */
object HtmlParsers {

    /** 列表页 status 属性 → 状态。1未交 2已过期 3待批阅 4已完成 */
    fun statusOf(code: Int): TaskStatus = when (code) {
        1 -> TaskStatus.TODO
        2 -> TaskStatus.EXPIRED
        3 -> TaskStatus.SUBMITTED
        4 -> TaskStatus.GRADED
        else -> TaskStatus.TODO
    }

    fun parseWorkList(html: String, courseId: String, classId: String): List<Task> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<Task>()
        for (n in doc.select("dt[data]")) {
            val data = n.attr("data").trim()
            if (data.isEmpty()) continue
            val title = n.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue
            val code = n.attr("status").toIntOrNull()
            val publishText = n.selectFirst("p")?.text()?.trim().orEmpty()
            val action = n.selectFirst("b")?.text()?.trim().orEmpty()
            out.add(
                Task(
                    key = keyOf(TaskType.WORK, courseId, title),
                    title = title,
                    courseId = courseId,
                    classId = classId,
                    type = TaskType.WORK,
                    status = code?.let { statusOf(it) } ?: inferStatus(action),
                    publishTime = TimeFmt.parseFlexible(publishText),
                    url = normalizeUrl(data),
                    statusText = action,
                )
            )
        }
        return out
    }

    fun parseExamList(html: String, courseId: String, classId: String): List<Task> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<Task>()
        for (n in doc.select("tr[data]")) {
            val data = n.attr("data").trim()
            if (data.isEmpty()) continue
            val cells = n.select("td").map { it.text().trim() }
            val title = n.attr("title").trim().ifEmpty { cells.firstOrNull().orEmpty() }
            if (title.isEmpty()) continue
            val code = n.attr("status").toIntOrNull()
            val statusText = cells.getOrElse(2) { "" }
            val score = cells.getOrNull(1)?.trim().orEmpty().let { if (it == "—" || it == "-") "" else it }
            out.add(
                Task(
                    key = keyOf(TaskType.EXAM, courseId, title),
                    title = title,
                    courseId = courseId,
                    classId = classId,
                    type = TaskType.EXAM,
                    status = code?.let { statusOf(it) } ?: inferStatus(statusText),
                    grade = if (code == 4) score else "",
                    url = normalizeUrl(data),
                    statusText = statusText,
                )
            )
        }
        return out
    }

    data class WorkDetail(val start: Long, val end: Long, val score: String)

    /** 作业详情页：开始时间 / 截止时间 / 分数（已完成时才有） */
    fun parseWorkDetail(html: String): WorkDetail {
        val text = Jsoup.parse(html).text()
        val start = TimeFmt.grabTime(text, listOf("开始时间"))
        val end = TimeFmt.grabTime(text, listOf("截止时间", "结束时间"))
        var score = Regex("(?:本次成绩|作业)\\s*(\\d+(?:\\.\\d+)?)\\s*分").find(text)
            ?.groupValues?.get(1)?.trim().orEmpty()
        if (score.isEmpty()) {
            score = Regex("(?:本次成绩|得分|成绩)[:：]?\\s*(\\d+(?:\\.\\d+)?)").find(text)
                ?.groupValues?.get(1)?.trim().orEmpty()
        }
        return WorkDetail(start, end, score)
    }

    data class ExamDetail(val submit: Long, val score: String)

    /** 考试详情页：提交时间 / 成绩 */
    fun parseExamDetail(html: String): ExamDetail {
        val text = Jsoup.parse(html).text()
        val submit = TimeFmt.grabTime(text, listOf("提交时间"))
        val score = Regex("(?:本次成绩|得分|成绩)\\s*(\\d+(?:\\.\\d+)?)\\s*分").find(text)
            ?.groupValues?.get(1)?.trim()
            ?: Regex("本次成绩[:：]?\\s*(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.trim()
            ?: ""
        return ExamDetail(submit, score)
    }

    /** 只有文字没有 status 属性时的兜底判断 */
    fun inferStatus(text: String): TaskStatus = when {
        text.contains("已完成") || text.contains("完成") || text.contains("已批阅") -> TaskStatus.GRADED
        text.contains("待批阅") || text.contains("等待教师批阅") -> TaskStatus.SUBMITTED
        text.contains("过期") -> TaskStatus.EXPIRED
        else -> TaskStatus.TODO
    }

    fun keyOf(type: TaskType, courseId: String, title: String): String =
        "${type.name}|$courseId|${title.trim()}"

    fun normalizeUrl(path: String): String {
        val p = path.trim()
        return when {
            p.startsWith("http://") || p.startsWith("https://") -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> "https://mooc1-api.chaoxing.com$p"
            else -> "https://mooc1-api.chaoxing.com/$p"
        }
    }
}

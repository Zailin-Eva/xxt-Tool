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

    data class ExamDetail(val start: Long, val submit: Long, val score: String)

    /** 考试详情页：开始时间 / 提交时间 / 成绩 */
    fun parseExamDetail(html: String): ExamDetail {
        val text = Jsoup.parse(html).text()
        val start = TimeFmt.grabTime(text, listOf("开始时间", "考试时间"))
        val submit = TimeFmt.grabTime(text, listOf("提交时间"))
        val score = Regex("(?:本次成绩|得分|成绩)\\s*(\\d+(?:\\.\\d+)?)\\s*分").find(text)
            ?.groupValues?.get(1)?.trim()
            ?: Regex("本次成绩[:：]?\\s*(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.trim()
            ?: ""
        return ExamDetail(start, submit, score)
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

    // ------------------------------------------------------------ 答题页地址

    private val CP_URL = Regex(
        """(?:var|let|const)?\s*url\s*=\s*location\.origin\s*\+\s*_CP_\s*\+\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val CP_ONLY = Regex(
        """_CP_\s*\+\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val HREF_ANSWER = Regex(
        """href\s*=\s*["']([^"']*(?:doHomeWorkNew|selectWorkQuestion)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val HREF_MOOC = Regex(
        """href\s*=\s*["']([^"']*mooc-ans[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val GENERIC_URL = Regex(
        """(?:url|href)\s*[=:]\s*['"]([^'"]*(?:doHomeWorkNew|selectWorkQuestion|mooc-ans)[^'"]*)['"]""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从作业/考试详情页 HTML 里提取真正的答题页地址。
     *
     * 学习通常见写法：var url = location.origin + _CP_ + '/work/doHomeWorkNew?...'
     * 此时页面真实地址是 https://mooc1-api.chaoxing.com/mooc-ans + path，
     * 直接加载详情页往往只会停在“课程首页/作业列表”，所以要先解析再打开。
     */
    fun extractAnswerUrl(html: String): String? {
        val text = html.replace("&amp;", "&").replace("&#38;", "&")

        CP_URL.find(text)?.let { match ->
            val path = match.groupValues[1].trim()
            if (path.isNotEmpty()) {
                return when {
                    path.startsWith("http://") || path.startsWith("https://") -> path
                    path.startsWith("//") -> "https:$path"
                    path.startsWith("/mooc-ans/") -> "https://mooc1-api.chaoxing.com$path"
                    else -> "https://mooc1-api.chaoxing.com/mooc-ans" +
                        (if (path.startsWith("/")) path else "/$path")
                }
            }
        }

        // 少数页面只写 _CP_ + path，不带 location.origin
        CP_ONLY.find(text)?.let { match ->
            val path = match.groupValues[1].trim()
            if (path.isNotEmpty()) {
                return when {
                    path.startsWith("http://") || path.startsWith("https://") -> path
                    path.startsWith("//") -> "https:$path"
                    path.startsWith("/mooc-ans/") -> "https://mooc1-api.chaoxing.com$path"
                    else -> "https://mooc1-api.chaoxing.com/mooc-ans" +
                        (if (path.startsWith("/")) path else "/$path")
                }
            }
        }

        for (regex in listOf(HREF_ANSWER, HREF_MOOC, GENERIC_URL)) {
            val match = regex.find(text) ?: continue
            val raw = match.groupValues[1].trim()
            if (raw.isNotEmpty()) return normalizeAnswerUrl(raw)
        }
        return null
    }

    private fun normalizeAnswerUrl(raw: String): String {
        val p = raw.trim()
        return when {
            p.startsWith("http://") || p.startsWith("https://") -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/mooc-ans/") -> "https://mooc1-api.chaoxing.com$p"
            p.startsWith("/work/") || p.startsWith("/exam/") ->
                "https://mooc1-api.chaoxing.com/mooc-ans$p"
            p.startsWith("/") -> "https://mooc1-api.chaoxing.com$p"
            else -> "https://mooc1-api.chaoxing.com/mooc-ans/$p"
        }
    }

    // ------------------------------------------------------------ 修改作业入口

    private val MODIFY_CP = Regex("""_CP_\s*\+\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val MODIFY_HREF = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val MODIFY_JS = Regex(
        """(?:location\.href|window\.open)\s*=\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )
    private val MODIFY_VAR = Regex(
        """(?:var|let|const)?\s*url\s*=\s*['"]([^'"]+)['"]""",
        RegexOption.IGNORE_CASE
    )

    /**
     * 从作业详情页里找“修改作业”按钮对应的地址。
     * 学习通的按钮可能是 <a href>、location.href 或 _CP_ + path，
     * 这里取离“修改作业”文字最近的一个 URL。
     */
    fun extractModifyUrl(html: String): String? {
        val text = html.replace("&amp;", "&").replace("&#38;", "&")
        val idx = text.indexOf("修改作业")
        if (idx < 0) return null

        val from = maxOf(0, idx - 600)
        val to = minOf(text.length, idx + 600)
        val window = text.substring(from, to)
        val candidates = ArrayList<Triple<Int, String, Boolean>>()

        for (match in MODIFY_CP.findAll(window)) {
            candidates.add(Triple(kotlin.math.abs(from + match.range.first - idx), match.groupValues[1], true))
        }
        for (regex in listOf(MODIFY_HREF, MODIFY_JS, MODIFY_VAR)) {
            for (match in regex.findAll(window)) {
                val raw = match.groupValues[1]
                if (raw.startsWith("javascript:", ignoreCase = true)) continue
                candidates.add(Triple(kotlin.math.abs(from + match.range.first - idx), raw, false))
            }
        }

        for ((_, raw, isCp) in candidates.sortedBy { it.first }) {
            val value = raw.trim()
            if (value.isEmpty()) continue
            return if (isCp) {
                when {
                    value.startsWith("http://") || value.startsWith("https://") -> value
                    value.startsWith("/mooc-ans/") -> "https://mooc1-api.chaoxing.com$value"
                    else -> "https://mooc1-api.chaoxing.com/mooc-ans" +
                        (if (value.startsWith("/")) value else "/$value")
                }
            } else {
                normalizeAnswerUrl(value)
            }
        }
        return null
    }
}

package com.xxkg.board

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream

/**
 * 2.7.0：文字型 PDF 课表导入。
 *
 * 思路：教务系统导出的课表 PDF 本质是一张表格。按页面旋转后的坐标取出每一行文字，
 * 先用“星期一..星期日”定位列，再把每列文字拼起来，按“学分”切分课程块，
 * 用“(3-5节)1-16周(双)/校区:.../场地:.../教师:...”解析时间段。
 *
 * 扫描件（没有文字层）不在这里处理，会给出明确提示。
 */
object PdfTimetableImporter {

    data class Run(
        val page: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val text: String,
    )

    private val dayNames = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
        "周一", "周二", "周三", "周四", "周五", "周六", "周日",
    )

    private val creditRegex = Regex("""学分\s*[:：]\s*\d+(?:\.\d+)?""")
    private val markerRegex = Regex("""\(\s*(\d{1,2})\s*[-–~至]\s*(\d{1,2})\s*节\s*\)""")
    private val symbolRegex = Regex("""[★☆◇◆□■●○▲△]""")
    private val spaceRegex = Regex("""\s+""")

    fun fromBytes(bytes: ByteArray, label: String): ImportResult {
        if (bytes.isEmpty()) {
            return ImportResult(label, emptyList(), emptyList(), listOf("PDF 文件是空的"))
        }
        val runs = try {
            ByteArrayInputStream(bytes).use { input ->
                PDDocument.load(input).use { doc -> extractRuns(doc) }
            }
        } catch (e: Exception) {
            return ImportResult(
                label, emptyList(), emptyList(),
                listOf("PDF 解析失败：${e.message ?: "格式不支持"}")
            )
        }
        return fromRuns(runs, label)
    }

    private fun extractRuns(doc: PDDocument): List<Run> {
        val runs = ArrayList<Run>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String?, positions: MutableList<TextPosition>?) {
                if (positions.isNullOrEmpty()) return
                val p = positions[0]
                runs.add(
                    Run(
                        page = currentPageNo,
                        x = p.xDirAdj,
                        y = p.yDirAdj,
                        w = p.widthDirAdj,
                        text = text.orEmpty(),
                    )
                )
            }
        }
        stripper.setSortByPosition(true)
        stripper.getText(doc)
        return runs
    }

    /** 纯文本层逻辑，便于单元测试。 */
    fun fromRuns(runs: List<Run>, label: String): ImportResult {
        val warnings = ArrayList<String>()
        val centers = DoubleArray(7) { Double.NaN }
        for (r in runs) {
            val t = r.text.trim()
            val idx = dayIndex(t)
            if (idx >= 0 && centers[idx].isNaN()) centers[idx] = (r.x + r.w / 2.0)
        }
        val missing = centers.count { it.isNaN() }
        if (missing >= 5) {
            return ImportResult(
                label, emptyList(), emptyList(),
                listOf("没有在 PDF 里找到“星期一…星期日”表头，可能是扫描件；文字识别会在后续版本支持")
            )
        }

        val present = (0 until 7).filter { !centers[it].isNaN() }.sortedBy { centers[it] }
        val bounds = DoubleArray(present.size + 1)
        if (present.size == 1) {
            val c = centers[present[0]]
            bounds[0] = c - 60.0
            bounds[1] = c + 60.0
        } else {
            bounds[0] = centers[present[0]] - (centers[present[1]] - centers[present[0]]) / 2.0
            bounds[present.size] =
                centers[present.last()] + (centers[present.last()] - centers[present[present.size - 2]]) / 2.0
            for (i in 1 until present.size) {
                bounds[i] = (centers[present[i - 1]] + centers[present[i]]) / 2.0
            }
        }

        val byDay = Array(7) { ArrayList<Run>() }
        for (r in runs) {
            val t = r.text.trim()
            if (t.isEmpty() || dayIndex(t) >= 0 || isMetaText(t)) continue
            val cx = r.x + r.w / 2.0
            for (i in present.indices) {
                if (cx >= bounds[i] && cx < bounds[i + 1]) {
                    byDay[present[i]].add(r)
                    break
                }
            }
        }

        val segments = ArrayList<ImportedSegment>()
        for (day in 0 until 7) {
            val rs = byDay[day]
            if (rs.isEmpty()) continue
            val text = flatten(rs)
            parseDayText(text, day + 1, warnings, segments)
        }

        if (segments.isEmpty()) {
            warnings.add("PDF 里没有识别到课程时间段")
        }
        return TimetableImporter.fromSegments(label, segments, warnings)
    }

    private fun flatten(runs: List<Run>): String {
        val sorted = runs.sortedWith(
            compareBy<Run> { it.page }.thenBy { it.y }.thenBy { it.x }
        )
        val sb = StringBuilder()
        var i = 0
        while (i < sorted.size) {
            val page = sorted[i].page
            val y = sorted[i].y
            var j = i
            while (j < sorted.size && sorted[j].page == page && kotlin.math.abs(sorted[j].y - y) <= 3.0f) {
                j++
            }
            sb.append(sorted.subList(i, j).sortedBy { it.x }.joinToString("") { it.text.trim() })
            i = j
        }
        return sb.toString()
    }

    private fun parseDayText(
        text: String,
        day: Int,
        warnings: MutableList<String>,
        out: MutableList<ImportedSegment>,
    ) {
        val cuts = creditRegex.findAll(text).map { it.range.last + 1 }.toList()
        var start = 0
        var prevName = ""
        for (end in cuts) {
            val chunk = text.substring(start, end)
            start = end
            val (name, list) = parseChunk(chunk, day, prevName, warnings)
            if (name.isNotBlank()) prevName = name
            out.addAll(list)
        }
        if (start < text.length) {
            val (_, list) = parseChunk(text.substring(start), day, prevName, warnings)
            out.addAll(list)
        }
    }

    private fun parseChunk(
        chunk: String,
        day: Int,
        prevName: String,
        warnings: MutableList<String>,
    ): Pair<String, List<ImportedSegment>> {
        val markers = markerRegex.findAll(chunk).toList()
        if (markers.isEmpty()) return "" to emptyList()

        var name = cleanName(chunk.substring(0, markers.first().range.first))
        if (name.isEmpty()) name = prevName
        if (name.isEmpty()) return "" to emptyList()

        val list = ArrayList<ImportedSegment>()
        for ((index, marker) in markers.withIndex()) {
            val blockEnd = if (index + 1 < markers.size) markers[index + 1].range.first else chunk.length
            val block = chunk.substring(marker.range.last + 1, blockEnd).substringBefore("学分")
            val startPeriod = marker.groupValues[1].toIntOrNull() ?: continue
            val endPeriod = (marker.groupValues[2].toIntOrNull() ?: startPeriod).coerceAtLeast(startPeriod)
            val weeks = TimetableImporter.parseWeeks(block)
            if (weeks.isEmpty()) {
                warnings.add("$name：没有识别到周次")
                continue
            }
            list.add(
                ImportedSegment(
                    courseName = name,
                    day = day,
                    startPeriod = startPeriod,
                    endPeriod = endPeriod,
                    weeks = weeks,
                    campus = field(block, "校区"),
                    location = field(block, "场地")
                        .ifEmpty { field(block, "地点") }
                        .ifEmpty { field(block, "教室") },
                    teacher = field(block, "教师").ifEmpty { field(block, "老师") },
                )
            )
        }
        return name to list
    }

    private fun cleanName(raw: String): String =
        raw.replace(symbolRegex, "").replace(spaceRegex, "").trim()

    private fun isMetaText(t: String): Boolean {
        if (t.length <= 24 && t.endsWith("课表")) return true
        if (t.contains("学号") || t.contains("打印时间") || t.contains("学期")) return true
        return t == "时间段" || t == "节次" || t == "上午" || t == "中午" || t == "下午" || t == "晚上"
    }

    private fun dayIndex(t: String): Int {
        val s = t.replace(" ", "")
        if (s.length > 4 || s.isEmpty()) return -1
        val list = listOf(
            "星期一" to 0, "周一" to 0,
            "星期二" to 1, "周二" to 1,
            "星期三" to 2, "周三" to 2,
            "星期四" to 3, "周四" to 3,
            "星期五" to 4, "周五" to 4,
            "星期六" to 5, "周六" to 5,
            "星期日" to 6, "星期天" to 6, "周日" to 6, "周天" to 6,
        )
        return list.firstOrNull { it.first == s }?.second ?: -1
    }

    private fun field(text: String, key: String): String {
        val m = Regex(Regex.escape(key) + """\s*[:：]\s*([^/;\n]+)""").find(text) ?: return ""
        val v = m.groupValues[1].trim()
        return if (v.isEmpty() || v == "无") "" else v
    }
}

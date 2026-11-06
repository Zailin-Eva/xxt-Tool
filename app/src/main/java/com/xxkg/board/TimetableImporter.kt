package com.xxkg.board

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 2.6.0 导入的第一阶段：Excel / CSV / HTML → 统一课程/时间段。
 *
 * 目标格式：
 * - 列表型：表头含“课程/星期/节次/周次/地点/教师”
 * - 网格型：表头含“星期一..星期日”，单元格里是课程块
 * - 课程块：课程名 + (3-5节) + 1-16周(双)/校区:.../场地:.../教师:...
 */
data class ImportedCourse(
    val name: String,
    val teacher: String = "",
    val credits: String = "",
)

data class ImportedSegment(
    val courseName: String,
    val day: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeks: MutableSet<Int>,
    val campus: String = "",
    val location: String = "",
    val teacher: String = "",
)

data class ImportResult(
    val label: String,
    val courses: List<ImportedCourse>,
    val segments: List<ImportedSegment>,
    val warnings: List<String>,
)

object TimetableImporter {

    private val dayTokens = listOf(
        "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日",
        "周一", "周二", "周三", "周四", "周五", "周六", "周日",
    )

    fun fromCsv(text: String, label: String = "CSV"): ImportResult {
        val grid = parseCsv(text)
        return fromGrid(grid, label)
    }

    fun fromHtml(html: String, label: String = "HTML"): ImportResult {
        val doc = Jsoup.parse(html)
        val results = ArrayList<ImportResult>()
        for (table in doc.select("table")) {
            val grid = tableToGrid(table)
            if (grid.size < 2) continue
            val r = fromGrid(grid, label)
            if (r.segments.isNotEmpty()) results.add(r)
        }
        if (results.isEmpty()) {
            return ImportResult(label, emptyList(), emptyList(), listOf("没有在 HTML 里找到可识别的课表表格"))
        }
        val courses = LinkedHashMap<String, ImportedCourse>()
        val segments = ArrayList<ImportedSegment>()
        val warnings = ArrayList<String>()
        for (r in results) {
            for (c in r.courses) courses.putIfAbsent(c.name, c)
            segments.addAll(r.segments)
            warnings.addAll(r.warnings)
        }
        return ImportResult(label, courses.values.toList(), dedupe(segments), warnings.distinct())
    }

    fun fromXlsx(bytes: ByteArray, label: String = "Excel"): ImportResult {
        val grid = try {
            XlsxReader.read(bytes)
        } catch (e: Exception) {
            return ImportResult(label, emptyList(), emptyList(), listOf("Excel 解析失败：${e.message ?: "格式不支持"}"))
        }
        if (grid.isEmpty()) {
            return ImportResult(label, emptyList(), emptyList(), listOf("Excel 里没有读到内容"))
        }
        return fromGrid(grid, label)
    }

    fun fromGrid(rawGrid: List<List<String>>, label: String): ImportResult {
        val grid = rawGrid.map { row -> row.map { it.trim() } }
        val warnings = ArrayList<String>()

        val gridHeader = findGridHeader(grid)
        if (gridHeader != null) {
            return parseGrid(grid, gridHeader.first, gridHeader.second, label, warnings)
        }

        val listHeader = findListHeader(grid)
        if (listHeader != null) {
            return parseList(grid, listHeader, label, warnings)
        }

        return ImportResult(
            label,
            emptyList(),
            emptyList(),
            warnings + "没有识别到表头（需要“星期/节次/周次”或“课程/地点”等列）"
        )
    }

    // ------------------------------------------------------------ 网格型

    private fun findGridHeader(grid: List<List<String>>): Pair<Int, Map<Int, Int>>? {
        for ((rowIndex, row) in grid.withIndex().take(15)) {
            val dayCols = LinkedHashMap<Int, Int>()
            for ((col, cell) in row.withIndex()) {
                val d = parseDay(cell)
                if (d > 0) dayCols[col] = d
            }
            if (dayCols.size >= 3) return rowIndex to dayCols
        }
        return null
    }

    private fun parseGrid(
        grid: List<List<String>>,
        headerRow: Int,
        dayCols: Map<Int, Int>,
        label: String,
        warnings: MutableList<String>,
    ): ImportResult {
        val segments = ArrayList<ImportedSegment>()
        for (rowIndex in (headerRow + 1) until grid.size) {
            val row = grid[rowIndex]
            val fallbackPeriod = (rowIndex - headerRow).coerceAtLeast(1)
            for ((col, day) in dayCols) {
                val text = row.getOrNull(col).orEmpty().trim()
                if (text.isEmpty()) continue
                if (parseDay(text) > 0) continue
                val parsed = parseCellBlocks(text, day, fallbackPeriod, warnings)
                segments.addAll(parsed)
            }
        }
        return finish(label, segments, warnings)
    }

    // ------------------------------------------------------------ 列表型

    private data class ListColumns(
        val headerRow: Int,
        val course: Int,
        val day: Int,
        val period: Int,
        val weeks: Int,
        val location: Int,
        val campus: Int,
        val teacher: Int,
        val credits: Int,
    )

    private fun findListHeader(grid: List<List<String>>): ListColumns? {
        for ((rowIndex, row) in grid.withIndex().take(15)) {
            fun find(vararg names: String): Int = row.indexOfFirst { cell ->
                names.any { cell.contains(it) }
            }
            val course = find("课程名称", "课程")
            if (course < 0) continue
            val day = find("星期", "周几", "上课星期")
            val period = find("节次", "上课节次", "时间")
            val weeks = find("周次", "上课周次")
            if (day < 0 && period < 0) continue
            return ListColumns(
                headerRow = rowIndex,
                course = course,
                day = day,
                period = period,
                weeks = weeks,
                location = find("地点", "教室", "上课地点"),
                campus = find("校区"),
                teacher = find("教师", "老师"),
                credits = find("学分"),
            )
        }
        return null
    }

    private fun parseList(
        grid: List<List<String>>,
        cols: ListColumns,
        label: String,
        warnings: MutableList<String>,
    ): ImportResult {
        val segments = ArrayList<ImportedSegment>()
        for (rowIndex in (cols.headerRow + 1) until grid.size) {
            val row = grid[rowIndex]
            fun value(index: Int): String = if (index in row.indices) row[index].trim() else ""
            val name = value(cols.course)
            if (name.isEmpty()) continue
            val day = parseDay(value(cols.day))
            val (start, end) = parsePeriods(value(cols.period))
            val weeks = parseWeeks(value(cols.weeks))
            val periodText = value(cols.period)
            // 有些导出把“星期/节次/周次”合并在一个单元格里
            val combined = "$periodText ${value(cols.day)} ${value(cols.weeks)} ${value(cols.location)}"
            if (day <= 0 || start <= 0) {
                val fallback = parseCellBlocks(
                    listOf(name, combined).filter { it.isNotBlank() }.joinToString("\n"),
                    0, 1, warnings
                )
                if (fallback.isNotEmpty()) {
                    segments.addAll(fallback)
                    continue
                }
            }
            if (day <= 0 || start <= 0 || weeks.isEmpty()) {
                warnings.add("无法识别：$name（星期/节次/周次不完整）")
                continue
            }
            segments.add(
                ImportedSegment(
                    courseName = name,
                    day = day,
                    startPeriod = start,
                    endPeriod = end.coerceAtLeast(start),
                    weeks = weeks,
                    campus = value(cols.campus),
                    location = value(cols.location),
                    teacher = value(cols.teacher),
                )
            )
            // 课程信息在 finish() 里从 segments 汇总
            @Suppress("UNUSED_VARIABLE")
            val credits = value(cols.credits)
        }
        return finish(label, segments, warnings)
    }

    // ------------------------------------------------------------ 单元格课程块

    private val periodRange = Regex("""\(?\s*(\d{1,2})\s*[-–~至]\s*(\d{1,2})\s*节\s*\)?""")
    private val periodSingle = Regex("""(\d{1,2})\s*节""")
    private val weeksRange = Regex("""(\d{1,2})\s*[-–~至]\s*(\d{1,2})\s*周\s*(?:\((单|双)\))?""")
    private val weeksSingle = Regex("""(\d{1,2})\s*周""")

    fun parseCellBlocks(
        raw: String,
        fallbackDay: Int,
        fallbackPeriod: Int,
        warnings: MutableList<String>,
    ): List<ImportedSegment> {
        if (raw.isBlank()) return emptyList()
        var text = raw
            .replace('\u00a0', ' ')
            .replace('（', '(')
            .replace('）', ')')
            .replace('：', ':')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        val actualDay = if (fallbackDay > 0) fallbackDay else parseDay(text)
        if (actualDay <= 0) {
            warnings.add("无法识别星期：${text.take(50)}")
            return emptyList()
        }

        val markers = periodRange.findAll(text).toList()
        if (markers.isNotEmpty()) {
            val name = text.substring(0, markers.first().range.first)
                .replace("★", "")
                .replace("\n", " ")
                .trim()
                .trim('@', ' ')
            if (name.isEmpty()) {
                warnings.add("课程块缺少课程名：${text.take(40)}")
                return emptyList()
            }
            val out = ArrayList<ImportedSegment>()
            for ((index, marker) in markers.withIndex()) {
                val blockEnd = if (index + 1 < markers.size) markers[index + 1].range.first else text.length
                val block = text.substring(marker.range.last + 1, blockEnd)
                val start = marker.groupValues[1].toIntOrNull() ?: fallbackPeriod
                val end = marker.groupValues[2].toIntOrNull() ?: start
                val weeks = parseWeeks(block)
                val campus = field(block, listOf("校区"))
                val location = field(block, listOf("场地", "地点", "教室"))
                val teacher = field(block, listOf("教师", "老师"))
                if (weeks.isEmpty()) {
                    warnings.add("$name：没有识别到周次")
                    continue
                }
                out.add(
                    ImportedSegment(
                        courseName = name,
                        day = actualDay,
                        startPeriod = start,
                        endPeriod = end.coerceAtLeast(start),
                        weeks = weeks,
                        campus = campus,
                        location = location,
                        teacher = teacher,
                    )
                )
            }
            return out
        }

        // 没有节次标记：按“第一行课程名 + 后面字段”兜底
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val name = lines.first().replace("★", "").trim().trim('@', ' ')
        val rest = lines.drop(1).joinToString("\n")
        val weeks = parseWeeks(text)
        if (name.isEmpty() || weeks.isEmpty()) {
            warnings.add("无法识别课程块：${text.take(50)}")
            return emptyList()
        }
        return listOf(
            ImportedSegment(
                courseName = name,
                day = actualDay,
                startPeriod = fallbackPeriod.coerceAtLeast(1),
                endPeriod = fallbackPeriod.coerceAtLeast(1),
                weeks = weeks,
                campus = field(rest, listOf("校区")),
                location = field(rest, listOf("场地", "地点", "教室")),
                teacher = field(rest, listOf("教师", "老师")),
            )
        )
    }

    fun parseWeeks(raw: String): MutableSet<Int> {
        val out = sortedSetOf<Int>()
        var rest = raw.replace('（', '(').replace('）', ')')
        for (m in weeksRange.findAll(rest)) {
            val a = m.groupValues[1].toIntOrNull() ?: continue
            val b = m.groupValues[2].toIntOrNull() ?: continue
            val parity = m.groupValues[3]
            for (w in a..b) {
                if (parity == "单" && w % 2 == 0) continue
                if (parity == "双" && w % 2 == 1) continue
                if (w in 1..60) out.add(w)
            }
        }
        // 去掉已匹配的区间，避免单周匹配重复
        rest = weeksRange.replace(rest, " ")
        for (m in weeksSingle.findAll(rest)) {
            val w = m.groupValues[1].toIntOrNull() ?: continue
            if (w in 1..60) out.add(w)
        }
        return out
    }

    fun parsePeriods(raw: String): Pair<Int, Int> {
        val m = periodRange.find(raw)
        if (m != null) {
            val a = m.groupValues[1].toIntOrNull() ?: 0
            val b = m.groupValues[2].toIntOrNull() ?: a
            return a to b.coerceAtLeast(a)
        }
        val s = periodSingle.find(raw)
        if (s != null) {
            val a = s.groupValues[1].toIntOrNull() ?: 0
            return a to a
        }
        return 0 to 0
    }

    fun parseDay(raw: String): Int {
        val s = raw.replace(" ", "")
        val names = listOf(
            listOf("星期一", "周一", "礼拜一", "Monday", "Mon"),
            listOf("星期二", "周二", "礼拜二", "Tuesday", "Tue"),
            listOf("星期三", "周三", "礼拜三", "Wednesday", "Wed"),
            listOf("星期四", "周四", "礼拜四", "Thursday", "Thu"),
            listOf("星期五", "周五", "礼拜五", "Friday", "Fri"),
            listOf("星期六", "周六", "礼拜六", "Saturday", "Sat"),
            listOf("星期日", "星期天", "周日", "周天", "礼拜日", "Sunday", "Sun"),
        )
        for ((i, tokens) in names.withIndex()) {
            if (tokens.any { s.contains(it, ignoreCase = true) }) return i + 1
        }
        return 0
    }

    private fun field(text: String, names: List<String>): String {
        for (name in names) {
            val m = Regex(Regex.escape(name) + """\s*[:：]\s*([^/;\n]+)""").find(text)
            if (m != null) {
                val v = m.groupValues[1].trim()
                if (v.isNotEmpty() && v != "无") return v
            }
        }
        return ""
    }

    private fun finish(
        label: String,
        segments: List<ImportedSegment>,
        warnings: MutableList<String>,
    ): ImportResult {
        val deduped = dedupe(segments)
        val courses = LinkedHashMap<String, ImportedCourse>()
        for (s in deduped) {
            val existing = courses[s.courseName]
            courses[s.courseName] = ImportedCourse(
                name = s.courseName,
                teacher = existing?.teacher?.ifEmpty { s.teacher } ?: s.teacher,
            )
        }
        if (deduped.isEmpty() && warnings.isEmpty()) warnings.add("没有识别到课程")
        return ImportResult(label, courses.values.toList(), deduped, warnings.distinct())
    }

    private fun dedupe(list: List<ImportedSegment>): List<ImportedSegment> {
        val seen = HashSet<String>()
        return list.filter { s ->
            val key = "${s.courseName}|${s.day}|${s.startPeriod}|${s.endPeriod}|" +
                "${s.weeks.joinToString(",")}|${s.campus}|${s.location}|${s.teacher}"
            seen.add(key)
        }
    }

    // ------------------------------------------------------------ CSV / HTML

    fun parseCsv(text: String): List<List<String>> {
        val raw = text.trim('\ufeff', ' ', '\r', '\n')
        if (raw.isEmpty()) return emptyList()
        val delimiter = when {
            raw.lineSequence().firstOrNull()?.contains('\t') == true -> '\t'
            raw.lineSequence().firstOrNull()?.contains(';') == true -> ';'
            else -> ','
        }
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val cell = StringBuilder()
        var quote = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (quote) {
                if (c == '"') {
                    if (i + 1 < raw.length && raw[i + 1] == '"') {
                        cell.append('"'); i++
                    } else {
                        quote = false
                    }
                } else {
                    cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> quote = true
                    delimiter -> {
                        row.add(cell.toString()); cell.setLength(0)
                    }
                    '\n' -> {
                        row.add(cell.toString()); cell.setLength(0)
                        rows.add(row.toList()); row.clear()
                    }
                    '\r' -> Unit
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString())
            rows.add(row.toList())
        }
        return rows
    }

    private fun tableToGrid(table: Element): List<List<String>> {
        val grid = ArrayList<MutableList<String>>()

        fun ensure(r: Int, c: Int) {
            while (grid.size <= r) grid.add(ArrayList())
            while (grid[r].size <= c) grid[r].add("")
        }

        var rowIndex = 0
        for (tr in table.select("tr")) {
            var col = 0
            for (cell in tr.children()) {
                if (cell.tagName() != "td" && cell.tagName() != "th") continue
                ensure(rowIndex, col)
                while (col < grid[rowIndex].size && grid[rowIndex][col].isNotEmpty()) col++
                ensure(rowIndex, col)
                val text = cell.text().trim()
                val rowspan = cell.attr("rowspan").toIntOrNull()?.coerceIn(1, 20) ?: 1
                val colspan = cell.attr("colspan").toIntOrNull()?.coerceIn(1, 10) ?: 1
                for (dr in 0 until rowspan) {
                    for (dc in 0 until colspan) {
                        ensure(rowIndex + dr, col + dc)
                        if (dr == 0 && dc == 0) {
                            grid[rowIndex][col] = text
                        } else if (grid[rowIndex + dr][col + dc].isEmpty()) {
                            grid[rowIndex + dr][col + dc] = text
                        }
                    }
                }
                col += colspan
            }
            rowIndex++
        }
        return grid.map { it.toList() }
    }
}

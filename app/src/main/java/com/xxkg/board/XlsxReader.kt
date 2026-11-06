package com.xxkg.board

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 极简 .xlsx 读取：zip + sharedStrings + sheet XML（DOM）。
 * 只取文本和合并单元格，不处理公式/样式，避免引入 POI。
 */
object XlsxReader {

    fun read(bytes: ByteArray): List<List<String>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = zip.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                entries[entry.name] = out.toByteArray()
            }
        }

        val shared = entries["xl/sharedStrings.xml"]?.let { parseSharedStrings(it) } ?: emptyList()
        val sheetName = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
            .firstOrNull()
            ?: throw IllegalArgumentException("找不到工作表")
        return parseSheet(entries.getValue(sheetName), shared)
    }

    private fun document(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = document(bytes)
        val out = ArrayList<String>()
        val sis = doc.getElementsByTagName("si")
        for (i in 0 until sis.length) {
            val si = sis.item(i) as? org.w3c.dom.Element
            val texts = si?.getElementsByTagName("t")
            val sb = StringBuilder()
            if (texts != null) {
                for (j in 0 until texts.length) sb.append(texts.item(j).textContent.orEmpty())
            }
            out.add(sb.toString())
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val doc = document(bytes)
        val rows = ArrayList<MutableList<String>>()

        fun ensure(r: Int, c: Int) {
            while (rows.size <= r) rows.add(ArrayList())
            while (rows[r].size <= c) rows[r].add("")
        }

        val rowNodes = doc.getElementsByTagName("row")
        for (i in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(i) as? org.w3c.dom.Element ?: continue
            val rowIndex = rowEl.getAttribute("r").toIntOrNull()?.minus(1) ?: i
            var fallbackCol = 0
            val cells = rowEl.getElementsByTagName("c")
            for (j in 0 until cells.length) {
                val cell = cells.item(j) as? org.w3c.dom.Element ?: continue
                val ref = cell.getAttribute("r")
                val col = colOf(ref).takeIf { it >= 0 } ?: fallbackCol
                fallbackCol = col + 1
                val type = cell.getAttribute("t")
                val vNodes = cell.getElementsByTagName("v")
                val rawValue = if (vNodes.length > 0) vNodes.item(0).textContent.orEmpty() else ""
                val value = when (type) {
                    "s" -> rawValue.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> {
                        val ts = cell.getElementsByTagName("t")
                        buildString {
                            for (k in 0 until ts.length) append(ts.item(k).textContent.orEmpty())
                        }
                    }
                    else -> rawValue
                }
                ensure(rowIndex, col)
                rows[rowIndex][col] = value
            }
            ensure(rowIndex, 0)
        }

        val mergeNodes = doc.getElementsByTagName("mergeCell")
        for (i in 0 until mergeNodes.length) {
            val ref = (mergeNodes.item(i) as? org.w3c.dom.Element)?.getAttribute("ref") ?: continue
            val parts = ref.split(":")
            if (parts.size != 2) continue
            val a = cellOf(parts[0]) ?: continue
            val b = cellOf(parts[1]) ?: continue
            val value = rows.getOrNull(a.first)?.getOrNull(a.second).orEmpty()
            for (r in a.first..b.first) {
                ensure(r, b.second)
                for (c in a.second..b.second) {
                    if (rows[r][c].isEmpty()) rows[r][c] = value
                }
            }
        }

        return rows.map { it.toList() }
    }

    private fun colOf(ref: String): Int {
        var col = 0
        var seen = false
        for (ch in ref.replace("$", "")) {
            if (ch in 'A'..'Z') {
                col = col * 26 + (ch - 'A' + 1)
                seen = true
            } else if (ch in 'a'..'z') {
                col = col * 26 + (ch - 'a' + 1)
                seen = true
            } else if (seen) {
                break
            }
        }
        return if (seen) col - 1 else -1
    }

    private fun cellOf(ref: String): Pair<Int, Int>? {
        val col = colOf(ref)
        if (col < 0) return null
        val digits = ref.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }
        val row = digits.toIntOrNull() ?: return null
        return (row - 1) to col
    }
}

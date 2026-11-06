package com.xxkg.board

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset

/** 2.6.0 初版：Excel / CSV / HTML 导入 + 预览 + 覆盖/合并。 */
class TimetableImportActivity : AppCompatActivity() {

    private lateinit var data: TimetableData
    private var result: ImportResult? = null
    private lateinit var tvFile: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvPreview: TextView
    private lateinit var tvWarnings: TextView
    private lateinit var importActions: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = TimetableStore.load(this)
        setContentView(R.layout.activity_timetable_import)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        tvFile = findViewById(R.id.tvFile)
        tvCount = findViewById(R.id.tvCount)
        tvPreview = findViewById(R.id.tvPreview)
        tvWarnings = findViewById(R.id.tvWarnings)
        importActions = findViewById(R.id.importActions)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPick).setOnClickListener { pickFile() }
        findViewById<View>(R.id.btnReplace).setOnClickListener { confirmImport(false) }
        findViewById<View>(R.id.btnMerge).setOnClickListener { confirmImport(true) }
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "text/html",
                    "application/pdf",
                    "application/octet-stream",
                )
            )
        }
        startActivityForResult(intent, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = displayName(uri)
        tvFile.text = name
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
        if (bytes == null || bytes.isEmpty()) {
            toast("读取文件失败")
            return
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        val isPdf = ext == "pdf" || (bytes.size > 3 &&
            bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte())
        val parsed = try {
            when {
                isPdf -> {
                    com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                    PdfTimetableImporter.fromBytes(bytes, name)
                }
                ext == "xlsx" -> TimetableImporter.fromXlsx(bytes, name)
                ext == "csv" -> TimetableImporter.fromCsv(decodeText(bytes), name)
                ext == "html" || ext == "htm" -> TimetableImporter.fromHtml(decodeText(bytes), name)
                bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() ->
                    TimetableImporter.fromXlsx(bytes, name)
                decodeText(bytes).contains("<table", ignoreCase = true) ->
                    TimetableImporter.fromHtml(decodeText(bytes), name)
                else -> TimetableImporter.fromCsv(decodeText(bytes), name)
            }
        } catch (e: Exception) {
            ImportResult(name, emptyList(), emptyList(), listOf("解析失败：${e.message ?: "未知错误"}"))
        }
        showResult(parsed)
    }

    private fun showResult(r: ImportResult) {
        result = r
        if (r.segments.isEmpty()) {
            tvCount.text = "没有识别到可导入的课程"
            tvPreview.text = ""
            tvWarnings.text = r.warnings.joinToString("\n") { "· $it" }
            importActions.visibility = View.GONE
            return
        }
        tvCount.text = "识别到 ${r.courses.size} 门课 / ${r.segments.size} 个时间段"
        tvPreview.text = buildString {
            for (s in r.segments.take(80)) {
                append("· ")
                append(s.courseName)
                append("  ")
                append(TimetableLogic.weeksText(s.weeks))
                append("  ")
                append(TimetableLogic.weekday(s.day))
                append(" ")
                append(TimetableLogic.periodText(s.startPeriod, s.endPeriod))
                append("  ")
                append(TimetableLogic.timeRange(data.periods, s.startPeriod, s.endPeriod))
                val room = listOf(s.campus, s.location).filter { it.isNotBlank() }.joinToString(" ")
                if (room.isNotBlank()) {
                    append("\n    ")
                    append(room)
                }
                val teacher = s.teacher.ifBlank { r.courses.firstOrNull { it.name == s.courseName }?.teacher.orEmpty() }
                if (teacher.isNotBlank()) {
                    append("  ")
                    append(teacher)
                }
                append("\n")
            }
            if (r.segments.size > 80) append("……还有 ${r.segments.size - 80} 条\n")
        }.trim()
        tvWarnings.text = if (r.warnings.isEmpty()) "" else buildString {
            append("需要注意：\n")
            for (w in r.warnings.take(20)) append("· ").append(w).append("\n")
        }.trim()
        importActions.visibility = View.VISIBLE
    }

    private fun confirmImport(merge: Boolean) {
        val r = result ?: return
        if (r.segments.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(if (merge) "合并导入" else "覆盖导入")
            .setMessage(
                if (merge) {
                    "会把识别到的 ${r.courses.size} 门课 / ${r.segments.size} 个时间段追加到当前课表；同名的课程会复用，重复时间段会跳过。"
                } else {
                    "会清空当前所有课程和时间段，只保留学期/节次设置，然后写入识别到的 ${r.courses.size} 门课 / ${r.segments.size} 个时间段。"
                }
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ -> apply(r, merge) }
            .show()
    }

    private fun apply(r: ImportResult, merge: Boolean) {
        data = TimetableStore.load(this)
        if (!merge) {
            data.courses.clear()
            data.segments.clear()
            data.unscheduled.clear()
        }
        val courseMap = HashMap<String, TtCourse>()
        for (c in data.courses) courseMap[c.name] = c

        for (c in r.courses) {
            var course = courseMap[c.name]
            if (course == null) {
                course = TtCourse(
                    id = TimetableCodec.newId(),
                    name = c.name,
                    teacher = c.teacher,
                    credits = c.credits,
                )
                data.courses.add(course)
                courseMap[c.name] = course
            } else if (course.teacher.isBlank() && c.teacher.isNotBlank()) {
                course.teacher = c.teacher
            }
        }

        var added = 0
        for (s in r.segments) {
            val course = courseMap[s.courseName] ?: continue
            val duplicate = data.segments.any {
                it.courseId == course.id &&
                    it.day == s.day &&
                    it.startPeriod == s.startPeriod &&
                    it.endPeriod == s.endPeriod &&
                    it.weeks == s.weeks &&
                    it.campus == s.campus &&
                    it.location == s.location
            }
            if (duplicate) continue
            data.segments.add(
                Segment(
                    id = TimetableCodec.newId(),
                    courseId = course.id,
                    day = s.day,
                    startPeriod = s.startPeriod,
                    endPeriod = s.endPeriod,
                    weeks = s.weeks,
                    campus = s.campus,
                    location = s.location,
                    teacher = s.teacher,
                )
            )
            added++
        }

        if (!TimetableStore.save(this, data)) {
            toast("保存失败")
            return
        }
        toast("已导入 $added 个时间段")
        setResult(RESULT_OK)
        finish()
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else ""
            } ?: uri.lastPathSegment.orEmpty()
        } catch (e: Exception) {
            uri.lastPathSegment.orEmpty()
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            String(bytes, Charset.forName("GBK"))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_PICK = 4201
    }
}

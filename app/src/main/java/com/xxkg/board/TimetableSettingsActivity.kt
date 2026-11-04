package com.xxkg.board

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 课表设置：学期、节次、强调色、非本周开关、JSON 备份。 */
class TimetableSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var data: TimetableData
    private lateinit var accentRow: LinearLayout
    private lateinit var tvTerm: TextView
    private lateinit var tvPeriods: TextView
    private lateinit var tvVersion: TextView
    private lateinit var swNonCurrent: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        data = TimetableStore.load(this)
        setContentView(R.layout.activity_timetable_settings)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        accentRow = findViewById(R.id.accentRow)
        tvTerm = findViewById(R.id.tvTermSummary)
        tvPeriods = findViewById(R.id.tvPeriodSummary)
        tvVersion = findViewById(R.id.tvVersion)
        swNonCurrent = findViewById(R.id.swNonCurrent)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnTerm).setOnClickListener {
            startActivity(Intent(this, TermSettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnPeriods).setOnClickListener {
            startActivity(Intent(this, PeriodSettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnCourses).setOnClickListener { showCourseManager() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportJson() }
        findViewById<View>(R.id.btnImport).setOnClickListener { importJson() }

        swNonCurrent.isChecked = prefs.showNonCurrentWeek
        swNonCurrent.setOnCheckedChangeListener { _, checked ->
            prefs.showNonCurrentWeek = checked
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        data = TimetableStore.load(this)
        refresh()
    }

    private fun refresh() {
        val current = TimetableLogic.currentWeek(data.term)
        tvTerm.text = "${data.term.name} · 第 $current 周"
        tvPeriods.text = "${data.periods.size} 节 · ${data.periods.firstOrNull()?.start ?: "08:00"} 开始"
        findViewById<TextView>(R.id.btnCourses).text = "课程管理（${data.courses.size} 门）"
        swNonCurrent.isChecked = prefs.showNonCurrentWeek
        renderAccents()
        tvVersion.text = "xxtT v" + versionName() + " · 课程表"
    }

    private fun renderAccents() {
        accentRow.removeAllViews()
        val selected = prefs.accent
        for (a in Palette.ACCENTS) {
            val dot = View(this)
            val size = dp(32)
            val lp = LinearLayout.LayoutParams(size, size).apply {
                rightMargin = dp(10)
            }
            dot.layoutParams = lp
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(a.color)
                if (a.key == selected) {
                    setStroke(dp(2), Palette.INK)
                } else {
                    setStroke(dp(1), 0xFFE0D9CC.toInt())
                }
            }
            dot.contentDescription = a.label
            dot.setOnClickListener {
                prefs.accent = a.key
                renderAccents()
                toast("已切换为${a.label}")
            }
            accentRow.addView(dot)
        }
    }

    private fun showCourseManager() {
        data = TimetableStore.load(this)
        if (data.courses.isEmpty()) {
            toast("还没有课程，回课表点右上角 + 添加")
            return
        }
        val sorted = data.courses.sortedBy { it.name }
        val items = sorted.map { c ->
            val count = data.segmentsOf(c.id).size
            "${c.name} · $count 个时间段"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("课程管理")
            .setItems(items) { _, which ->
                val course = sorted.getOrNull(which) ?: return@setItems
                startActivity(
                    Intent(this, CourseEditActivity::class.java)
                        .putExtra(CourseEditActivity.EXTRA_COURSE_ID, course.id)
                )
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun exportJson() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "xxtT-课表备份.json")
        }
        startActivityForResult(intent, REQ_EXPORT)
    }

    private fun importJson() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_EXPORT -> {
                val ok = TimetableStore.export(this, uri, this.data)
                toast(if (ok) "已导出" else "导出失败")
            }
            REQ_IMPORT -> {
                val imported = TimetableStore.import(this, uri)
                if (imported == null) {
                    toast("无法读取这个备份文件")
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle("导入课表")
                    .setMessage("会用备份覆盖当前学期、课程和时间段，确定吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("覆盖导入") { _, _ ->
                        this.data = imported
                        if (TimetableStore.save(this, imported)) {
                            refresh()
                            toast("已导入 ${imported.courses.size} 门课程")
                        } else {
                            toast("导入失败")
                        }
                    }
                    .show()
            }
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "2.5.0"
    } catch (e: Exception) {
        "2.5.0"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EXPORT = 3301
        private const val REQ_IMPORT = 3302
    }
}

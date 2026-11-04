package com.xxkg.board

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 节次时间编辑：逐节改，或按每节时长/课间批量生成。 */
class PeriodSettingsActivity : AppCompatActivity() {

    private lateinit var data: TimetableData
    private lateinit var periodContainer: LinearLayout
    private val rows = ArrayList<Row>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = TimetableStore.load(this)
        setContentView(R.layout.activity_period_settings)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        periodContainer = findViewById(R.id.periodContainer)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            data.periods = TimetableDefaults.defaultPeriods()
            renderRows()
        }
        findViewById<View>(R.id.btnGenerate).setOnClickListener { showGenerateDialog() }
        renderRows()
    }

    private fun renderRows() {
        periodContainer.removeAllViews()
        rows.clear()
        var lastSection: Section? = null
        for (p in data.periods) {
            if (p.section != lastSection) {
                val header = TextView(this).apply {
                    text = p.section.label
                    setTextColor(Palette.MUTED2)
                    textSize = 10f
                    setPadding(0, dp(14), 0, dp(2))
                }
                periodContainer.addView(header)
                lastSection = p.section
            }
            val rowView = layoutInflater.inflate(R.layout.item_period_row, periodContainer, false)
            val no = rowView.findViewById<TextView>(R.id.tvPeriodNo)
            val start = rowView.findViewById<EditText>(R.id.etStart)
            val end = rowView.findViewById<EditText>(R.id.etEnd)
            no.text = p.no.toString()
            start.setText(p.start)
            end.setText(p.end)
            periodContainer.addView(rowView)
            rows.add(Row(p, start, end))
        }
    }

    private fun save() {
        for (row in rows) {
            val a = TimetableLogic.minutesOfDay(row.start.text.toString().trim())
            val b = TimetableLogic.minutesOfDay(row.end.text.toString().trim())
            if (a < 0 || b < 0) {
                toast("第${row.period.no}节的时间格式应为 HH:mm")
                return
            }
            if (b <= a) {
                toast("第${row.period.no}节的结束时间必须晚于开始时间")
                return
            }
        }
        data = TimetableStore.load(this)
        for (row in rows) {
            val target = data.periods.firstOrNull { it.no == row.period.no } ?: continue
            target.start = row.start.text.toString().trim()
            target.end = row.end.text.toString().trim()
        }
        if (!TimetableStore.save(this, data)) {
            toast("保存失败")
            return
        }
        finish()
    }

    private fun showGenerateDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_generate_periods, null)
        val etDuration = view.findViewById<EditText>(R.id.etDuration)
        val etBreak = view.findViewById<EditText>(R.id.etBreak)
        val etMorning = view.findViewById<EditText>(R.id.etMorning)
        val etNoon = view.findViewById<EditText>(R.id.etNoon)
        val etAfternoon = view.findViewById<EditText>(R.id.etAfternoon)
        val etEvening = view.findViewById<EditText>(R.id.etEvening)

        fun firstStart(section: Section, fallback: String): String =
            data.periods.firstOrNull { it.section == section }?.start ?: fallback

        etMorning.setText(firstStart(Section.MORNING, "08:00"))
        etNoon.setText(firstStart(Section.NOON, "12:45"))
        etAfternoon.setText(firstStart(Section.AFTERNOON, "14:00"))
        etEvening.setText(firstStart(Section.EVENING, "19:20"))

        AlertDialog.Builder(this)
            .setTitle("按规则生成")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("生成") { _, _ ->
                val duration = etDuration.text.toString().toIntOrNull() ?: 45
                val gap = etBreak.text.toString().toIntOrNull() ?: 10
                if (duration !in 20..120 || gap !in 0..60) {
                    toast("时长或课间超出范围")
                    return@setPositiveButton
                }
                val starts = mapOf(
                    Section.MORNING to etMorning.text.toString(),
                    Section.NOON to etNoon.text.toString(),
                    Section.AFTERNOON to etAfternoon.text.toString(),
                    Section.EVENING to etEvening.text.toString(),
                )
                for ((section, raw) in starts) {
                    if (TimetableLogic.minutesOfDay(raw) < 0) {
                        toast("${section.label}开始时间格式应为 HH:mm")
                        return@setPositiveButton
                    }
                }
                generate(duration, gap, starts)
                renderRows()
            }
            .show()
    }

    private fun generate(duration: Int, gap: Int, starts: Map<Section, String>) {
        val seen = HashSet<Section>()
        var cursor = 0
        for (p in data.periods) {
            if (p.section !in seen) {
                seen.add(p.section)
                cursor = TimetableLogic.minutesOfDay(starts[p.section] ?: "08:00")
            }
            val end = cursor + duration
            p.start = fmt(cursor)
            p.end = fmt(end)
            cursor = end + gap
        }
    }

    private fun fmt(minutes: Int): String =
        String.format(java.util.Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60)

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private data class Row(val period: Period, val start: EditText, val end: EditText)
}

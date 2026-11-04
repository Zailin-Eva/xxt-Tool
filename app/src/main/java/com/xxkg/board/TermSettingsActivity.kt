package com.xxkg.board

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

/** 学期名称、第一周周一、总周数，以及按“本周第几周”反推。 */
class TermSettingsActivity : AppCompatActivity() {

    private lateinit var data: TimetableData
    private lateinit var etName: EditText
    private lateinit var etTotal: EditText
    private lateinit var etCurrentWeek: EditText
    private lateinit var btnWeek1: TextView
    private lateinit var tvComputed: TextView
    private var week1 = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = TimetableStore.load(this)
        setContentView(R.layout.activity_term_settings)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        etName = findViewById(R.id.etTermName)
        etTotal = findViewById(R.id.etTotalWeeks)
        etCurrentWeek = findViewById(R.id.etCurrentWeek)
        btnWeek1 = findViewById(R.id.btnWeek1)
        tvComputed = findViewById(R.id.tvComputed)

        week1 = data.term.week1Monday
        etName.setText(data.term.name)
        etTotal.setText(data.term.totalWeeks.toString())
        btnWeek1.text = week1

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        btnWeek1.setOnClickListener { showDatePicker() }
        findViewById<View>(R.id.btnCalc).setOnClickListener { calcFromCurrentWeek() }

        updateComputed()
    }

    private fun showDatePicker() {
        val c = Calendar.getInstance()
        val parsed = TimetableLogic.parseDate(week1)
        if (parsed > 0L) c.timeInMillis = parsed
        DatePickerDialog(
            this,
            { _, y, m, d ->
                val cal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, y)
                    set(Calendar.MONTH, m)
                    set(Calendar.DAY_OF_MONTH, d)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                week1 = TimetableLogic.formatDate(cal.timeInMillis)
                btnWeek1.text = week1
                updateComputed()
            },
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH),
            c.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun calcFromCurrentWeek() {
        val current = etCurrentWeek.text.toString().toIntOrNull()
        if (current == null || current !in 1..30) {
            toast("请先填“本周是第几周”（1–30）")
            return
        }
        val thisMonday = TimetableLogic.mondayOf(System.currentTimeMillis())
        val start = thisMonday - (current - 1L) * 7L * TimetableLogic.DAY_MS
        week1 = TimetableLogic.formatDate(start)
        btnWeek1.text = week1
        updateComputed()
        toast("已按第 $current 周反推")
    }

    private fun updateComputed() {
        val total = etTotal.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 20
        val term = Term(etName.text.toString(), week1, total)
        val current = TimetableLogic.weekNumber(term, System.currentTimeMillis())
        tvComputed.text = if (current in 1..total) {
            "当前是第 $current 周 · ${TimetableLogic.weekRangeText(term, current)}"
        } else {
            "当前日期不在学期内（第 1 周 ${TimetableLogic.weekRangeText(term, 1)}）"
        }
    }

    private fun save() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("请填写学期名称")
            return
        }
        if (TimetableLogic.parseDate(week1) <= 0L) {
            toast("请选择第 1 周周一")
            return
        }
        val total = etTotal.text.toString().toIntOrNull()?.coerceIn(1, 30) ?: 20
        data = TimetableStore.load(this)
        data.term = Term(name, week1, total)
        if (!TimetableStore.save(this, data)) {
            toast("保存失败")
            return
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

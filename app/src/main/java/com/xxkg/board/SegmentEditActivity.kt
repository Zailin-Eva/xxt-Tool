package com.xxkg.board

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 单个时间段的编辑页：星期/节次/周次/地点/教师/隐藏。 */
class SegmentEditActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var data: TimetableData
    private lateinit var courseId: String
    private var segmentId: String? = null

    private lateinit var spDay: Spinner
    private lateinit var spStart: Spinner
    private lateinit var spEnd: Spinner
    private lateinit var weekChips: WeekChipsView
    private lateinit var etCampus: EditText
    private lateinit var etLocation: EditText
    private lateinit var etTeacher: EditText
    private lateinit var btnDelete: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        data = TimetableStore.load(this)
        courseId = intent.getStringExtra(EXTRA_COURSE_ID).orEmpty()
        segmentId = intent.getStringExtra(EXTRA_SEGMENT_ID)
        val course = data.course(courseId)
        if (course == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_segment_edit)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        spDay = findViewById(R.id.spDay)
        spStart = findViewById(R.id.spStart)
        spEnd = findViewById(R.id.spEnd)
        weekChips = findViewById(R.id.weekChips)
        etCampus = findViewById(R.id.etCampus)
        etLocation = findViewById(R.id.etLocation)
        etTeacher = findViewById(R.id.etTeacher)
        btnDelete = findViewById(R.id.btnDeleteSegment)

        val days = (1..7).map { TimetableLogic.weekday(it) }.toTypedArray()
        spDay.adapter = spinnerAdapter(days)
        val periodLabels = data.periods.map { p ->
            "第${p.no}节  ${p.start}–${p.end}"
        }.toTypedArray()
        spStart.adapter = spinnerAdapter(periodLabels)
        spEnd.adapter = spinnerAdapter(periodLabels)

        val seg = data.segments.firstOrNull { it.id == segmentId }
        val totalWeeks = data.term.totalWeeks.coerceIn(1, 30)
        if (seg != null) {
            spDay.setSelection((seg.day - 1).coerceIn(0, 6))
            spStart.setSelection((seg.startPeriod - 1).coerceIn(0, data.periods.size - 1))
            spEnd.setSelection((seg.endPeriod - 1).coerceIn(0, data.periods.size - 1))
            etCampus.setText(seg.campus)
            etLocation.setText(seg.location)
            etTeacher.setText(seg.teacher)
            weekChips.configure(totalWeeks, seg.weeks, Palette.accentColor(prefs.accent))
            btnDelete.visibility = View.VISIBLE
        } else {
            spDay.setSelection(0)
            spStart.setSelection(0)
            spEnd.setSelection(1.coerceAtMost(data.periods.size - 1))
            weekChips.configure(totalWeeks, (1..totalWeeks).toSet(), Palette.accentColor(prefs.accent))
        }

        spStart.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spEnd.selectedItemPosition < position) {
                    spEnd.setSelection(position)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnAllWeeks).setOnClickListener { weekChips.selectAll() }
        findViewById<View>(R.id.btnOddWeeks).setOnClickListener { weekChips.selectOdd() }
        findViewById<View>(R.id.btnEvenWeeks).setOnClickListener { weekChips.selectEven() }
        findViewById<View>(R.id.btnClearWeeks).setOnClickListener { weekChips.clear() }
        btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun spinnerAdapter(items: Array<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun save() {
        val start = spStart.selectedItemPosition + 1
        val end = spEnd.selectedItemPosition + 1
        if (start > end) {
            toast("结束节次不能早于开始节次")
            return
        }
        val weeks = weekChips.weeks()
        if (weeks.isEmpty()) {
            toast("至少选择一个上课周次")
            return
        }
        data = TimetableStore.load(this)
        var seg = data.segments.firstOrNull { it.id == segmentId }
        if (seg == null) {
            seg = Segment(
                id = TimetableCodec.newId(),
                courseId = courseId,
                day = spDay.selectedItemPosition + 1,
                startPeriod = start,
                endPeriod = end,
                weeks = weeks,
            )
            data.segments.add(seg)
        }
        seg.day = spDay.selectedItemPosition + 1
        seg.startPeriod = start
        seg.endPeriod = end
        seg.weeks = weeks
        seg.campus = etCampus.text.toString().trim()
        seg.location = etLocation.text.toString().trim()
        seg.teacher = etTeacher.text.toString().trim()
        seg.hidden = false
        if (!TimetableStore.save(this, data)) {
            toast("保存失败")
            return
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmDelete() {
        val id = segmentId ?: return
        AlertDialog.Builder(this)
            .setTitle("删除这个时间段")
            .setMessage("只删除这一段，课程本身和其他时间段保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val fresh = TimetableStore.load(this)
                fresh.segments.removeAll { it.id == id }
                TimetableStore.save(this, fresh)
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_SEGMENT_ID = "segment_id"
    }
}

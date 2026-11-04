package com.xxkg.board

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** 课程信息 + 时间段列表。 */
class CourseEditActivity : AppCompatActivity() {

    private lateinit var data: TimetableData
    private var courseId: String? = null
    private lateinit var etName: EditText
    private lateinit var etTeacher: EditText
    private lateinit var etCredits: EditText
    private lateinit var etNote: EditText
    private lateinit var segmentContainer: LinearLayout
    private lateinit var btnDeleteCourse: TextView
    private lateinit var quickBox: View
    private lateinit var tvQuickTime: TextView
    private lateinit var etQuickCampus: EditText
    private lateinit var etQuickLocation: EditText
    private var quickDay = 0
    private var quickStart = 0
    private var quickEnd = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = TimetableStore.load(this)
        courseId = intent.getStringExtra(EXTRA_COURSE_ID)
        setContentView(R.layout.activity_course_edit)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG

        etName = findViewById(R.id.etName)
        etTeacher = findViewById(R.id.etTeacher)
        etCredits = findViewById(R.id.etCredits)
        etNote = findViewById(R.id.etNote)
        segmentContainer = findViewById(R.id.segmentContainer)
        btnDeleteCourse = findViewById(R.id.btnDeleteCourse)
        quickBox = findViewById(R.id.quickBox)
        tvQuickTime = findViewById(R.id.tvQuickTime)
        etQuickCampus = findViewById(R.id.etQuickCampus)
        etQuickLocation = findViewById(R.id.etQuickLocation)

        val course = data.course(courseId.orEmpty())
        if (course != null) {
            etName.setText(course.name)
            etTeacher.setText(course.teacher)
            etCredits.setText(course.credits)
            etNote.setText(course.note)
            btnDeleteCourse.visibility = View.VISIBLE
        } else {
            courseId = null
            quickDay = intent.getIntExtra(EXTRA_QUICK_DAY, 0)
            quickStart = intent.getIntExtra(EXTRA_QUICK_START, 0)
            quickEnd = intent.getIntExtra(EXTRA_QUICK_END, quickStart)
            if (quickDay in 1..7 && quickStart > 0) {
                quickEnd = quickEnd.coerceAtLeast(quickStart)
                quickBox.visibility = View.VISIBLE
                tvQuickTime.text = buildString {
                    append("保存后加入：")
                    append(TimetableLogic.weekday(quickDay))
                    append(" ")
                    append(TimetableLogic.periodText(quickStart, quickEnd))
                    append("  ")
                    append(TimetableLogic.timeRange(data.periods, quickStart, quickEnd))
                }
                findViewById<View>(R.id.tvSegmentHint).visibility = View.GONE
                findViewById<View>(R.id.tvSegmentSection).visibility = View.GONE
                findViewById<View>(R.id.btnAddSegment).visibility = View.GONE
                segmentContainer.visibility = View.GONE
            }
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener {
            saveCourse()
        }
        findViewById<View>(R.id.btnAddSegment).setOnClickListener {
            if (!saveCourse(finishAfter = false)) return@setOnClickListener
            val id = courseId ?: return@setOnClickListener
            startActivityForResult(
                Intent(this, SegmentEditActivity::class.java)
                    .putExtra(SegmentEditActivity.EXTRA_COURSE_ID, id),
                REQ_SEGMENT
            )
        }
        btnDeleteCourse.setOnClickListener { confirmDeleteCourse() }

        refreshSegments()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SEGMENT) {
            this.data = TimetableStore.load(this)
            refreshSegments()
        }
    }

    private fun saveCourse(finishAfter: Boolean = true): Boolean {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            toast("请填写课程名称")
            etName.requestFocus()
            return false
        }
        data = TimetableStore.load(this)
        var course = data.course(courseId.orEmpty())
        if (course == null) {
            course = TtCourse(id = TimetableCodec.newId(), name = name)
            data.courses.add(course)
            courseId = course.id
        }
        course.name = name
        course.teacher = etTeacher.text.toString().trim()
        course.credits = etCredits.text.toString().trim()
        course.note = etNote.text.toString().trim()
        if (quickDay in 1..7 && quickStart > 0 && data.segmentsOf(course.id).isEmpty()) {
            data.segments.add(
                Segment(
                    id = TimetableCodec.newId(),
                    courseId = course.id,
                    day = quickDay,
                    startPeriod = quickStart,
                    endPeriod = quickEnd.coerceAtLeast(quickStart),
                    weeks = (1..data.term.totalWeeks.coerceAtLeast(1)).toMutableSet(),
                    campus = etQuickCampus.text.toString().trim(),
                    location = etQuickLocation.text.toString().trim(),
                )
            )
        }
        if (!TimetableStore.save(this, data)) {
            toast("保存失败")
            return false
        }
        btnDeleteCourse.visibility = View.VISIBLE
        refreshSegments()
        if (finishAfter) finish() else toast("已保存，可以添加时间段")
        return true
    }

    private fun refreshSegments() {
        val id = courseId ?: run {
            segmentContainer.removeAllViews()
            return
        }
        val fresh = TimetableStore.load(this)
        data = fresh
        segmentContainer.removeAllViews()
        val segments = fresh.segmentsOf(id)
        if (segments.isEmpty()) {
            val empty = TextView(this).apply {
                text = "还没有时间段，点下面的“添加时间段”。"
                setTextColor(Palette.MUTED2)
                textSize = 11f
                setPadding(0, 8, 0, 8)
            }
            segmentContainer.addView(empty)
            return
        }
        for (s in segments) {
            val row = layoutInflater.inflate(R.layout.item_course_segment, segmentContainer, false)
            row.findViewById<TextView>(R.id.tvSegmentSummary).text = buildString {
                append(TimetableLogic.weekday(s.day))
                append("  ")
                append(TimetableLogic.periodText(s.startPeriod, s.endPeriod))
                append("  ")
                append(TimetableLogic.timeRange(fresh.periods, s.startPeriod, s.endPeriod))
                append("\n")
                append(TimetableLogic.weeksText(s.weeks))
                val room = listOf(s.campus, s.location).filter { it.isNotBlank() }.joinToString(" ")
                if (room.isNotBlank()) {
                    append("\n")
                    append(room)
                }
            }
            row.findViewById<View>(R.id.btnEditSegment).setOnClickListener {
                startActivityForResult(
                    Intent(this, SegmentEditActivity::class.java)
                        .putExtra(SegmentEditActivity.EXTRA_COURSE_ID, id)
                        .putExtra(SegmentEditActivity.EXTRA_SEGMENT_ID, s.id),
                    REQ_SEGMENT
                )
            }
            row.findViewById<View>(R.id.btnDeleteSegment).setOnClickListener {
                confirmDeleteSegment(s.id)
            }
            segmentContainer.addView(row)
        }
    }

    private fun confirmDeleteSegment(segmentId: String) {
        AlertDialog.Builder(this)
            .setTitle("删除这个时间段")
            .setMessage("只删除这一段，课程本身和其他时间段保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val fresh = TimetableStore.load(this)
                fresh.segments.removeAll { it.id == segmentId }
                TimetableStore.save(this, fresh)
                data = fresh
                refreshSegments()
            }
            .show()
    }

    private fun confirmDeleteCourse() {
        val id = courseId ?: return
        AlertDialog.Builder(this)
            .setTitle("删除课程")
            .setMessage("课程信息和它的全部时间段都会删除，且不可恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                val fresh = TimetableStore.load(this)
                TimetableLogic.deleteCourse(fresh, id)
                TimetableStore.save(this, fresh)
                finish()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_COURSE_ID = "course_id"
        const val EXTRA_QUICK_DAY = "quick_day"
        const val EXTRA_QUICK_START = "quick_start"
        const val EXTRA_QUICK_END = "quick_end"
        private const val REQ_SEGMENT = 3201
    }
}


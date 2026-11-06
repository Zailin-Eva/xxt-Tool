package com.xxkg.board

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/** 课程表主页面。 */
class TimetableActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var data: TimetableData
    private var week = 1
    private var detail: Dialog? = null
    private var pendingCell: Pair<Int, Int>? = null

    private lateinit var tvWeek: TextView
    private lateinit var tvTerm: TextView
    private lateinit var tvUnplaced: TextView
    private lateinit var unplacedDivider: View
    private lateinit var header: TimetableHeaderView
    private lateinit var grid: TimetableView
    private lateinit var scroll: android.widget.ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        data = TimetableStore.load(this)
        setContentView(R.layout.activity_timetable)

        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        tvWeek = findViewById(R.id.tvWeek)
        tvTerm = findViewById(R.id.tvTerm)
        tvUnplaced = findViewById(R.id.tvUnplaced)
        unplacedDivider = findViewById(R.id.unplacedDivider)
        header = findViewById(R.id.header)
        grid = findViewById(R.id.grid)
        scroll = findViewById(R.id.scroll)

        week = TimetableLogic.currentWeek(data.term)

        findViewById<ImageButton>(R.id.btnPrevWeek).setOnClickListener { changeWeek(-1) }
        findViewById<ImageButton>(R.id.btnNextWeek).setOnClickListener { changeWeek(1) }
        findViewById<ImageButton>(R.id.btnWeekPicker).setOnClickListener { showWeekPicker() }
        tvWeek.setOnClickListener { showWeekPicker() }
        findViewById<TextView>(R.id.btnToday).setOnClickListener {
            week = TimetableLogic.currentWeek(data.term)
            clearPending()
            render()
            scroll.post { scrollToCurrent() }
        }
        findViewById<ImageButton>(R.id.btnTimetableSettings).setOnClickListener {
            startActivity(Intent(this, TimetableSettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btnAddCourse).setOnClickListener {
            clearPending()
            startActivityForResult(Intent(this, CourseEditActivity::class.java), REQ_EDIT)
        }

        grid.onCourseClick = { courseId ->
            clearPending()
            showCourseDetail(courseId)
        }
        grid.onEmptyCellClick = { day, period -> onEmptyCellTap(day, period) }
        grid.onWeekSwipe = { delta -> changeWeek(delta) }
        tvUnplaced.setOnClickListener { showUnplaced() }

        findViewById<LinearLayout>(R.id.navHome).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
        findViewById<LinearLayout>(R.id.navTimetable).setOnClickListener { }
        findViewById<LinearLayout>(R.id.navMe).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        render()
        scroll.post { scrollToCurrent() }
    }

    override fun onResume() {
        super.onResume()
        data = TimetableStore.load(this)
        week = week.coerceIn(1, data.term.totalWeeks.coerceAtLeast(1))
        clearPending()
        render()
    }

    private fun onEmptyCellTap(day: Int, period: Int) {
        val current = pendingCell
        if (current != null && current.first == day && current.second == period) {
            pendingCell = null
            grid.clearPendingCell()
            startQuickAdd(day, period)
        } else {
            pendingCell = day to period
            grid.setPendingCell(day, period)
        }
    }

    private fun startQuickAdd(day: Int, period: Int) {
        startActivityForResult(
            Intent(this, CourseEditActivity::class.java)
                .putExtra(CourseEditActivity.EXTRA_QUICK_DAY, day)
                .putExtra(CourseEditActivity.EXTRA_QUICK_START, period),
            REQ_EDIT
        )
    }

    private fun clearPending() {
        pendingCell = null
        if (::grid.isInitialized) grid.clearPendingCell()
    }

    override fun onDestroy() {
        detail?.dismiss()
        detail = null
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_EDIT) {
            this.data = TimetableStore.load(this)
            render()
        }
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val accent = Palette.accentColor(prefs.accent)
        tvWeek.text = "第 $week 周"
        tvTerm.text = data.term.name
        header.setData(data.term, week, System.currentTimeMillis(), accent)
        grid.setData(data, week, System.currentTimeMillis(), accent, prefs.showNonCurrentWeek)

        val n = TimetableLogic.unscheduledCount(data)
        if (n > 0) {
            unplacedDivider.visibility = View.VISIBLE
            tvUnplaced.visibility = View.VISIBLE
            tvUnplaced.text = "未排课 $n · 点击查看 ›"
        } else {
            unplacedDivider.visibility = View.GONE
            tvUnplaced.visibility = View.GONE
        }
    }

    private fun changeWeek(delta: Int) {
        clearPending()
        val total = data.term.totalWeeks.coerceAtLeast(1)
        val target = (week + delta).coerceIn(1, total)
        if (target == week) return
        week = target
        render()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun scrollToCurrent() {
        if (data.courses.isEmpty()) {
            scroll.scrollTo(0, 0)
            return
        }
        if (TimetableLogic.weekNumber(data.term, System.currentTimeMillis()) != week) return
        val top = grid.currentPeriodTop() - dp(52)
        scroll.smoothScrollTo(0, top.coerceAtLeast(0))
    }

    private fun showWeekPicker() {
        val total = data.term.totalWeeks.coerceAtLeast(1)
        val items = (1..total).map {
            "第 $it 周 · ${TimetableLogic.weekShortRange(data.term, it)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择周次")
            .setItems(items) { _, which ->
                clearPending()
                week = which + 1
                render()
                scroll.post { scroll.scrollTo(0, 0) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------ 详情

    private fun showCourseDetail(courseId: String) {
        data = TimetableStore.load(this)
        val course = data.course(courseId) ?: return
        val view = layoutInflater.inflate(R.layout.dialog_timetable_detail, null)
        val container = view.findViewById<LinearLayout>(R.id.segmentContainer)
        val detailScroll = view.findViewById<MaxHeightScrollView>(R.id.detailScroll)
        detailScroll.maxHeight = (resources.displayMetrics.heightPixels * 0.58f).toInt()

        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)
        dialog.setOnDismissListener { detail = null }

        view.findViewById<TextView>(R.id.btnCourseInfo).setOnClickListener {
            dialog.dismiss()
            startActivityForResult(
                Intent(this, CourseEditActivity::class.java).putExtra(CourseEditActivity.EXTRA_COURSE_ID, courseId),
                REQ_EDIT
            )
        }

        val segments = TimetableLogic.sortedSegments(data, courseId, week)
        if (segments.isEmpty()) {
            val empty = TextView(this).apply {
                text = "这门课还没有时间段，点右上角「课程信息」添加。"
                setTextColor(Palette.MUTED)
                textSize = 12f
                setPadding(0, dp(24), 0, dp(24))
                gravity = Gravity.CENTER
            }
            container.addView(empty)
        } else {
            for (s in segments) {
                container.addView(buildSegmentCard(course, s, dialog))
            }
        }

        detail = dialog
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    private fun buildSegmentCard(course: TtCourse, s: Segment, dialog: Dialog): View {
        val card = layoutInflater.inflate(R.layout.item_segment_card, null, false)
        val active = s.weeks.contains(week)
        card.setBackgroundResource(if (active) R.drawable.bg_card else R.drawable.bg_card_off)

        val badge = card.findViewById<TextView>(R.id.tvBadge)
        if (!active) {
            badge.text = "非本周"
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }

        card.findViewById<TextView>(R.id.tvCourseName).text = course.name

        card.findViewById<TextView>(R.id.tvWeeks).text = TimetableLogic.weeksText(s.weeks)

        card.findViewById<TextView>(R.id.tvTime).text = buildString {
            append(TimetableLogic.weekday(s.day))
            append("  |  ")
            append(TimetableLogic.periodText(s.startPeriod, s.endPeriod))
            append("  |  ")
            append(TimetableLogic.timeRange(data.periods, s.startPeriod, s.endPeriod))
        }

        val room = listOf(s.campus, s.location).filter { it.isNotBlank() }.joinToString(" ")
        card.findViewById<TextView>(R.id.tvRoom).text =
            "教室：" + room.ifBlank { "未填写" }

        val teacher = s.teacher.ifBlank { course.teacher }
        card.findViewById<TextView>(R.id.tvTeacher).text =
            "老师：" + teacher.ifBlank { "未填写" }

        card.findViewById<TextView>(R.id.btnEdit).setOnClickListener {
            dialog.dismiss()
            startActivityForResult(
                Intent(this, SegmentEditActivity::class.java)
                    .putExtra(SegmentEditActivity.EXTRA_COURSE_ID, course.id)
                    .putExtra(SegmentEditActivity.EXTRA_SEGMENT_ID, s.id),
                REQ_EDIT
            )
        }

        return card
    }

    private fun showUnplaced() {
        data = TimetableStore.load(this)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        for (u in data.unscheduled) {
            labels.add("${u.name} · ${TimetableLogic.weeksText(u.weeks)}")
            actions.add {
                AlertDialog.Builder(this)
                    .setTitle(u.name)
                    .setMessage(
                        "导入的未排课条目。\n" +
                            "周次：${TimetableLogic.weeksText(u.weeks)}\n" +
                            "教师：${u.teacher.ifBlank { "未填写" }}\n\n" +
                            "2.6.0 支持把未排课条目一键安排到具体时间段；现在可以先在课程信息里手动添加。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        for (c in data.courses) {
            if (data.segments.none { it.courseId == c.id }) {
                labels.add("${c.name} · 未设置时间段")
                actions.add {
                    startActivityForResult(
                        Intent(this, CourseEditActivity::class.java)
                            .putExtra(CourseEditActivity.EXTRA_COURSE_ID, c.id),
                        REQ_EDIT
                    )
                }
            }
        }
        if (labels.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("未排课")
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_EDIT = 3101
    }
}


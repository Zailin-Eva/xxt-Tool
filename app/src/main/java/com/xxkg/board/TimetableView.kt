package com.xxkg.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** 课程表主体：7 列 × 节次，自绘网格、课程块、今天列与当前时间线。 */
class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val rowH = 52f * density
    private val leftW = 44f * density

    private var data: TimetableData? = null
    private var week = 1
    private var today = System.currentTimeMillis()
    private var accent = Palette.accentColor("clay")
    private var showNonCurrent = true

    private var pendingDay = 0
    private var pendingPeriod = 0
    private val pencilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val pendingFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val hits = ArrayList<Hit>()

    var onCourseClick: ((String) -> Unit)? = null
    var onWeekSwipe: ((Int) -> Unit)? = null
    var onEmptyCellClick: ((Int, Int) -> Unit)? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.LINE
        strokeWidth = 0.8f * density
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.BG }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE_ALT }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Palette.CARD_BORDER
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8F8A80.toInt() }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.2f * density
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        textSize = 9.6f * scaled
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED2
        textSize = 7.0f * scaled
        textAlign = Paint.Align.CENTER
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.6f * scaled
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        color = Palette.INK
    }
    private val locPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 7.4f * scaled
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Palette.MUTED
    }
    private val teacherPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 7.1f * scaled
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Palette.MUTED2
    }
    private val pillTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 6.2f * scaled
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = hits.lastOrNull { it.rect.contains(e.x, e.y) }
            if (hit != null) {
                onCourseClick?.invoke(hit.courseId)
                return true
            }
            val d = data ?: return false
            if (e.x < leftW) return false
            val colW = (width - leftW) / 7f
            if (colW <= 0f) return false
            val day = ((e.x - leftW) / colW).toInt() + 1
            val period = (e.y / rowH).toInt() + 1
            if (day !in 1..7 || period !in 1..d.periods.size) return false
            onEmptyCellClick?.invoke(day, period)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val a = e1 ?: return false
            val dx = e2.x - a.x
            val dy = e2.y - a.y
            if (abs(dx) > abs(dy) * 1.4f && abs(dx) > 52f * density) {
                onWeekSwipe?.invoke(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    fun setData(
        data: TimetableData,
        week: Int,
        today: Long,
        accent: Int,
        showNonCurrent: Boolean,
    ) {
        this.data = data
        this.week = week
        this.today = today
        this.accent = accent
        this.showNonCurrent = showNonCurrent
        nowPaint.color = accent
        todayPaint.color = withAlpha(accent, 14)
        pencilPaint.color = accent
        pendingFill.color = withAlpha(accent, 28)
        requestLayout()
        invalidate()
    }

    fun setPendingCell(day: Int, period: Int) {
        pendingDay = day
        pendingPeriod = period
        invalidate()
    }

    fun clearPendingCell() {
        pendingDay = 0
        pendingPeriod = 0
        invalidate()
    }

    /** 自动滚动用：当前/下一节课的顶部偏移。 */
    fun currentPeriodTop(): Int {
        val d = data ?: return 0
        if (!TimetableLogic.isInTerm(d.term, today)) return 0
        val now = TimetableLogic.nowMinutes()
        val current = TimetableLogic.currentPeriod(d.periods, now)
        val index = when {
            current != null -> d.periods.indexOfFirst { it.no == current.no }
            else -> d.periods.indexOfFirst { TimetableLogic.minutesOfDay(it.start) > now }
        }
        return (index.coerceAtLeast(0) * rowH).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val count = data?.periods?.size ?: 13
        val h = (count * rowH).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val d = data ?: return
        val periods = d.periods
        if (periods.isEmpty()) return
        val colW = (w - leftW) / 7f

        if (TimetableLogic.weekNumber(d.term, today) == week) {
            val col = TimetableLogic.dayOfWeek(today) - 1
            canvas.drawRect(
                leftW + col * colW, 0f,
                leftW + (col + 1) * colW, h,
                todayPaint
            )
        }

        for (i in 0..7) {
            val x = leftW + i * colW
            canvas.drawLine(x, 0f, x, h, linePaint)
        }
        for (i in 0..periods.size) {
            val y = i * rowH
            canvas.drawLine(0f, y, w, y, linePaint)
        }

        for ((i, p) in periods.withIndex()) {
            val cy = i * rowH + rowH / 2f
            canvas.drawText(p.no.toString(), leftW / 2f, cy - 4f * density, numPaint)
            canvas.drawText(p.start, leftW / 2f, cy + 7f * density, smallPaint)
            canvas.drawText(p.end, leftW / 2f, cy + 15f * density, smallPaint)
        }

        // 当前时间线画在课程卡下面，避免红线穿过课程文字
        if (TimetableLogic.weekNumber(d.term, today) == week) {
            val now = TimetableLogic.nowMinutes()
            val p = TimetableLogic.currentPeriod(periods, now)
            if (p != null) {
                val i = periods.indexOfFirst { it.no == p.no }
                val progress = TimetableLogic.progressInPeriod(p, now)
                if (i >= 0 && progress >= 0f) {
                    val y = (i + progress) * rowH
                    canvas.drawLine(leftW, y, w, y, nowPaint)
                    canvas.drawCircle(leftW, y, 3.2f * density, nowPaint)
                }
            }
        }

        hits.clear()
        val activeSlots = HashSet<String>()
        for (s in d.segments) {
            if (!s.hidden && s.weeks.contains(week)) {
                activeSlots.add(slotKey(s))
            }
        }
        val drawnGrey = HashSet<String>()
        for (s in d.segments) {
            if (s.hidden) continue
            val active = s.weeks.contains(week)
            if (!active && !showNonCurrent) continue
            val key = slotKey(s)
            if (!active) {
                if (activeSlots.contains(key)) continue
                if (!drawnGrey.add(key)) continue
            }
            val course = d.course(s.courseId) ?: continue
            drawCard(canvas, s, course, active, colW)
        }

        drawPendingCell(canvas, colW)
    }

    private fun drawPendingCell(canvas: Canvas, colW: Float) {
        if (pendingDay !in 1..7 || pendingPeriod <= 0) return
        val d = data ?: return
        if (pendingPeriod > d.periods.size) return
        val left = leftW + (pendingDay - 1) * colW + 2f * density
        val right = leftW + pendingDay * colW - 2f * density
        val top = (pendingPeriod - 1) * rowH + 2f * density
        val bottom = pendingPeriod * rowH - 2f * density
        if (right <= left || bottom <= top) return
        canvas.drawRoundRect(
            left, top, right, bottom,
            10f * density, 10f * density, pendingFill
        )
        canvas.drawRoundRect(
            left, top, right, bottom,
            10f * density, 10f * density, borderPaint.also { it.color = accent }
        )
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val arm = 6f * density
        canvas.drawLine(cx - arm, cy, cx + arm, cy, pencilPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, pencilPaint)
    }

    private fun drawCard(
        canvas: Canvas,
        s: Segment,
        course: TtCourse,
        active: Boolean,
        colW: Float,
    ) {
        val left = leftW + (s.day - 1) * colW + 2f * density
        val right = leftW + s.day * colW - 2f * density
        val top = (s.startPeriod - 1) * rowH + 2f * density
        val bottom = s.endPeriod * rowH - 2f * density
        if (right <= left || bottom <= top) return

        val rect = RectF(left, top, right, bottom)
        cardPaint.color = if (active) Palette.SURFACE_ALT else Palette.NONCURRENT_BG
        canvas.drawRoundRect(rect, 10f * density, 10f * density, cardPaint)
        borderPaint.color = if (active) Palette.CARD_BORDER else 0xFFDED7C8.toInt()
        canvas.drawRoundRect(rect, 10f * density, 10f * density, borderPaint)

        canvas.save()
        canvas.clipRect(rect)

        val textX = left + 4f * density
        var textY = top + 4.5f * density
        if (!active) {
            val label = "非本周"
            val pillW = pillTextPaint.measureText(label) + 5f * density
            val pillH = 8.6f * density
            canvas.drawRoundRect(
                left + 3.5f * density, top + 3.5f * density,
                left + 3.5f * density + pillW, top + 3.5f * density + pillH,
                2.5f * density, 2.5f * density, pillPaint
            )
            canvas.drawText(
                label,
                left + 3.5f * density + pillW / 2f,
                top + 3.5f * density + pillH - 2.2f * density,
                pillTextPaint
            )
            textY = top + 14.5f * density
        }

        val textW = (rect.width() - 8f * density).toInt().coerceAtLeast(8)
        titlePaint.color = if (active) Palette.INK else Palette.NONCURRENT_INK
        locPaint.color = if (active) Palette.MUTED else Palette.NONCURRENT_INK
        teacherPaint.color = if (active) Palette.MUTED2 else Palette.NONCURRENT_INK

        val available = (rect.bottom - textY - 3f * density)
        val titleLineH = titlePaint.fontSpacing * 1.05f
        val titleLines = (available / titleLineH).toInt().coerceIn(1, 4)
        val titleH = drawLayout(canvas, course.name, titlePaint, textW, titleLines, textX, textY)

        // 名称、地点、教师都按可用高度自动换行；只有整块真的放不下时才省略
        val gap = 1.5f * density
        val bodyLineH = locPaint.fontSpacing * 1.05f
        val bottomLimit = rect.bottom - 3f * density
        var y = textY + titleH + gap

        val location = listOf(s.campus, s.location).filter { it.isNotBlank() }.joinToString(" ")
        val teacher = s.teacher.ifBlank { course.teacher }

        if (location.isNotEmpty() && bottomLimit - y >= bodyLineH * 0.9f) {
            var locLines = ((bottomLimit - y) / bodyLineH).toInt()
            if (teacher.isNotBlank()) locLines -= 1
            if (locLines >= 1) {
                val locH = drawLayout(canvas, location, locPaint, textW, locLines, textX, y)
                y += locH + gap
            }
        }

        if (teacher.isNotBlank() && bottomLimit - y >= bodyLineH * 0.9f) {
            val teacherLines = ((bottomLimit - y) / bodyLineH).toInt().coerceAtLeast(1)
            drawLayout(canvas, teacher, teacherPaint, textW, teacherLines, textX, y)
        }

        canvas.restore()
        hits.add(Hit(rect, course.id))
    }

    private fun drawLayout(
        canvas: Canvas,
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int,
        x: Float,
        y: Float,
    ): Int {
        if (text.isBlank() || width <= 0) return 0
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
        return layout.height
    }

    private fun slotKey(s: Segment): String =
        "${s.courseId}|${s.day}|${s.startPeriod}|${s.endPeriod}"

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > dy && dx > touchSlop) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        gesture.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private data class Hit(val rect: RectF, val courseId: String)
}


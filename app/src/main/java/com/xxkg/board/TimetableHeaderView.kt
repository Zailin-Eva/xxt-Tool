package com.xxkg.board

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/** 课表顶部：月份 + 星期/日期，今天用强调色圆点。 */
class TimetableHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val leftW = 44f * density

    private var term: Term? = null
    private var week = 1
    private var today = System.currentTimeMillis()
    private var accent = Palette.accentColor("clay")

    private val monthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED
        textSize = 10f * scaled
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.MUTED
        textSize = 9.5f * scaled
        textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        textSize = 14.5f * scaled
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.INK
        textSize = 14.5f * scaled
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.LINE
        strokeWidth = 1f * density
    }

    fun setData(term: Term, week: Int, today: Long, accent: Int) {
        this.term = term
        this.week = week
        this.today = today
        this.accent = accent
        fillPaint.color = accent
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (56f * density).toInt()
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        canvas.drawColor(Palette.BG)

        val colW = (w - leftW) / 7f
        val t = term ?: return

        val start = TimetableLogic.weekStart(t, week)
        if (start > 0L) {
            val month = TimetableLogic.monthLabel(start)
            drawMonth(canvas, month)
        }

        val todayIndex = if (TimetableLogic.weekNumber(t, today) == week) {
            TimetableLogic.dayOfWeek(today)
        } else {
            -1
        }
        for (i in 0 until 7) {
            val cx = leftW + colW * i + colW / 2f
            val isToday = i + 1 == todayIndex
            val dayPaint = if (isToday) {
                Paint(weekPaint).apply { color = accent }
            } else {
                weekPaint
            }
            canvas.drawText(TimetableLogic.weekdayShort(i + 1), cx, 17f * density, dayPaint)

            val dateMs = start + i * TimetableLogic.DAY_MS
            val day = Calendar.getInstance().apply { timeInMillis = dateMs }
                .get(Calendar.DAY_OF_MONTH)
            if (isToday) {
                canvas.drawCircle(cx, 35f * density, 12f * density, fillPaint)
                canvas.drawText(day.toString(), cx, 39.5f * density, Paint(todayPaint).apply { color = 0xFFFFFFFF.toInt() })
            } else {
                canvas.drawText(day.toString(), cx, 39.5f * density, datePaint)
            }
        }

        canvas.drawLine(0f, h - 1f, w, h - 1f, linePaint)
        canvas.drawLine(leftW, 0f, leftW, h, linePaint)
    }

    private fun drawMonth(canvas: Canvas, month: String) {
        val cx = leftW / 2f
        val cleaned = month.replace("/", "/")
        val number = cleaned.removeSuffix("月")
        if (number.contains("/")) {
            canvas.drawText(number, cx, 22f * density, monthPaint)
            canvas.drawText("月", cx, 34f * density, monthPaint)
        } else {
            canvas.drawText(number, cx, 22f * density, monthPaint)
            canvas.drawText("月", cx, 34f * density, monthPaint)
        }
    }
}


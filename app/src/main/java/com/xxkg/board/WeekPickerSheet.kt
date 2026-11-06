package com.xxkg.board

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 与课表同风格的“选择周次”底部弹层：5 列周次卡片 + 回到本周。 */
object WeekPickerSheet {

    fun show(
        activity: Activity,
        term: Term,
        currentWeek: Int,
        selectedWeek: Int,
        accentColor: Int,
        onPick: (Int) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_week_picker, null)
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        view.findViewById<TextView>(R.id.tvWeekTerm).text =
            "${term.name} · 共 ${term.totalWeeks} 周"

        val total = term.totalWeeks.coerceIn(1, 30)
        val todayWeek = if (currentWeek in 1..total) currentWeek else 0
        val grid = view.findViewById<LinearLayout>(R.id.weekGrid)

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)

        var week = 1
        while (week <= total) {
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 5) {
                val n = week + i
                if (n <= total) {
                    row.addView(
                        chip(activity, n, n == selectedWeek, n == todayWeek, accentColor, { v: Int -> dp(v) }) {
                            onPick(n)
                            dialog.dismiss()
                        }
                    )
                } else {
                    row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
                }
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
            week += 5
        }

        view.findViewById<View>(R.id.btnThisWeek).setOnClickListener {
            onPick(if (todayWeek == 0) 1 else todayWeek)
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.setGravity(Gravity.BOTTOM)
    }

    private fun chip(
        activity: Activity,
        week: Int,
        selected: Boolean,
        isCurrent: Boolean,
        accent: Int,
        dp: (Int) -> Int,
        onClick: () -> Unit,
    ): TextView {
        val textColor = when {
            selected -> Color.WHITE
            isCurrent -> accent
            else -> Palette.INK
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            when {
                selected -> setColor(accent)
                isCurrent -> {
                    setColor(Palette.SURFACE)
                    setStroke(dp(1).coerceAtLeast(1), accent)
                }
                else -> setColor(Palette.SURFACE_ALT)
            }
        }
        return TextView(activity).apply {
            text = week.toString()
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(textColor)
            background = bg
            isClickable = true
            isFocusable = true
            setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
        }
    }
}

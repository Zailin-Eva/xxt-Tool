package com.xxkg.board

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView

/** 1..N 周的彩色小方块，支持单选/全选/单周/双周/清空。 */
class WeekChipsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private var total = 20
    private var accent = Palette.accentColor("clay")
    private val selected = sortedSetOf<Int>()
    private val chips = HashMap<Int, TextView>()

    var onChanged: (() -> Unit)? = null

    init {
        orientation = VERTICAL
    }

    fun configure(totalWeeks: Int, weeks: Set<Int>, accentColor: Int) {
        total = totalWeeks.coerceIn(1, 30)
        accent = accentColor
        selected.clear()
        selected.addAll(weeks.filter { it in 1..total })
        rebuild()
    }

    fun weeks(): MutableSet<Int> = sortedSetOf<Int>().apply { addAll(selected) }

    fun setWeeks(weeks: Set<Int>) {
        selected.clear()
        selected.addAll(weeks.filter { it in 1..total })
        refreshChips()
    }

    fun selectAll() {
        selected.clear()
        selected.addAll(1..total)
        refreshChips()
    }

    fun selectOdd() {
        selected.clear()
        selected.addAll((1..total).filter { it % 2 == 1 })
        refreshChips()
    }

    fun selectEven() {
        selected.clear()
        selected.addAll((1..total).filter { it % 2 == 0 })
        refreshChips()
    }

    fun clear() {
        selected.clear()
        refreshChips()
    }

    private fun rebuild() {
        removeAllViews()
        chips.clear()
        var row: LinearLayout? = null
        for (w in 1..total) {
            if ((w - 1) % 7 == 0) {
                val r = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4f)
                    }
                }
                addView(r)
                row = r
            }
            val chip = TextView(context).apply {
                text = w.toString()
                gravity = android.view.Gravity.CENTER
                textSize = 11f
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (!selected.add(w)) selected.remove(w)
                    applyChip(w)
                    onChanged?.invoke()
                }
            }
            val lp = LayoutParams(0, dp(34f), 1f).apply {
                leftMargin = dp(2f)
                rightMargin = dp(2f)
            }
            row?.addView(chip, lp)
            chips[w] = chip
            applyChip(w)
        }
        if (total % 7 != 0) {
            // 补齐最后一行，避免最后几个格子被拉宽
            val missing = 7 - (total % 7)
            repeat(missing) {
                val spacer = TextView(context)
                row?.addView(spacer, LayoutParams(0, dp(34f), 1f).apply {
                    leftMargin = dp(2f)
                    rightMargin = dp(2f)
                })
            }
        }
    }

    private fun refreshChips() {
        for (w in chips.keys) applyChip(w)
        onChanged?.invoke()
    }

    private fun applyChip(week: Int) {
        val chip = chips[week] ?: return
        val isOn = week in selected
        chip.background = GradientDrawable().apply {
            cornerRadius = 8f * density
            setColor(if (isOn) accent else Palette.SURFACE)
            if (!isOn) setStroke(dp(1f).toInt(), Palette.LINE)
        }
        chip.setTextColor(if (isOn) 0xFFFFFFFF.toInt() else Palette.INK)
    }

    private fun dp(v: Float): Int = (v * density).toInt()
}


package com.xxkg.board

/** 课程表新 UI 的配色与字体约定。 */
object Palette {

    val BG = 0xFFF7F5EF.toInt()
    val SURFACE = 0xFFFFFFFF.toInt()
    val SURFACE_ALT = 0xFFEFEAE0.toInt()
    val INK = 0xFF141413.toInt()
    val MUTED = 0xFF6E6960.toInt()
    val MUTED2 = 0xFFA39D92.toInt()
    val LINE = 0xFFE5E0D5.toInt()
    val CARD_BORDER = 0xFFE2DACB.toInt()
    val NONCURRENT_BG = 0xFFE9E4D9.toInt()
    val NONCURRENT_INK = 0xFF9B958A.toInt()
    val DANGER = 0xFFB4483A.toInt()

    data class Accent(val key: String, val label: String, val color: Int, val soft: Int)

    val ACCENTS = listOf(
        Accent("clay", "陶土", 0xFFC96442.toInt(), 0xFFF3E3DA.toInt()),
        Accent("sage", "鼠尾草", 0xFF6F7F5E.toInt(), 0xFFE8EDE3.toInt()),
        Accent("ink", "墨蓝", 0xFF3F5C74.toInt(), 0xFFE3E9EF.toInt()),
        Accent("teal", "深青", 0xFF2F6B66.toInt(), 0xFFE0ECE9.toInt()),
        Accent("graphite", "炭黑", 0xFF2A2A27.toInt(), 0xFFE8E6E0.toInt()),
    )

    fun accent(key: String?): Accent = ACCENTS.firstOrNull { it.key == key } ?: ACCENTS[0]

    fun accentColor(key: String?): Int = accent(key).color

    fun accentSoft(key: String?): Int = accent(key).soft
}

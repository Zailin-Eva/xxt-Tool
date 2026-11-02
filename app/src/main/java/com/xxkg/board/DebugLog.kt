package com.xxkg.board

/** 简单环形日志，供「抓取日志」页面查看，定位接口失效。 */
object DebugLog {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(msg: String) {
        val ts = TimeFmt.hmss(System.currentTimeMillis())
        lines.addLast("$ts  $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}

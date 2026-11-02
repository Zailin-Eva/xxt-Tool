package com.xxkg.board

import org.json.JSONObject

enum class TaskType {
    WORK, EXAM;

    val label: String
        get() = if (this == WORK) "作业" else "考试"
}

/**
 * 学习通列表页的 status 属性：
 * 1 = 未交，2 = 已过期未交，3 = 待批阅，4 = 已完成
 */
enum class TaskStatus { TODO, EXPIRED, SUBMITTED, GRADED }

/** 统一后的任务条目（作业或考试）。 */
class Task(
    var key: String = "",
    var title: String = "",
    var courseName: String = "",
    var courseId: String = "",
    var classId: String = "",
    var type: TaskType = TaskType.WORK,
    var status: TaskStatus = TaskStatus.TODO,
    var endTime: Long = 0L,
    var publishTime: Long = 0L,
    /** 详情页里的开始时间（列表页只有发布日期，详情页才有精确时间） */
    var startTime: Long = 0L,
    /** 进入当前状态的时刻：未交/过期=发布时间，待批阅=观测到已交的时刻 */
    var stageTime: Long = 0L,
    var grade: String = "",
    var url: String = "",
    var statusText: String = "",
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("k", key)
        o.put("t", title)
        o.put("cn", courseName)
        o.put("ci", courseId)
        o.put("cl", classId)
        o.put("ty", type.name)
        o.put("st", status.name)
        o.put("et", endTime)
        o.put("pt", publishTime)
        o.put("stt", startTime)
        o.put("stg", stageTime)
        o.put("g", grade)
        o.put("u", url)
        o.put("sx", statusText)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Task = Task(
            key = o.optString("k"),
            title = o.optString("t"),
            courseName = o.optString("cn"),
            courseId = o.optString("ci"),
            classId = o.optString("cl"),
            type = runCatching { TaskType.valueOf(o.optString("ty")) }.getOrDefault(TaskType.WORK),
            status = runCatching { TaskStatus.valueOf(o.optString("st")) }.getOrDefault(TaskStatus.TODO),
            endTime = o.optLong("et"),
            publishTime = o.optLong("pt"),
            startTime = o.optLong("stt"),
            stageTime = o.optLong("stg"),
            grade = o.optString("g"),
            url = o.optString("u"),
            statusText = o.optString("sx"),
        )

        fun encode(list: List<Task>): String {
            val arr = org.json.JSONArray()
            for (t in list) arr.put(t.toJson())
            return arr.toString()
        }

        fun decode(raw: String): List<Task> {
            if (raw.isBlank()) return emptyList()
            return try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { runCatching { fromJson(it) }.getOrNull() }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

data class Course(
    val courseId: String,
    val classId: String,
    val name: String,
    val cpi: String,
)

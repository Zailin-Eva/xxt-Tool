package com.xxkg.board

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 雨课堂（默认长江雨课堂）数据抓取。
 *
 * 课程：GET /v2/api/web/courses/list?identity=2
 * 活动：GET /v2/api/web/logs/learn/{classroom_id}?actype=-1&page=0&offset=100&sort=-1
 * 其中 type=5 是习题/考试（带 status/score/deadline），type=19 是作业（带 leaf_id/score_d）。
 *
 * 请求头：Cookie、xtbz: ykt、X-CSRFToken（从 Cookie 的 csrftoken 取）。
 * 雨课堂没有公开 API，接口变动时需要跟着调整。
 */
class YtApi(private val prefs: Prefs) : TaskSource {

    companion object {
        const val DEFAULT_HOST = "www.yuketang.cn"
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun loginUrl(host: String): String = "https://$host/web"
        fun courseUrl(host: String, classroomId: String): String =
            "https://$host/v2/web/studentLog/$classroomId"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun host(): String = prefs.ytHost.ifEmpty { DEFAULT_HOST }

    private fun csrfToken(): String {
        for (part in prefs.ytCookie.split(';')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            if (part.substring(0, idx).trim() == "csrftoken") {
                return part.substring(idx + 1).trim()
            }
        }
        return ""
    }

    private fun get(path: String, classroomId: String? = null): String {
        val rb = Request.Builder()
            .url("https://${host()}$path")
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("xtbz", "ykt")
            .header("Referer", "https://${host()}/v2/web/index")
        if (!classroomId.isNullOrEmpty()) rb.header("classroom-id", classroomId)
        if (prefs.ytCookie.isNotEmpty()) rb.header("Cookie", prefs.ytCookie)
        val csrf = csrfToken()
        if (csrf.isNotEmpty()) rb.header("X-CSRFToken", csrf)
        client.newCall(rb.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code != 200) {
                DebugLog.log("雨课堂 HTTP ${resp.code} ${path.take(80)}")
            }
            return body
        }
    }

    private fun toJson(text: String): JSONObject? {
        val s = text.trim()
        if (s.isEmpty()) return null
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JSONObject(s.substring(start, end + 1))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- 登录

    /** 校验登录态并返回 (uid, name)，失败返回 null。 */
    fun fetchUser(): Pair<String, String>? {
        val text = try {
            get("/api/v3/user/basic-info")
        } catch (e: Exception) {
            DebugLog.log("雨课堂用户信息失败: ${e.message}")
            return null
        }
        val json = toJson(text) ?: return null
        val data = json.optJSONObject("data") ?: return null
        val uid = data.optString("id").ifEmpty { data.optString("user_id") }
        val name = data.optString("name").ifEmpty { data.optString("nickname") }
        if (uid.isEmpty()) return null
        return uid to name
    }

    fun testLogin(): Boolean = fetchUser() != null

    // ---------------------------------------------------------------- 课程

    private data class YtCourse(val classroomId: String, val name: String, val endTime: Long = 0L)

    private fun fetchClassEnd(classroomId: String): Long {
        val text = try {
            get("/v2/api/web/classrooms/$classroomId?role=5")
        } catch (e: Exception) {
            return 0L
        }
        val json = toJson(text) ?: return 0L
        return json.optJSONObject("data")?.optLong("class_end", 0L) ?: 0L
    }

    /** type=19 作业的提交/批阅状态只能从 leaf_info 的 is_assessed 推断。 */
    private fun fetchLeafAssessed(classroomId: String, leafId: String): Boolean {
        if (leafId.isEmpty()) return false
        val text = try {
            get("/mooc-api/v1/lms/learn/leaf_info/$classroomId/$leafId/", classroomId)
        } catch (e: Exception) {
            return false
        }
        val json = toJson(text) ?: return false
        return json.optJSONObject("data")?.optBoolean("is_assessed", false) ?: false
    }

    private fun fetchCourses(): List<YtCourse> {
        val text = try {
            get("/v2/api/web/courses/list?identity=2")
        } catch (e: Exception) {
            DebugLog.log("雨课堂课程请求异常: ${e.message}")
            return emptyList()
        }
        val json = toJson(text) ?: return emptyList()
        val list = json.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<YtCourse>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val classroomId = o.optString("classroom_id")
            if (classroomId.isEmpty()) continue
            val course = o.optJSONObject("course")
            val name = course?.optString("name").orEmpty().ifEmpty { o.optString("name") }
            out.add(YtCourse(classroomId, name))
        }
        return out
    }

    // ---------------------------------------------------------------- 活动

    private fun fetchActivities(course: YtCourse): List<Task> {
        val path = "/v2/api/web/logs/learn/${course.classroomId}" +
            "?actype=-1&page=0&offset=100&sort=-1"
        val text = try {
            get(path)
        } catch (e: Exception) {
            DebugLog.log("雨课堂活动失败 ${course.name}: ${e.message}")
            return emptyList()
        }
        val json = toJson(text) ?: return emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("activities") ?: return emptyList()
        val withEnd = if (course.endTime > 0) course else {
            course.copy(endTime = fetchClassEnd(course.classroomId))
        }
        val out = ArrayList<Task>()
        val now = System.currentTimeMillis()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val task = mapActivity(withEnd, a, now) ?: continue
            out.add(task)
        }
        return out
    }

    private fun mapActivity(course: YtCourse, a: JSONObject, now: Long): Task? {
        val type = a.optInt("type", 0)
        val title = a.optString("title").trim()
        if (title.isEmpty()) return null
        val id = a.optString("id")
        val createTime = a.optLong("create_time", 0L)
        // 日志里的 courseware_id 与活动页路由使用的 id 不一致，直接拼会提示“资源已删除”；
        // 先打开课程页，用户再点具体活动（活动页按钮是雨课堂自己的页面，能正常点）。
        val url = courseUrl(host(), course.classroomId)
        return when (type) {
            5 -> {
                val statusCode = a.optInt("status", 0)
                // 雨课堂对没有显式截止的习题/考试，页面会回退显示课程结束时间
                val deadline = a.optLong("deadline", 0L).takeIf { it > 0 } ?: course.endTime
                val total = a.optDouble("total_score", 0.0)
                val rawScore = if (a.isNull("score")) null else a.optDouble("score", -1.0)
                val status = when {
                    statusCode == 3 -> TaskStatus.GRADED
                    statusCode == 2 -> TaskStatus.SUBMITTED
                    deadline > 0 && deadline < now -> TaskStatus.EXPIRED
                    else -> TaskStatus.TODO
                }
                val isExam = title.contains("考试") || title.contains("测试")
                Task(
                    key = "YT|5|${course.classroomId}|$id",
                    title = title,
                    courseName = course.name,
                    courseId = course.classroomId,
                    classId = course.classroomId,
                    type = if (isExam) TaskType.EXAM else TaskType.WORK,
                    status = status,
                    endTime = deadline,
                    publishTime = createTime,
                    grade = if (status == TaskStatus.GRADED && rawScore != null && rawScore >= 0) {
                        fmtScore(rawScore)
                    } else {
                        ""
                    },
                    url = url,
                    statusText = when {
                        status == TaskStatus.GRADED -> "已完成"
                        status == TaskStatus.SUBMITTED -> "已提交，等待批阅"
                        statusCode == 0 -> "未作答"
                        else -> "进行中"
                    } + if (total > 0) " · 满分 ${fmtScore(total)}" else "",
                    provider = Provider.YUKETANG,
                )
            }

            19 -> {
                // 作业：leaf_info 里 is_assessed=true 视为已批阅
                val content = a.optJSONObject("content")
                val leafId = content?.optString("leaf_id").orEmpty()
                val deadline = content?.optLong("score_d", 0L) ?: 0L
                val assessed = fetchLeafAssessed(course.classroomId, leafId)
                val status = when {
                    assessed -> TaskStatus.GRADED
                    deadline > 0 && deadline < now -> TaskStatus.EXPIRED
                    else -> TaskStatus.TODO
                }
                Task(
                    key = "YT|19|${course.classroomId}|$id",
                    title = title,
                    courseName = course.name,
                    courseId = course.classroomId,
                    classId = course.classroomId,
                    type = TaskType.WORK,
                    status = status,
                    endTime = deadline,
                    publishTime = createTime,
                    url = url,
                    statusText = if (assessed) "已完成" else "作业",
                    provider = Provider.YUKETANG,
                )
            }

            else -> null
        }
    }

    /** 首次见到已交/已批任务时，把“现在”当提交/批阅时间，保证时间窗口里能看到。 */
    private fun applyStageTimes(tasks: List<Task>) {
        val old = prefs.states()
        if (old.isEmpty() && tasks.none { it.status == TaskStatus.SUBMITTED || it.status == TaskStatus.GRADED }) {
            return
        }
        val next = HashMap(old)
        val now = System.currentTimeMillis()
        var changed = false
        for (t in tasks) {
            if (t.status != TaskStatus.SUBMITTED && t.status != TaskStatus.GRADED) continue
            val prev = old[t.key]
            t.stageTime = if (prev != null && prev.first == t.status.name) {
                prev.second
            } else {
                now
            }
            next[t.key] = t.status.name to t.stageTime
            changed = true
        }
        if (changed) prefs.saveStates(next)
    }

    private fun fmtScore(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    // ---------------------------------------------------------------- 近期考试

    private data class UpcomingExam(val classroomId: String, val title: String)

    private fun fetchUpcomingExams(): List<UpcomingExam> {
        val text = try {
            get("/api/v3/classroom/on-lesson-upcoming-exam")
        } catch (e: Exception) {
            DebugLog.log("雨课堂近期考试失败: ${e.message}")
            return emptyList()
        }
        val json = toJson(text) ?: return emptyList()
        val arr = json.optJSONObject("data")?.optJSONArray("upcomingExam") ?: return emptyList()
        val out = ArrayList<UpcomingExam>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cid = o.optString("classroom_id")
            val title = o.optString("title").trim()
            if (cid.isNotEmpty() && title.isNotEmpty()) out.add(UpcomingExam(cid, title))
        }
        return out
    }

    // ---------------------------------------------------------------- 汇总

    override suspend fun fetchAll(): XtApi.Snapshot = withContext(Dispatchers.IO) {
        val errors = Collections.synchronizedList(ArrayList<String>())
        val courses = try {
            fetchCourses()
        } catch (e: Exception) {
            errors.add("雨课堂课程列表: ${e.message}")
            emptyList()
        }
        DebugLog.log("雨课堂课程数=${courses.size}")

        val raws = Collections.synchronizedList(ArrayList<Task>())
        if (courses.isNotEmpty()) {
            val sem = Semaphore(6)
            coroutineScope {
                courses.map { c ->
                    async {
                        sem.withPermit {
                            try {
                                raws.addAll(fetchActivities(c))
                            } catch (e: Exception) {
                                DebugLog.log("雨课堂活动拉取失败 ${c.name}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        // 近期考试接口里的 title 能区分“考试/习题”，把对应条目标成考试
        try {
            val upcoming = fetchUpcomingExams()
            if (upcoming.isNotEmpty()) {
                for (t in raws) {
                    if (upcoming.any { it.classroomId == t.courseId && it.title == t.title }) {
                        t.type = TaskType.EXAM
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("雨课堂考试标记失败: ${e.message}")
        }
        // 同一门课里同名同类型的活动会重复出现，去重
        val deduped = raws.distinctBy { "${it.courseId}|${it.title}|${it.type}" }
        applyStageTimes(deduped)
        DebugLog.log("雨课堂任务=${deduped.size}")
        XtApi.Snapshot(deduped, errors.toList())
    }

    override fun refreshTaskStatus(task: Task): Task {
        if (task.courseId.isEmpty()) return task
        val course = YtCourse(task.courseId, task.courseName)
        val list = try {
            fetchActivities(course)
        } catch (e: Exception) {
            DebugLog.log("雨课堂状态刷新失败: ${e.message}")
            return task
        }
        val found = list.firstOrNull { it.key == task.key } ?: return task
        applyStageTimes(listOf(found))
        task.status = found.status
        task.grade = found.grade
        task.endTime = found.endTime
        task.statusText = found.statusText
        if (found.stageTime > 0) task.stageTime = found.stageTime
        return task
    }
}

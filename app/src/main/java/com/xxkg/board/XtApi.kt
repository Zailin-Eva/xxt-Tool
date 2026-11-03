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
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * 学习通数据抓取。真实抓取路径（2026-11 实测）：
 *  课程列表  mooc1-api/mycourse/backclazzdata
 *  作业列表  mooc1-api/phone/moocAnalysis/workList?courseId&classId
 *  考试列表  mooc1-api/phone/moocAnalysis/examList?courseId&classId
 *  作业详情  /work/phone/task-work?...  → 开始/截止时间、分数
 *  考试详情  /exam/phone/task-exam?...  → 提交时间、成绩
 * 官方聚合接口 gas/taskmsg 对第三方 UA 一律 403，故不再依赖。
 */
class XtApi(private val prefs: Prefs) : TaskSource {

    companion object {
        const val MOOC = "https://mooc1-api.chaoxing.com"
        const val WEB = "https://mooc1.chaoxing.com"
        const val LOGIN_URL =
            "https://passport2.chaoxing.com/login?fid=&newversion=true&refer=https%3A%2F%2Fi.chaoxing.com"

        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val UA_SALT = "ipL\$TkeiEmfy1gTXb2XHrdLN0a@7c^vu"
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** 复刻官方 App 的 User-Agent（部分接口仍需要） */
    private fun officialUa(): String {
        val suffix = " (device:Pixel 6) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_7.0.2" +
            "_android_phone_10993_264 (@Kalimdor)_${prefs.uniqueId}"
        val digest = md5(suffix + UA_SALT)
        return "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 6 Build/UQ1A.240205.004) (schild:$digest)$suffix"
    }

    private fun get(url: String, official: Boolean = false, referer: String? = null): String {
        val rb = Request.Builder()
            .url(url)
            .header("User-Agent", if (official) officialUa() else BROWSER_UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (prefs.cookie.isNotEmpty()) rb.header("Cookie", prefs.cookie)
        if (!referer.isNullOrEmpty()) rb.header("Referer", referer)
        client.newCall(rb.build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code != 200) {
                DebugLog.log("HTTP ${resp.code} ${url.take(90)}")
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

    // ---------------------------------------------------------------- 课程

    fun fetchCourses(): List<Course> {
        val url = "$MOOC/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode="
        val text = try {
            get(url, referer = "https://i.chaoxing.com")
        } catch (e: Exception) {
            DebugLog.log("课程请求异常: ${e.message}")
            return emptyList()
        }
        val json = toJson(text)
        if (json == null) {
            DebugLog.log("课程返回非 JSON: ${text.take(60)}")
            return emptyList()
        }
        if (json.optInt("result", 0) != 1) {
            DebugLog.log("课程 result=${json.opt("result")} msg=${json.optString("msg")}")
            return emptyList()
        }
        val arr = json.optJSONArray("channelList") ?: return emptyList()
        val out = ArrayList<Course>()
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue
            var content: Any? = ch.opt("content")
            if (content is String) content = toJson(content)
            val clazz = content as? JSONObject ?: continue
            val cpi = clazz.optString("cpi").ifEmpty { ch.optString("cpi") }
            val courseData = clazz.optJSONObject("course")?.optJSONArray("data")
            if (courseData != null && courseData.length() > 0) {
                val classId = clazz.optString("id").ifEmpty { ch.optString("key") }
                if (classId.isEmpty()) continue
                for (j in 0 until courseData.length()) {
                    val c = courseData.optJSONObject(j) ?: continue
                    val cid = c.optString("id")
                    if (cid.isEmpty()) continue
                    out.add(Course(cid, classId, c.optString("name"), cpi))
                }
            } else {
                val cid = clazz.optString("id")
                val clazzArr = clazz.optJSONArray("clazz") ?: continue
                if (cid.isEmpty()) continue
                for (j in 0 until clazzArr.length()) {
                    val c = clazzArr.optJSONObject(j) ?: continue
                    out.add(Course(cid, c.optString("id"), clazz.optString("name"), cpi))
                }
            }
        }
        return out.distinctBy { it.courseId + "_" + it.classId }
    }

    // ---------------------------------------------------------------- 列表

    fun fetchWorkList(courseId: String, classId: String): List<Task> {
        val url = "$MOOC/phone/moocAnalysis/workList?courseId=$courseId&classId=$classId"
        return HtmlParsers.parseWorkList(get(url, referer = "$WEB/"), courseId, classId)
    }

    fun fetchExamList(courseId: String, classId: String): List<Task> {
        val url = "$MOOC/phone/moocAnalysis/examList?courseId=$courseId&classId=$classId"
        return HtmlParsers.parseExamList(get(url, referer = "$WEB/"), courseId, classId)
    }

    // ---------------------------------------------------------------- 答题页

    /**
     * 详情页 → 真正的答题页。
     *
     * 列表里的 url 是 /work/phone/task-work?...，它只是入口页；
     * 直接把它交给 WebView，经常会停在课程首页/作业列表。
     * 这里用已登录 Cookie 取一次详情页 HTML，解析出 mooc-ans 答题地址。
     */
    fun resolveAnswerUrl(taskUrl: String): String? {
        if (taskUrl.isEmpty()) return null
        val html = try {
            get(taskUrl, referer = "$WEB/")
        } catch (e: Exception) {
            DebugLog.log("解析答题页失败: ${e.message}")
            return null
        }
        val resolved = HtmlParsers.extractAnswerUrl(html)
        if (resolved != null) {
            DebugLog.log("答题页: ${resolved.take(120)}")
        } else {
            DebugLog.log("未找到答题页: ${taskUrl.take(100)}")
        }
        return resolved
    }

    /** 重新抓一次当前条目的详情，补开始/截止时间和分数。 */
    fun fetchTaskDetail(task: Task): Task {
        if (task.url.isEmpty()) return task
        val html = try {
            get(task.url, referer = "$WEB/")
        } catch (e: Exception) {
            DebugLog.log("刷新详情失败: ${e.message}")
            return task
        }
        if (task.type == TaskType.WORK) {
            val d = HtmlParsers.parseWorkDetail(html)
            if (d.start > 0) task.startTime = d.start
            if (d.end > 0) task.endTime = d.end
            if (d.score.isNotEmpty()) task.grade = d.score
        } else {
            val d = HtmlParsers.parseExamDetail(html)
            if (d.start > 0) task.startTime = d.start
            if (d.submit > 0) task.stageTime = d.submit
            if (d.score.isNotEmpty()) task.grade = d.score
        }
        return task
    }

    /** 只重新拉当前课程列表，更新这一条的状态（提交后变待批阅）。 */
    override fun refreshTaskStatus(task: Task): Task {
        if (task.courseId.isEmpty() || task.classId.isEmpty()) return task
        val list = try {
            when (task.type) {
                TaskType.WORK -> fetchWorkList(task.courseId, task.classId)
                TaskType.EXAM -> fetchExamList(task.courseId, task.classId)
            }
        } catch (e: Exception) {
            DebugLog.log("刷新状态失败: ${e.message}")
            return task
        }
        val found = list.firstOrNull { it.key == task.key } ?: return task
        val before = task.status
        task.status = found.status
        if (found.status != before) {
            // 和 fetchAll 的状态变化追踪保持一致：状态刚变，就把“现在”记成提交/批阅时间
            task.stageTime = System.currentTimeMillis()
            val states = prefs.states()
            states[task.key] = task.status.name to task.stageTime
            prefs.saveStates(states)
        }
        if (found.grade.isNotEmpty()) task.grade = found.grade
        if (found.endTime > 0) task.endTime = found.endTime
        if (found.publishTime > 0) task.publishTime = found.publishTime
        if (found.url.isNotEmpty()) task.url = found.url
        return task
    }

    /** 待批阅作业 →“修改作业”地址；找不到返回 null。 */
    fun resolveModifyUrl(taskUrl: String): String? {
        if (taskUrl.isEmpty()) return null
        val html = try {
            get(taskUrl, referer = "$WEB/")
        } catch (e: Exception) {
            DebugLog.log("解析修改作业失败: ${e.message}")
            return null
        }
        val url = HtmlParsers.extractModifyUrl(html)
        if (url != null) {
            DebugLog.log("修改作业: ${url.take(120)}")
        } else {
            DebugLog.log("未找到修改作业入口: ${taskUrl.take(100)}")
        }
        return url
    }

    // ---------------------------------------------------------------- 汇总

    data class Snapshot(val tasks: List<Task>, val errors: List<String>)

    override suspend fun fetchAll(): Snapshot = withContext(Dispatchers.IO) {
        val errors = Collections.synchronizedList(ArrayList<String>())
        val started = System.currentTimeMillis()

        val courses = try {
            fetchCourses()
        } catch (e: Exception) {
            errors.add("课程列表: ${e.message}")
            emptyList<Course>()
        }
        DebugLog.log("课程数=${courses.size}")

        val raws = Collections.synchronizedList(ArrayList<Task>())
        if (courses.isNotEmpty()) {
            val sem = Semaphore(8)
            coroutineScope {
                courses.map { c ->
                    async {
                        sem.withPermit {
                            try {
                                raws.addAll(fetchWorkList(c.courseId, c.classId))
                            } catch (e: Exception) {
                                DebugLog.log("作业拉取失败 ${c.name}: ${e.message}")
                            }
                            try {
                                raws.addAll(fetchExamList(c.courseId, c.classId))
                            } catch (e: Exception) {
                                DebugLog.log("考试拉取失败 ${c.name}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val nameById = courses.associate { it.courseId to it.name }
        for (t in raws) {
            if (t.courseName.isEmpty()) t.courseName = nameById[t.courseId] ?: ""
        }
        DebugLog.log("列表条目=${raws.size}，用时 ${(System.currentTimeMillis() - started) / 1000}s")

        // 计算“进入当前状态的时刻”：状态变化的那次刷新视为提交/批阅时间
        val now = System.currentTimeMillis()
        val old = prefs.states()
        val newStates = HashMap<String, Pair<String, Long>>()
        for (t in raws) {
            val prev = old[t.key]
            t.stageTime = when {
                prev == null -> if (t.publishTime > 0) t.publishTime else now
                prev.first != t.status.name -> now
                else -> prev.second
            }
            newStates[t.key] = t.status.name to t.stageTime
        }

        // 补详情：未交/过期要截止时间，近期完成的要分数
        val need = raws.filter { t ->
            when (t.type) {
                TaskType.WORK -> when (t.status) {
                    TaskStatus.TODO, TaskStatus.EXPIRED -> true
                    TaskStatus.GRADED -> t.publishTime > now - 45 * TimeFmt.DAY
                    else -> false
                }
                TaskType.EXAM -> t.status == TaskStatus.GRADED ||
                    t.status == TaskStatus.TODO ||
                    t.status == TaskStatus.EXPIRED
            }
        }
        DebugLog.log("需要补详情 ${need.size} 条")
        val drop = Collections.synchronizedSet(HashSet<String>())
        if (need.isNotEmpty()) {
            val sem2 = Semaphore(6)
            val ok = Collections.synchronizedList(ArrayList<String>())
            coroutineScope {
                need.map { t ->
                    async {
                        sem2.withPermit {
                            try {
                                if (t.url.isEmpty()) return@withPermit
                                val html = get(t.url, referer = "$WEB/")
                                if (t.type == TaskType.WORK) {
                                    val d = HtmlParsers.parseWorkDetail(html)
                                    if (d.start > 0) t.startTime = d.start
                                    if (d.end > 0) t.endTime = d.end
                                    if (d.score.isNotEmpty()) t.grade = d.score
                                } else {
                                    val d = HtmlParsers.parseExamDetail(html)
                                    if (d.start > 0) t.startTime = d.start
                                    if (d.submit > 0) t.stageTime = d.submit
                                    if (d.score.isNotEmpty()) t.grade = d.score
                                    // 「老师按人发放，无需作答」的考试不是给我的，直接丢掉
                                    if (html.contains("无需作答")) drop.add(t.key)
                                }
                                ok.add(t.title.take(12))
                            } catch (e: Exception) {
                                DebugLog.log("详情失败 ${t.title.take(12)}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }
            DebugLog.log("详情完成 ${ok.size}/${need.size}，过滤 ${drop.size} 条")
        }

        prefs.saveStates(newStates)
        val result = raws.filter { it.key !in drop }
        DebugLog.log("抓取完成，共 ${result.size} 条，总用时 ${(System.currentTimeMillis() - started) / 1000}s")
        Snapshot(result, errors.toList())
    }

    fun testCookie(): Boolean {
        val text = try {
            get("$MOOC/mycourse/backclazzdata?view=json&getTchClazzType=1&mcode=", referer = "https://i.chaoxing.com")
        } catch (e: Exception) {
            return false
        }
        return text.contains("\"result\":1")
    }
}

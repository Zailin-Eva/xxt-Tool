package com.xxkg.board

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

enum class Section {
    MORNING, NOON, AFTERNOON, EVENING;

    val label: String
        get() = when (this) {
            MORNING -> "上午"
            NOON -> "中午"
            AFTERNOON -> "下午"
            EVENING -> "晚上"
        }
}

data class Period(
    var no: Int,
    var start: String,
    var end: String,
    var section: Section,
)

data class Term(
    var name: String,
    var week1Monday: String,
    var totalWeeks: Int,
)

data class TtCourse(
    var id: String,
    var name: String,
    var teacher: String = "",
    var credits: String = "",
    var note: String = "",
)

data class Segment(
    var id: String,
    var courseId: String,
    var day: Int,
    var startPeriod: Int,
    var endPeriod: Int,
    var weeks: MutableSet<Int> = sortedSetOf(),
    var campus: String = "",
    var location: String = "",
    var teacher: String = "",
    var hidden: Boolean = false,
)

data class Unscheduled(
    var id: String,
    var name: String,
    var teacher: String = "",
    var weeks: MutableSet<Int> = sortedSetOf(),
    var note: String = "",
)

/** 课程表全部本地数据。 */
class TimetableData(
    var schema: Int = 1,
    var term: Term = TimetableDefaults.defaultTerm(),
    var periods: MutableList<Period> = TimetableDefaults.defaultPeriods(),
    var courses: MutableList<TtCourse> = mutableListOf(),
    var segments: MutableList<Segment> = mutableListOf(),
    var unscheduled: MutableList<Unscheduled> = mutableListOf(),
) {
    fun course(id: String): TtCourse? = courses.firstOrNull { it.id == id }

    fun segmentsOf(courseId: String): List<Segment> =
        segments.filter { it.courseId == courseId }

    fun activeSegments(courseId: String, week: Int): List<Segment> =
        segmentsOf(courseId).filter { !it.hidden && it.weeks.contains(week) }
}

object TimetableDefaults {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun defaultTerm(): Term {
        val now = Calendar.getInstance()
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        return if (month >= 8) {
            val start = firstMondayOnOrAfter(year, Calendar.SEPTEMBER, 1)
            Term("$year-${year + 1} 第1学期", dateFmt.format(start.time), 20)
        } else {
            val start = firstMondayOnOrAfter(year, Calendar.FEBRUARY, 20)
            Term("${year - 1}-$year 第2学期", dateFmt.format(start.time), 20)
        }
    }

    private fun firstMondayOnOrAfter(year: Int, month: Int, day: Int): Calendar {
        val c = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (c.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return c
    }

    fun defaultPeriods(): MutableList<Period> = mutableListOf(
        Period(1, "08:00", "08:45", Section.MORNING),
        Period(2, "08:55", "09:40", Section.MORNING),
        Period(3, "10:00", "10:45", Section.MORNING),
        Period(4, "10:55", "11:40", Section.MORNING),
        Period(5, "11:50", "12:35", Section.MORNING),
        Period(6, "12:45", "13:30", Section.NOON),
        Period(7, "14:00", "14:45", Section.AFTERNOON),
        Period(8, "14:55", "15:40", Section.AFTERNOON),
        Period(9, "16:00", "16:45", Section.AFTERNOON),
        Period(10, "16:55", "17:40", Section.AFTERNOON),
        Period(11, "17:50", "18:35", Section.AFTERNOON),
        Period(12, "19:20", "20:05", Section.EVENING),
        Period(13, "20:15", "21:00", Section.EVENING),
    )
}

object TimetableCodec {

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    fun encode(data: TimetableData): String = toJson(data).toString()

    fun decode(raw: String): TimetableData? {
        if (raw.isBlank()) return null
        return try {
            fromJson(JSONObject(raw))
        } catch (e: Exception) {
            null
        }
    }

    fun toJson(data: TimetableData): JSONObject {
        val o = JSONObject()
        o.put("schema", data.schema)
        o.put(
            "term",
            JSONObject()
                .put("name", data.term.name)
                .put("week1Monday", data.term.week1Monday)
                .put("totalWeeks", data.term.totalWeeks)
        )

        val periods = JSONArray()
        for (p in data.periods) {
            periods.put(
                JSONObject()
                    .put("no", p.no)
                    .put("start", p.start)
                    .put("end", p.end)
                    .put("section", p.section.name)
            )
        }
        o.put("periods", periods)

        val courses = JSONArray()
        for (c in data.courses) {
            courses.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("teacher", c.teacher)
                    .put("credits", c.credits)
                    .put("note", c.note)
            )
        }
        o.put("courses", courses)

        val segments = JSONArray()
        for (s in data.segments) {
            segments.put(
                JSONObject()
                    .put("id", s.id)
                    .put("courseId", s.courseId)
                    .put("day", s.day)
                    .put("startPeriod", s.startPeriod)
                    .put("endPeriod", s.endPeriod)
                    .put("weeks", JSONArray(s.weeks.sorted()))
                    .put("campus", s.campus)
                    .put("location", s.location)
                    .put("teacher", s.teacher)
                    .put("hidden", s.hidden)
            )
        }
        o.put("segments", segments)

        val unscheduled = JSONArray()
        for (u in data.unscheduled) {
            unscheduled.put(
                JSONObject()
                    .put("id", u.id)
                    .put("name", u.name)
                    .put("teacher", u.teacher)
                    .put("weeks", JSONArray(u.weeks.sorted()))
                    .put("note", u.note)
            )
        }
        o.put("unscheduled", unscheduled)
        return o
    }

    fun fromJson(o: JSONObject): TimetableData {
        val data = TimetableData()
        data.schema = o.optInt("schema", 1)

        val term = o.optJSONObject("term")
        if (term != null) {
            data.term = Term(
                name = term.optString("name").ifEmpty { data.term.name },
                week1Monday = term.optString("week1Monday").ifEmpty { data.term.week1Monday },
                totalWeeks = term.optInt("totalWeeks", data.term.totalWeeks).coerceIn(1, 30),
            )
        }

        val periods = o.optJSONArray("periods")
        if (periods != null && periods.length() > 0) {
            val list = ArrayList<Period>()
            for (i in 0 until periods.length()) {
                val p = periods.optJSONObject(i) ?: continue
                list.add(
                    Period(
                        no = p.optInt("no", i + 1),
                        start = p.optString("start", "08:00"),
                        end = p.optString("end", "08:45"),
                        section = runCatching {
                            Section.valueOf(p.optString("section", Section.MORNING.name))
                        }.getOrDefault(Section.MORNING),
                    )
                )
            }
            data.periods = list.sortedBy { it.no }.toMutableList()
        }

        val courses = o.optJSONArray("courses")
        if (courses != null) {
            for (i in 0 until courses.length()) {
                val c = courses.optJSONObject(i) ?: continue
                val name = c.optString("name").trim()
                if (name.isEmpty()) continue
                data.courses.add(
                    TtCourse(
                        id = c.optString("id").ifEmpty { newId() },
                        name = name,
                        teacher = c.optString("teacher"),
                        credits = c.optString("credits"),
                        note = c.optString("note"),
                    )
                )
            }
        }

        val segments = o.optJSONArray("segments")
        if (segments != null) {
            for (i in 0 until segments.length()) {
                val s = segments.optJSONObject(i) ?: continue
                val courseId = s.optString("courseId")
                if (courseId.isEmpty()) continue
                data.segments.add(
                    Segment(
                        id = s.optString("id").ifEmpty { newId() },
                        courseId = courseId,
                        day = s.optInt("day", 1).coerceIn(1, 7),
                        startPeriod = s.optInt("startPeriod", 1),
                        endPeriod = s.optInt("endPeriod", 1),
                        weeks = readWeeks(s.optJSONArray("weeks")),
                        campus = s.optString("campus"),
                        location = s.optString("location"),
                        teacher = s.optString("teacher"),
                        hidden = s.optBoolean("hidden", false),
                    )
                )
            }
        }

        val unscheduled = o.optJSONArray("unscheduled")
        if (unscheduled != null) {
            for (i in 0 until unscheduled.length()) {
                val u = unscheduled.optJSONObject(i) ?: continue
                val name = u.optString("name").trim()
                if (name.isEmpty()) continue
                data.unscheduled.add(
                    Unscheduled(
                        id = u.optString("id").ifEmpty { newId() },
                        name = name,
                        teacher = u.optString("teacher"),
                        weeks = readWeeks(u.optJSONArray("weeks")),
                        note = u.optString("note"),
                    )
                )
            }
        }
        return data
    }

    private fun readWeeks(arr: JSONArray?): MutableSet<Int> {
        val set = sortedSetOf<Int>()
        if (arr == null) return set
        for (i in 0 until arr.length()) {
            val w = arr.optInt(i, 0)
            if (w in 1..60) set.add(w)
        }
        return set
    }
}



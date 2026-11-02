package com.xxkg.board

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 作业/考试详情页：先展示发布时间、截止时间、状态和分数，
 * 再由用户选择“查看作业详情”或“开始答题 / 修改作业 / 查看作业”。
 */
class TaskDetailActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var api: XtApi
    private lateinit var task: Task

    private var loading = false
    private var refreshOnResume = false

    private lateinit var tvBarTitle: TextView
    private lateinit var tvType: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvTaskTitle: TextView
    private lateinit var tvCourse: TextView
    private lateinit var tvStart: TextView
    private lateinit var tvEnd: TextView
    private lateinit var tvRemain: TextView
    private lateinit var tvGrade: TextView
    private lateinit var rowGrade: View
    private lateinit var tvHint: TextView
    private lateinit var btnViewDetail: TextView
    private lateinit var btnAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        api = XtApi(prefs)
        setContentView(R.layout.activity_task_detail)

        val key = intent.getStringExtra(EXTRA_TASK_KEY).orEmpty()
        val found = Task.decode(prefs.cache).firstOrNull { it.key == key }
        if (found == null) {
            toast("找不到这条作业，请回列表刷新")
            finish()
            return
        }
        task = found

        tvBarTitle = findViewById(R.id.tvBarTitle)
        tvType = findViewById(R.id.tvType)
        tvStatus = findViewById(R.id.tvStatus)
        tvTaskTitle = findViewById(R.id.tvTaskTitle)
        tvCourse = findViewById(R.id.tvCourse)
        tvStart = findViewById(R.id.tvStart)
        tvEnd = findViewById(R.id.tvEnd)
        tvRemain = findViewById(R.id.tvRemain)
        tvGrade = findViewById(R.id.tvGrade)
        rowGrade = findViewById(R.id.rowGrade)
        tvHint = findViewById(R.id.tvHint)
        btnViewDetail = findViewById(R.id.btnViewDetail)
        btnAction = findViewById(R.id.btnAction)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { refreshStatusAndDetail() }
        btnViewDetail.setOnClickListener { openDetailPage() }
        btnAction.setOnClickListener { openAction() }

        render()
        refreshDetail()
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val isExam = task.type == TaskType.EXAM
        tvBarTitle.text = if (isExam) "考试详情" else "作业详情"
        tvType.text = task.type.label
        tvStatus.text = statusLabel()
        tvStatus.setTextColor(statusColor())
        tvTaskTitle.text = task.title
        tvCourse.text = task.courseName.ifEmpty { "未知课程" }

        val start = if (task.startTime > 0) task.startTime else task.publishTime
        tvStart.text = if (start > 0) TimeFmt.dateTime(start) else "未知"
        tvEnd.text = if (task.endTime > 0) TimeFmt.dateTime(task.endTime) else "未知"

        val now = System.currentTimeMillis()
        when (task.status) {
            TaskStatus.GRADED -> {
                tvRemain.text = "已完成"
                tvRemain.setTextColor(0xFF2E7D32.toInt())
            }
            TaskStatus.SUBMITTED -> {
                tvRemain.text = "已提交，等待批阅"
                tvRemain.setTextColor(TimeFmt.C_SOON)
            }
            TaskStatus.EXPIRED -> {
                tvRemain.text = "已过期"
                tvRemain.setTextColor(TimeFmt.C_EXPIRED)
            }
            else -> {
                val r = TimeFmt.remain(now, task.endTime)
                tvRemain.text = r.text
                tvRemain.setTextColor(r.color)
            }
        }

        if (task.grade.isNotEmpty()) {
            rowGrade.visibility = View.VISIBLE
            tvGrade.text = "${task.grade} 分"
        } else {
            rowGrade.visibility = View.GONE
        }

        btnViewDetail.text = if (isExam) "查看考试详情" else "查看作业详情"
        btnAction.text = actionLabel()
        val canOpen = task.url.isNotEmpty()
        btnViewDetail.isEnabled = canOpen
        btnAction.isEnabled = canOpen
        btnViewDetail.alpha = if (canOpen) 1f else 0.45f
        btnAction.alpha = if (canOpen) 1f else 0.45f
    }

    private fun statusLabel(): String = when (task.status) {
        TaskStatus.TODO -> "未完成"
        TaskStatus.SUBMITTED -> "待批阅"
        TaskStatus.GRADED -> "已完成"
        TaskStatus.EXPIRED -> "已过期"
    }

    private fun statusColor(): Int = when (task.status) {
        TaskStatus.GRADED -> 0xFF2E7D32.toInt()
        TaskStatus.EXPIRED -> TimeFmt.C_EXPIRED
        else -> TimeFmt.C_SOON
    }

    private fun actionLabel(): String {
        if (task.type == TaskType.EXAM) {
            return if (task.status == TaskStatus.TODO) "开始考试" else "查看考试"
        }
        return when (task.status) {
            TaskStatus.TODO -> "开始答题"
            TaskStatus.SUBMITTED -> "修改作业"
            TaskStatus.GRADED -> "查看作业"
            TaskStatus.EXPIRED -> "查看题目"
        }
    }

    // ------------------------------------------------------------ 数据刷新

    private fun refreshDetail() {
        if (task.url.isEmpty()) {
            tvHint.visibility = View.GONE
            return
        }
        if (loading) return
        loading = true
        tvHint.visibility = View.VISIBLE
        tvHint.text = "正在获取最新时间…"
        scope.launch {
            val updated = withContext(Dispatchers.IO) { api.fetchTaskDetail(task) }
            task = updated
            saveTask()
            loading = false
            tvHint.visibility = View.GONE
            render()
        }
    }

    /** 刷新状态（提交后变待批阅）并补详情；从学习通页面返回时调用。 */
    private fun refreshStatusAndDetail() {
        if (task.url.isEmpty()) return
        if (loading) return
        loading = true
        tvHint.visibility = View.VISIBLE
        tvHint.text = "正在刷新状态…"
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                api.refreshTaskStatus(task)
                api.fetchTaskDetail(task)
            }
            task = updated
            saveTask()
            loading = false
            tvHint.visibility = View.GONE
            render()
        }
    }

    private fun saveTask() {
        val list = Task.decode(prefs.cache).toMutableList()
        val index = list.indexOfFirst { it.key == task.key }
        if (index >= 0) list[index] = task else list.add(task)
        prefs.cache = Task.encode(list)
    }

    // ------------------------------------------------------------ 跳转

    private fun openDetailPage() {
        if (loading) {
            toast("正在刷新，请稍候")
            return
        }
        if (task.url.isEmpty()) {
            toast("这条没有可打开的详情页")
            return
        }
        openWeb(task.url, if (task.type == TaskType.EXAM) "考试详情" else "作业详情")
    }

    private fun openAction() {
        if (loading) {
            toast("正在刷新，请稍候")
            return
        }
        if (task.url.isEmpty()) {
            toast("这条没有可打开的页面")
            return
        }
        loading = true
        toast("正在打开…")
        scope.launch {
            val url = withContext(Dispatchers.IO) {
                if (task.type == TaskType.EXAM) {
                    api.resolveAnswerUrl(task.url)
                } else if (task.status == TaskStatus.SUBMITTED) {
                    api.resolveModifyUrl(task.url)
                } else {
                    api.resolveAnswerUrl(task.url)
                }
            }
            loading = false
            if (isFinishing || isDestroyed) return@launch
            if (url != null) {
                openWeb(url, task.title)
            } else if (task.status == TaskStatus.SUBMITTED) {
                toast("没找到修改入口，打开详情页后请点“修改作业”")
                openWeb(task.url, task.title)
            } else {
                toast("没找到答题页，打开学习通详情页")
                openWeb(task.url, task.title)
            }
        }
    }

    private fun openWeb(url: String, title: String) {
        refreshOnResume = true
        val i = Intent(this, WebActivity::class.java)
        i.putExtra(WebActivity.EXTRA_URL, url)
        i.putExtra(WebActivity.EXTRA_REFERER, XtApi.WEB + "/")
        i.putExtra(WebActivity.EXTRA_TITLE, title)
        startActivity(i)
    }

    override fun onResume() {
        super.onResume()
        if (refreshOnResume) {
            refreshOnResume = false
            refreshStatusAndDetail()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TASK_KEY = "task_key"
    }
}

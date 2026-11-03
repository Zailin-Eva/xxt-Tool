package com.xxkg.board

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var api: TaskSource
    private lateinit var adapter: TaskAdapter

    private lateinit var tvNearestTitle: TextView
    private lateinit var tvNearestMeta: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvSort: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnLogin: TextView
    private lateinit var emptyBox: android.view.View
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private val tabs = ArrayList<TextView>()

    private var all: List<Task> = emptyList()
    private var buckets = Buckets()
    private var tab = 0
    private var refreshing = false
    private var lastOpenedTaskKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        api = if (prefs.provider == Provider.YUKETANG) YtApi(prefs) else XtApi(prefs)
        setContentView(R.layout.activity_main)

        tvNearestTitle = findViewById(R.id.tvNearestTitle)
        tvNearestMeta = findViewById(R.id.tvNearestMeta)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvSort = findViewById(R.id.tvSort)
        tvRange = findViewById(R.id.tvRange)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnLogin = findViewById(R.id.btnLogin)
        emptyBox = findViewById(R.id.emptyBox)
        swipe = findViewById(R.id.swipe)
        list = findViewById(R.id.list)

        btnLogin.setOnClickListener {
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
        }

        tabs.add(findViewById(R.id.tab0))
        tabs.add(findViewById(R.id.tab1))
        tabs.add(findViewById(R.id.tab2))
        tabs.add(findViewById(R.id.tab3))

        adapter = TaskAdapter(
            onClick = { t -> openTask(t) },
            onLongClick = { t -> confirmDelete(t) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        for (i in tabs.indices) {
            tabs[i].setOnClickListener {
                tab = i
                render()
            }
        }

        tvSort.setOnClickListener {
            if (tab != 0) {
                toast("只有「未完成」可以切换排序")
                return@setOnClickListener
            }
            prefs.sortMode = (prefs.sortMode + 1) % Categorizer.SORT_LABELS.size
            render()
        }

        // 时间窗口只影响「待批阅 / 已完成」两栏，其它栏目下置灰不可点
        tvRange.setOnClickListener {
            if (tab != 1 && tab != 2) {
                toast("只有「待批阅」和「已完成」可以调整时间窗口")
                return@setOnClickListener
            }
            showRangeDialog()
        }

        swipe.setOnRefreshListener { refresh() }

        findViewById<TextView>(R.id.btnHome).setOnClickListener {
            tab = 0
            list.scrollToPosition(0)
            render()
        }
        findViewById<TextView>(R.id.btnMe).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        all = Task.decode(prefs.activeCache)
        render()

        if (!prefs.isLoggedIn) {
            if (all.isEmpty() && !prefs.loggedOut) {
                startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
            } else if (all.isNotEmpty()) {
                toast("登录已失效，先显示上次缓存")
            }
        } else {
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从学习通页面/退出登录回来时，本地缓存可能已变化，重新读一遍再渲染
        all = Task.decode(prefs.activeCache)
        render()
        // 从别处登录成功回来（缓存为空且从未同步过）时自动抓一次
        if (prefs.isLoggedIn && all.isEmpty() && prefs.lastSync == 0L && !refreshing) {
            refresh()
        }
        if (prefs.isLoggedIn) {
            UpdateManager.maybeAutoCheck(this)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == Activity.RESULT_OK) {
            prefs.loggedOut = false
            // 登录时可能切换了数据源，重新绑定
            api = if (prefs.provider == Provider.YUKETANG) YtApi(prefs) else XtApi(prefs)
            all = Task.decode(prefs.activeCache)
            render()
            refresh()
        } else if (requestCode == REQ_TASK) {
            // 从学习通页面回到列表：这条作业可能刚交过，轻量刷新它的状态
            refreshTaskStatus(lastOpenedTaskKey)
        }
    }

    // ------------------------------------------------------------ 渲染

    private fun render() {
        val now = System.currentTimeMillis()
        buckets = Categorizer.split(
            all, prefs.xDays, prefs.yDays, now,
            prefs.deletedKeys(), prefs.sortMode
        )

        for (i in tabs.indices) tabs[i].isSelected = (i == tab)

        tvSort.text = "排序：" + Categorizer.SORT_LABELS[prefs.sortMode]
        tvSort.setTextColor(if (tab == 0) 0xFF333333.toInt() else 0xFFBBBBBB.toInt())
        tvRange.text = "待批阅 ${prefs.xDays} 天 / 已完成 ${prefs.yDays} 天"
        tvRange.setTextColor(if (tab == 1 || tab == 2) 0xFF333333.toInt() else 0xFFBBBBBB.toInt())

        // 顶部卡片
        // 顶部卡片始终取真正最近截止的一条，不受当前排序方式影响
        val first = buckets.todo.minByOrNull {
            if (it.endTime > 0) it.endTime else Long.MAX_VALUE
        }
        if (first == null) {
            tvNearestTitle.text = "暂无未完成作业"
            tvNearestMeta.text = if (prefs.isLoggedIn) "下拉可刷新" else "请先登录学习通"
            tvCountdown.text = "--"
            tvCountdown.setTextColor(TimeFmt.C_TEXT)
        } else {
            tvNearestTitle.text = first.title
            val r = TimeFmt.remain(now, first.endTime, prefs.greenDays, prefs.orangeDays)
            tvCountdown.text = if (first.endTime > 0) {
                TimeFmt.bigCountdown(now, first.endTime)
            } else {
                "无截止"
            }
            tvCountdown.setTextColor(r.color)
            val soon = buckets.todo.count { it.endTime > now && it.endTime - now <= TimeFmt.DAY }
            val week = buckets.todo.count { it.endTime > now && it.endTime - now <= 7 * TimeFmt.DAY }
            val course = first.courseName.ifEmpty { "" }
            tvNearestMeta.text = buildString {
                if (course.isNotEmpty()) {
                    append(course)
                    append(" · ")
                }
                append("截止 ")
                append(if (first.endTime > 0) TimeFmt.dateTime(first.endTime) else "未知")
                if (soon > 0) {
                    append(" · 今天 ")
                    append(soon)
                }
                if (week > soon) {
                    append(" · 本周 ")
                    append(week)
                }
            }
        }

        val data = when (tab) {
            0 -> buckets.todo
            1 -> buckets.pending
            2 -> buckets.done
            else -> buckets.expired
        }
        adapter.submit(data, tab, prefs.greenDays, prefs.orangeDays)

        if (data.isEmpty()) {
            emptyBox.visibility = android.view.View.VISIBLE
            btnLogin.visibility = if (!prefs.isLoggedIn) {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            tvEmpty.text = when {
                !prefs.isLoggedIn -> "登录后查看课程、作业和考试"
                tab == 0 -> "没有未完成的作业 / 考试"
                tab == 1 -> "近 ${prefs.xDays} 天没有待批阅的作业"
                tab == 2 -> "近 ${prefs.yDays} 天没有已完成的作业"
                else -> "没有已过期未交的作业"
            }
        } else {
            emptyBox.visibility = android.view.View.GONE
            btnLogin.visibility = android.view.View.GONE
        }
    }

    // ------------------------------------------------------------ 刷新

    private fun refresh() {
        if (refreshing) return
        if (!prefs.isLoggedIn) {
            swipe.isRefreshing = false
            toast("请先登录学习通")
            return
        }
        refreshing = true
        swipe.isRefreshing = true
        scope.launch {
            try {
                val snap = api.fetchAll()
                all = snap.tasks
                prefs.activeCache = Task.encode(all)
                prefs.lastSync = System.currentTimeMillis()
                if (snap.tasks.isEmpty() && snap.errors.isNotEmpty()) {
                    toast("抓取失败：" + snap.errors.first())
                }
                render()
            } catch (e: Exception) {
                toast("刷新失败：" + (e.message ?: "未知错误"))
            } finally {
                refreshing = false
                swipe.isRefreshing = false
            }
        }
    }

    // ------------------------------------------------------------ 交互

    private fun openTask(t: Task) {
        if (t.url.isEmpty()) {
            toast("这条没有可打开的页面")
            return
        }
        // 直接打开学习通扒下来的作业/考试页面，页内的「继续答题 / 修改答案」由 jsBridge 接管
        lastOpenedTaskKey = t.key
        val i = Intent(this, WebActivity::class.java)
        i.putExtra(WebActivity.EXTRA_URL, t.url)
        i.putExtra(WebActivity.EXTRA_REFERER, refererFor(prefs, t))
        i.putExtra(WebActivity.EXTRA_TITLE, t.title)
        startActivityForResult(i, REQ_TASK)
    }

    private fun refererFor(prefs: Prefs, t: Task): String =
        if (prefs.provider == Provider.YUKETANG) {
            "https://${prefs.ytHost}/"
        } else {
            XtApi.WEB + "/"
        }

    /** 从作业页回来：只重拉这条所在课程的列表，更新它的状态/分数，避免整页重刷。 */
    private fun refreshTaskStatus(key: String) {
        if (key.isEmpty() || !prefs.isLoggedIn) return
        val task = all.firstOrNull { it.key == key } ?: return
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { api.refreshTaskStatus(task) }
                val list = all.toMutableList()
                val index = list.indexOfFirst { it.key == updated.key }
                if (index >= 0) list[index] = updated
                all = list
                prefs.activeCache = Task.encode(all)
                render()
            } catch (e: Exception) {
                // 状态刷新失败不打扰用户，下次下拉刷新会补上
            }
        }
    }

    private fun confirmDelete(t: Task) {
        AlertDialog.Builder(this)
            .setTitle("移除这一条")
            .setMessage(t.title)
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                prefs.deleteKey(t.key)
                render()
                toast("已移除，可在「个人」里恢复")
            }
            .show()
    }

    private fun showRangeDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_range, null)
        val etX = view.findViewById<EditText>(R.id.etX)
        val etY = view.findViewById<EditText>(R.id.etY)
        etX.setText(prefs.xDays.toString())
        etY.setText(prefs.yDays.toString())
        AlertDialog.Builder(this)
            .setTitle("时间窗口")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val x = etX.text.toString().toIntOrNull() ?: prefs.xDays
                val y = etY.text.toString().toIntOrNull() ?: prefs.yDays
                prefs.xDays = x.coerceIn(1, 365)
                prefs.yDays = y.coerceIn(1, 365)
                render()
            }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_LOGIN = 1001
        private const val REQ_TASK = 1002
    }
}

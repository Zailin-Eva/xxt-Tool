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

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var api: XtApi
    private lateinit var adapter: TaskAdapter

    private lateinit var tvNearestTitle: TextView
    private lateinit var tvNearestMeta: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvCountdownNote: TextView
    private lateinit var tvSort: TextView
    private lateinit var tvRange: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var list: RecyclerView
    private val tabs = ArrayList<TextView>()

    private var all: List<Task> = emptyList()
    private var buckets = Buckets()
    private var tab = 0
    private var refreshing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        api = XtApi(prefs)
        setContentView(R.layout.activity_main)

        tvNearestTitle = findViewById(R.id.tvNearestTitle)
        tvNearestMeta = findViewById(R.id.tvNearestMeta)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvCountdownNote = findViewById(R.id.tvCountdownNote)
        tvSort = findViewById(R.id.tvSort)
        tvRange = findViewById(R.id.tvRange)
        tvEmpty = findViewById(R.id.tvEmpty)
        swipe = findViewById(R.id.swipe)
        list = findViewById(R.id.list)

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

        tvRange.setOnClickListener { showRangeDialog() }

        swipe.setOnRefreshListener { refresh() }

        findViewById<TextView>(R.id.btnHome).setOnClickListener {
            tab = 0
            list.scrollToPosition(0)
            render()
        }
        findViewById<TextView>(R.id.btnMe).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        all = Task.decode(prefs.cache)
        render()

        if (!prefs.isLoggedIn) {
            if (all.isEmpty()) {
                startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
            } else {
                toast("登录已失效，先显示上次缓存")
            }
        } else {
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == Activity.RESULT_OK) {
            refresh()
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

        // 顶部卡片
        val first = buckets.todo.firstOrNull()
        if (first == null) {
            tvNearestTitle.text = "暂无未完成作业"
            tvNearestMeta.text = if (prefs.isLoggedIn) "下拉可刷新" else "请先登录学习通"
            tvCountdown.text = "--"
            tvCountdown.setTextColor(TimeFmt.C_TEXT)
            tvCountdownNote.text = "（该作业剩余时间）"
        } else {
            tvNearestTitle.text = first.title
            val r = TimeFmt.remain(now, first.endTime)
            tvCountdown.text = TimeFmt.bigCountdown(now, first.endTime)
            tvCountdown.setTextColor(if (r.level >= 4) TimeFmt.C_EXPIRED else TimeFmt.C_TEXT)
            tvCountdownNote.text = "（该作业剩余时间）"
            val soon = buckets.todo.count { it.endTime > now && it.endTime - now <= TimeFmt.DAY }
            val week = buckets.todo.count { it.endTime > now && it.endTime - now <= 7 * TimeFmt.DAY }
            val course = first.courseName.ifEmpty { "" }
            tvNearestMeta.text = buildString {
                if (course.isNotEmpty()) {
                    append(course)
                    append(" · ")
                }
                append("截止 ")
                append(TimeFmt.dateTime(first.endTime))
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
        adapter.submit(data, tab)

        if (data.isEmpty()) {
            tvEmpty.visibility = android.view.View.VISIBLE
            tvEmpty.text = when {
                !prefs.isLoggedIn -> "请先登录学习通账号"
                tab == 0 -> "没有未完成的作业 / 考试"
                tab == 1 -> "近 ${prefs.xDays} 天没有待批阅的作业"
                tab == 2 -> "近 ${prefs.yDays} 天没有已完成的作业"
                else -> "没有已过期未交的作业"
            }
        } else {
            tvEmpty.visibility = android.view.View.GONE
        }
    }

    // ------------------------------------------------------------ 刷新

    private fun refresh() {
        if (refreshing) return
        if (!prefs.isLoggedIn) {
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
            return
        }
        refreshing = true
        swipe.isRefreshing = true
        scope.launch {
            try {
                val snap = api.fetchAll()
                all = snap.tasks
                prefs.cache = Task.encode(all)
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
        val i = Intent(this, WebActivity::class.java)
        i.putExtra(WebActivity.EXTRA_URL, t.url)
        i.putExtra(WebActivity.EXTRA_TITLE, t.title)
        startActivity(i)
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
    }
}

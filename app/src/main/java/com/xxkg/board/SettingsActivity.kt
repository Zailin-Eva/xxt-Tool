package com.xxkg.board

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var tvAccount: TextView
    private lateinit var tvSync: TextView
    private lateinit var btnX: TextView
    private lateinit var btnY: TextView
    private lateinit var btnRestore: TextView
    private lateinit var btnLogout: TextView
    private lateinit var btnRelogin: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_settings)

        tvAccount = findViewById(R.id.tvAccount)
        tvSync = findViewById(R.id.tvSync)
        btnX = findViewById(R.id.btnX)
        btnY = findViewById(R.id.btnY)
        btnRestore = findViewById(R.id.btnRestore)
        btnLogout = findViewById(R.id.btnLogout)
        btnRelogin = findViewById(R.id.btnRelogin)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvVersion).text = "作业看板 v" + versionName() + " · 仅本机使用"
        btnRelogin.setOnClickListener {
            startActivityForResult(Intent(this, LoginActivity::class.java), REQ_LOGIN)
        }
        findViewById<TextView>(R.id.btnRefresh).setOnClickListener { refreshNow() }
        findViewById<TextView>(R.id.btnLog).setOnClickListener { showLog() }
        findViewById<TextView>(R.id.btnClear).setOnClickListener { confirmClear() }
        btnRestore.setOnClickListener { confirmRestore() }
        btnLogout.setOnClickListener { confirmLogout() }
        btnX.setOnClickListener { editDays("待批阅时间窗口（天）", prefs.xDays) { prefs.xDays = it } }
        btnY.setOnClickListener { editDays("已完成保留时间（天）", prefs.yDays) { prefs.yDays = it } }

        bindState()
    }

    override fun onResume() {
        super.onResume()
        bindState()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == Activity.RESULT_OK) bindState()
    }

    private fun bindState() {
        tvAccount.text = if (prefs.isLoggedIn) {
            "已登录 · UID ${prefs.uid}"
        } else {
            "未登录"
        }
        tvSync.text = if (prefs.lastSync > 0) {
            "上次更新：" + TimeFmt.dateTime(prefs.lastSync)
        } else {
            "尚未同步"
        }
        btnX.text = "${prefs.xDays} 天"
        btnY.text = "${prefs.yDays} 天"
        btnRelogin.text = if (prefs.isLoggedIn) "重新登录" else "登录学习通"
        btnLogout.visibility = if (prefs.isLoggedIn) View.VISIBLE else View.GONE
        val n = prefs.deletedKeys().size
        btnRestore.text = if (n > 0) "恢复被清理的条目（$n）" else "恢复被清理的条目"
    }

    private fun editDays(title: String, current: Int, apply: (Int) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_number, null)
        val tvLabel = view.findViewById<TextView>(R.id.tvLabel)
        val et = view.findViewById<EditText>(R.id.etValue)
        tvLabel.text = title
        et.setText(current.toString())
        AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = et.text.toString().toIntOrNull() ?: current
                apply(v.coerceIn(1, 365))
                bindState()
            }
            .show()
    }

    private fun refreshNow() {
        if (!prefs.isLoggedIn) {
            toast("请先登录")
            return
        }
        toast("正在刷新…")
        scope.launch {
            try {
                val api = XtApi(prefs)
                val snap = api.fetchAll()
                prefs.cache = Task.encode(snap.tasks)
                prefs.lastSync = System.currentTimeMillis()
                bindState()
                toast("已更新 ${snap.tasks.size} 条" + if (snap.errors.isEmpty()) "" else "（${snap.errors.size} 项失败）")
            } catch (e: Exception) {
                toast("刷新失败：" + (e.message ?: "未知错误"))
            }
        }
    }

    private fun showLog() {
        val tv = TextView(this)
        tv.text = DebugLog.dump().ifEmpty { "（暂无日志）" }
        tv.textSize = 10f
        tv.setTextIsSelectable(true)
        tv.setPadding(28, 28, 28, 28)
        tv.movementMethod = ScrollingMovementMethod()
        val scroll = android.widget.ScrollView(this)
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("抓取日志")
            .setView(scroll)
            .setNeutralButton("清空") { _, _ ->
                DebugLog.clear()
                toast("已清空")
            }
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("抓取日志", DebugLog.dump()))
                toast("日志已复制到剪贴板")
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("会清除本机保存的登录态、课程和作业缓存，需要重新登录学习通。确定吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("退出") { _, _ ->
                prefs.clearAccount()
                DebugLog.clear()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                WebView(this).apply {
                    clearCache(true)
                    destroy()
                }
                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                finish()
            }
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清除缓存与本地数据")
            .setMessage("会退出登录、清空已抓取的数据和设置，需要重新登录学习通。确定吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                prefs.clearAll()
                DebugLog.clear()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                toast("已清除")
                val i = Intent(this, MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                finish()
            }
            .show()
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle("恢复被清理的条目")
            .setMessage("清空所有的「手动移除」记录，之前移掉的条目会在下次刷新后重新出现。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                prefs.clearDeleted()
                bindState()
                toast("已恢复")
            }
            .show()
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
    } catch (e: Exception) {
        "1.0"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_LOGIN = 2001
    }
}

package com.xxkg.board

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

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvAccount: TextView
    private lateinit var tvSync: TextView
    private lateinit var btnX: TextView
    private lateinit var btnY: TextView
    private lateinit var btnGreen: TextView
    private lateinit var btnOrange: TextView
    private lateinit var btnRestore: TextView
    private lateinit var btnLogout: TextView
    private lateinit var btnUpdate: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_settings)

        tvAccount = findViewById(R.id.tvAccount)
        tvSync = findViewById(R.id.tvSync)
        btnX = findViewById(R.id.btnX)
        btnY = findViewById(R.id.btnY)
        btnGreen = findViewById(R.id.btnGreen)
        btnOrange = findViewById(R.id.btnOrange)
        btnRestore = findViewById(R.id.btnRestore)
        btnLogout = findViewById(R.id.btnLogout)
        btnUpdate = findViewById(R.id.btnUpdate)

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvVersion).text = "学习通小工具 xxtT v" + versionName() + " · 仅本机使用"
        findViewById<TextView>(R.id.btnLog).setOnClickListener { showLog() }
        btnRestore.setOnClickListener { confirmRestore() }
        btnLogout.setOnClickListener { confirmLogout() }
        btnUpdate.setOnClickListener { UpdateManager.manualCheck(this) }
        btnX.setOnClickListener { editDays("待批阅时间窗口（天）", prefs.xDays) { prefs.xDays = it } }
        btnY.setOnClickListener { editDays("已完成保留时间（天）", prefs.yDays) { prefs.yDays = it } }
        btnGreen.setOnClickListener { editGreenDays() }
        btnOrange.setOnClickListener { editOrangeDays() }

        bindState()
    }

    override fun onResume() {
        super.onResume()
        bindState()
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
        btnGreen.text = "${prefs.greenDays} 天"
        btnOrange.text = "${prefs.orangeDays} 天"
        btnLogout.visibility = if (prefs.isLoggedIn) View.VISIBLE else View.GONE
        btnUpdate.text = "检查更新（当前 v" + versionName() + "）"
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

    /** 绿色节点必须比橙色节点大至少 1 天，否则两个颜色会重叠。 */
    private fun editGreenDays() {
        editDays("绿色提醒：剩余至少（天）", prefs.greenDays) { value ->
            val green = value.coerceAtLeast(prefs.orangeDays + 1)
            prefs.greenDays = green
            if (green != value) toast("绿色节点需大于橙色节点，已改为 $green 天")
        }
    }

    /** 橙色节点至少 1 天，且必须小于绿色节点。 */
    private fun editOrangeDays() {
        editDays("橙色提醒：剩余至少（天）", prefs.orangeDays) { value ->
            val orange = value.coerceIn(1, (prefs.greenDays - 1).coerceAtLeast(1))
            prefs.orangeDays = orange
            if (orange != value) toast("橙色节点需小于绿色节点，已改为 $orange 天")
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
}

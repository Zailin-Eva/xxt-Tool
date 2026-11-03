package com.xxkg.board

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

/** 内嵌登录页：先选学习通或雨课堂，再由用户自己完成登录，成功后收割 Cookie。 */
class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvHint: TextView
    private var done = false
    private var defaultUa = ""
    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            if (done) return
            checkLogin()
            if (!done) handler.postDelayed(this, 900)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_login)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        tvTitle = findViewById(R.id.tvLoginTitle)
        tvHint = findViewById(R.id.tvHint)

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnSwitch).setOnClickListener { showChooser() }

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            // 保持默认（浏览器）UA：雨课堂/学习通对伪装客户端的 UA 会触发二次验证
        }
        defaultUa = webView.settings.userAgentString

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (prefs.provider != Provider.YUKETANG) return null
                val url = request?.url?.toString() ?: return null
                if (!request.isForMainFrame) return null
                if (!url.startsWith("https://${prefs.ytHost}/v2/web/")) return null
                return patchedHtml(url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (prefs.provider == Provider.YUKETANG) {
                    // 雨课堂登录页是固定宽度的桌面布局，默认只显示左半边；滚到右侧的二维码卡片
                    view?.post { view.scrollTo((view.width * 0.6).toInt(), 0) }
                }
                checkLogin()
            }
        }

        if (savedInstanceState == null) {
            showChooser()
        } else {
            startLogin(prefs.provider)
        }
    }

    /** 雨课堂 viewport 缺 width=device-width，会按 980px 桌面布局渲染；在主文档里补上。 */
    private fun patchedHtml(url: String): WebResourceResponse? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", YtApi.BROWSER_UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                CookieManager.getInstance().getCookie(url)?.let {
                    if (it.isNotEmpty()) setRequestProperty("Cookie", it)
                }
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val patched = html.replace(
                "content=\"initial-scale=1",
                "content=\"width=device-width,initial-scale=1"
            )
            WebResourceResponse("text/html", "utf-8", patched.byteInputStream())
        } catch (e: Exception) {
            null
        }
    }

    private fun showChooser() {
        val items = arrayOf(
            "学习通登录",
            "雨课堂登录（${prefs.ytHost}）",
            "雨课堂：更换服务器",
        )
        AlertDialog.Builder(this)
            .setTitle("选择登录方式")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startLogin(Provider.CHAOXING)
                    1 -> startLogin(Provider.YUKETANG)
                    2 -> editYtHost()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                if (!done) finish()
            }
            .show()
    }

    private fun editYtHost() {
        val input = EditText(this).apply {
            setText(prefs.ytHost)
            setSingleLine(true)
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("雨课堂服务器")
            .setMessage("填域名，例如 changjiang.yuketang.cn 或 www.yuketang.cn")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    prefs.ytHost = v
                    Toast.makeText(this, "已改为 ${prefs.ytHost}", Toast.LENGTH_SHORT).show()
                    startLogin(Provider.YUKETANG)
                }
            }
            .show()
    }

    private fun startLogin(provider: Provider) {
        prefs.provider = provider
        done = false
        tvTitle.text = "登录" + provider.label
        tvHint.text = if (provider == Provider.YUKETANG) {
            "请用微信扫码登录；登录完成后会自动返回"
        } else {
            "请使用手机号或学号登录，登录完成后会自动返回"
        }
        if (provider == Provider.YUKETANG) {
            // 雨课堂网页按视口宽度切换移动/桌面布局，桌面布局在手机屏上会只显示左半边
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.userAgentString = YtApi.BROWSER_UA
            webView.setInitialScale(35)
        } else {
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            webView.settings.userAgentString = defaultUa
        }
        when (provider) {
            Provider.YUKETANG -> webView.loadUrl(YtApi.loginUrl(prefs.ytHost))
            Provider.CHAOXING -> webView.loadUrl(XtApi.LOGIN_URL)
        }
        handler.removeCallbacks(poller)
        handler.postDelayed(poller, 1500)
    }

    private fun checkLogin() {
        if (done) return
        when (prefs.provider) {
            Provider.YUKETANG -> {
                val map = Cookies.harvestFor(prefs.ytHost)
                if (Cookies.looksYtLoggedIn(map)) finishYtLogin(map)
            }

            Provider.CHAOXING -> {
                val map = Cookies.harvest()
                if (Cookies.looksLoggedIn(map)) finishCxLogin(map)
            }
        }
    }

    private fun finishCxLogin(map: Map<String, String>) {
        done = true
        handler.removeCallbacks(poller)
        prefs.cookie = Cookies.header(map)
        prefs.uid = map["_uid"].orEmpty()
        prefs.puid = map["sso_puid"] ?: map["_tid"] ?: ""
        prefs.loggedOut = false
        CookieManager.getInstance().flush()
        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK, Intent())
        finish()
    }

    private fun finishYtLogin(map: Map<String, String>) {
        done = true
        handler.removeCallbacks(poller)
        prefs.ytCookie = Cookies.header(map)
        // 先用会话串占位，保证 isLoggedIn 立刻为 true；用户信息异步补齐
        prefs.ytUid = map["sessionid"]?.take(8).orEmpty().ifEmpty { "yuketang" }
        prefs.loggedOut = false
        CookieManager.getInstance().flush()

        Thread {
            val user = try {
                YtApi(prefs).fetchUser()
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (user != null) {
                    prefs.ytUid = user.first
                    prefs.ytName = user.second
                }
                Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK, Intent())
                finish()
            }
        }.start()
    }

    override fun onBackPressed() {
        if (!done && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }
}

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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** 内嵌学习通登录页：用户自己输账号密码、过验证码，成功后收割 Cookie。 */
class LoginActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private var done = false
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

        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }

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
            // 保持默认（浏览器）UA：学习通对伪装客户端的 UA 会触发二次验证
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                checkLogin()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }
        }

        webView.loadUrl(XtApi.LOGIN_URL)
        handler.postDelayed(poller, 1500)
    }

    private fun checkLogin() {
        if (done) return
        val map = Cookies.harvest()
        if (Cookies.looksLoggedIn(map)) {
            finishLogin(map)
        }
    }

    private fun finishLogin(map: Map<String, String>) {
        done = true
        handler.removeCallbacks(poller)
        prefs.cookie = Cookies.header(map)
        prefs.uid = map["_uid"].orEmpty()
        prefs.puid = map["sso_puid"] ?: map["_tid"] ?: ""
        CookieManager.getInstance().flush()
        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK, Intent())
        finish()
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

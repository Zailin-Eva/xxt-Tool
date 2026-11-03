package com.xxkg.board

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
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
import org.json.JSONObject
import java.util.Locale

/** 用已登录的 Cookie 打开学习通具体页面（作业答题 / 考试 / 详情）。 */
class WebActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvError: TextView
    private lateinit var errorBox: View
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var lastError: String = ""
    private var currentUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        setContentView(R.layout.activity_web)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        tvTitle = findViewById(R.id.tvTitle)
        progress = findViewById(R.id.progress)
        webView = findViewById(R.id.webView)
        tvError = findViewById(R.id.tvError)
        errorBox = findViewById(R.id.errorBox)

        tvTitle.text = title.ifEmpty { "学习通" }
        tvTitle.setOnLongClickListener {
            showDiagnostics()
            true
        }
        findViewById<TextView>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnRetry).setOnClickListener { load(currentUrl) }

        configureWebView()
        injectCookies()

        load(url.ifEmpty { "https://i.chaoxing.com" }, referer)
    }

    // ------------------------------------------------------------ 初始化

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = XtApi.BROWSER_UA
            allowFileAccess = false
            allowContentAccess = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // 学习通页面里的“继续答题 / 修改答案”等按钮不是普通链接，而是调用它自己的
        // jsBridge（androidjsbridge.postNotification("CLIENT_OPEN_URL", ...)）交给原生 App 打开。
        // 不实现这个桥，按钮就会静默无反应，所以这里补一个最小实现。
        webView.addJavascriptInterface(ChaoxingJsBridge(), "androidjsbridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return handleScheme(uri)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                errorBox.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                if (tvTitle.text.isNullOrEmpty() || tvTitle.text == "学习通") {
                    view?.title?.takeIf { it.isNotBlank() }?.let { tvTitle.text = it }
                }
                // 告诉页面“当前是 Android 原生壳”，它才会走 androidjsbridge 而不是 iframe 协议
                view?.evaluateJavascript(BRIDGE_ATTACH_JS, null)
                DebugLog.log("WebView 完成: ${view?.url} | ${view?.title}")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    showError("加载失败：${error?.description ?: "网络错误"}\n${request.url}")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    showError("页面返回 HTTP ${errorResponse?.statusCode}\n${request.url}")
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                if (result == null) return false
                if (isFinishing || isDestroyed) {
                    result.cancel()
                    return true
                }
                AlertDialog.Builder(this@WebActivity)
                    .setMessage(message.orEmpty())
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?
            ): Boolean {
                if (result == null) return false
                if (isFinishing || isDestroyed) {
                    result.cancel()
                    return true
                }
                AlertDialog.Builder(this@WebActivity)
                    .setMessage(message.orEmpty())
                    .setPositiveButton("确定") { _, _ -> result.confirm() }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                if (result == null) return false
                if (isFinishing || isDestroyed) {
                    result.cancel()
                    return true
                }
                val input = EditText(this@WebActivity).apply {
                    setText(defaultValue.orEmpty())
                    setPadding(32, 16, 32, 16)
                }
                AlertDialog.Builder(this@WebActivity)
                    .setMessage(message.orEmpty())
                    .setView(input)
                    .setPositiveButton("确定") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("取消") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                if (callback == null) return false
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = try {
                    params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                } catch (e: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                }
                return try {
                    startActivityForResult(intent, REQ_FILE)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (resultMsg == null) return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

                val popup = WebView(this@WebActivity)
                popup.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    userAgentString = XtApi.BROWSER_UA
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme?.lowercase(Locale.ROOT)
                        return if (scheme == "http" || scheme == "https") {
                            false
                        } else {
                            openExternal(uri)
                        }
                    }
                }
                popup.webChromeClient = this

                val dialog = Dialog(this@WebActivity)
                dialog.setContentView(popup)
                dialog.setOnDismissListener { popup.destroy() }
                dialog.show()
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                // 当前版本只申请了网络权限；摄像头/麦克风类作业先拒绝，避免误导
                request?.deny()
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null &&
                    consoleMessage.messageLevel() != ConsoleMessage.MessageLevel.LOG
                ) {
                    DebugLog.log(
                        "WebView JS ${consoleMessage.messageLevel()}: " +
                            "${consoleMessage.message()} (${consoleMessage.lineNumber()})"
                    )
                }
                return true
            }
        }

        webView.setDownloadListener { downloadUrl, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            } catch (e: Exception) {
                toast("无法打开下载链接")
            }
        }
    }

    // ------------------------------------------------------------ 学习通 jsBridge

    /** 页面里的 androidjsbridge.postNotification(name, json) 会进到这里（非 UI 线程）。 */
    private inner class ChaoxingJsBridge {
        @JavascriptInterface
        fun postNotification(name: String?, payload: String?) {
            if (name.isNullOrEmpty()) return
            val safePayload = payload.orEmpty()
            runOnUiThread { handleBridgeNotification(name, safePayload) }
        }
    }

    private fun isChaoxingPage(): Boolean {
        val host = Uri.parse(webView.url.orEmpty()).host.orEmpty().lowercase(Locale.ROOT)
        return host == "chaoxing.com" || host.endsWith(".chaoxing.com")
    }

    private fun handleBridgeNotification(name: String, payload: String) {
        if (!isChaoxingPage()) return
        DebugLog.log("jsBridge: $name ${payload.take(160)}")
        when (name) {
            // 页面要打开下一个原生页面：继续答题 / 查看答案 / 修改作业 / 外部链接
            "CLIENT_OPEN_URL" -> {
                val json = try {
                    JSONObject(payload)
                } catch (e: Exception) {
                    null
                } ?: return
                val raw = json.optString("webUrl").trim()
                if (raw.isEmpty()) return
                val url = raw
                    .substringBefore("#INNER")
                    .replace(Regex("&?keyboardDisplayRequiresUserAction=1"), "")
                if (url.isEmpty()) return
                val absolute = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("//") -> "https:$url"
                    url.startsWith("/") -> "https://mooc1-api.chaoxing.com$url"
                    else -> return
                }
                val host = Uri.parse(absolute).host.orEmpty().lowercase(Locale.ROOT)
                if (host == "chaoxing.com" || host.endsWith(".chaoxing.com")) {
                    load(absolute, currentUrl)
                } else {
                    openExternal(Uri.parse(absolute))
                }
            }

            // 页面要求刷新（例如提交后）
            "CLIENT_REFRESH_EVENT" -> webView.reload()

            // 其它通知（图片预览、自定义菜单、返回拦截等）暂不处理
            else -> Unit
        }
    }

    private fun injectCookies() {
        val yt = prefs.provider == Provider.YUKETANG
        val header = if (yt) prefs.ytCookie else prefs.cookie
        if (header.isEmpty()) {
            DebugLog.log("WebView: 本地没有登录 Cookie")
            return
        }
        val cm = CookieManager.getInstance()
        val hosts = if (yt) {
            listOf("https://${prefs.ytHost}", "https://${prefs.ytHost}/")
        } else {
            listOf(
                "https://mooc1-api.chaoxing.com",
                "https://mooc1.chaoxing.com",
                "https://mooc2-ans.chaoxing.com",
                "https://chaoxing.com",
                "https://i.chaoxing.com",
            )
        }
        var count = 0
        for (host in hosts) {
            for (part in header.split(";")) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (key.isEmpty()) continue
                try {
                    cm.setCookie(host, "$key=$value; path=/")
                    count++
                } catch (e: Exception) {
                    // 个别 Cookie 格式不合法就跳过
                }
            }
        }
        cm.flush()
        DebugLog.log("WebView: 注入 Cookie $count 条")
    }

    private fun load(url: String, referer: String = "") {
        currentUrl = url
        lastError = ""
        errorBox.visibility = View.GONE
        webView.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        DebugLog.log("WebView 打开: ${url.take(140)}")
        try {
            val headers = HashMap<String, String>()
            if (referer.isNotEmpty()) headers["Referer"] = referer
            webView.loadUrl(url, headers)
        } catch (e: Exception) {
            showError("加载失败：${e.message}")
        }
    }

    private fun handleScheme(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return when (scheme) {
            "http", "https" -> false
            "intent", "chaoxing", "tel", "mailto", "weixin", "alipays", "mqq" ->
                openExternal(uri)
            else -> false
        }
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            startActivity(intent)
            true
        } catch (e: Exception) {
            toast("未安装对应的应用")
            true
        }
    }

    private fun showError(message: String) {
        lastError = message
        DebugLog.log("WebView 错误: ${message.replace("\n", " ")}")
        progress.visibility = View.GONE
        webView.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
        tvError.text = message
    }

    private fun showDiagnostics() {
        val info = buildString {
            append("URL: ").append(webView.url ?: currentUrl).append("\n\n")
            append("标题: ").append(webView.title ?: "-").append("\n\n")
            append("错误: ").append(lastError.ifEmpty { "无" }).append("\n\n")
            append("UA: ").append(webView.settings.userAgentString)
        }
        AlertDialog.Builder(this)
            .setTitle("页面诊断")
            .setMessage(info)
            .setPositiveButton("复制") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("页面诊断", info))
                toast("已复制")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            val callback = filePathCallback
            filePathCallback = null
            if (callback == null) return
            if (resultCode != Activity.RESULT_OK) {
                callback.onReceiveValue(null)
                return
            }
            val clip = data?.clipData
            val single = data?.data
            val uris: Array<Uri>? = when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri!! }
                single != null -> arrayOf(single)
                else -> null
            }
            callback.onReceiveValue(uris)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_TITLE = "title"
        private const val REQ_FILE = 4001

        /**
         * 学习通自己的 CXJSBridge.js 在 device 不是 android 时会走 jsbridge:// iframe，
         * 纯 WebView 里没人接收，按钮就“点了没反应”。页面加载完后把它切成 android 模式。
         */
        private const val BRIDGE_ATTACH_JS =
            "(function(){try{" +
                "if(window.jsBridge&&typeof window.jsBridge.setDevice==='function'){" +
                "window.jsBridge.setDevice('android');return 'android';}" +
                "return 'no-bridge';}catch(e){return 'error';}})()"
    }
}

package com.xxkg.board

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 检查更新 → 弹窗让用户选择 → 下载 → 校验 → 交给系统安装器覆盖安装。
 */
object UpdateManager {

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private var checking = false
    private var installing = false

    /** 启动时每天检查一次，静默失败。 */
    fun maybeAutoCheck(activity: AppCompatActivity) {
        val prefs = Prefs.get(activity)
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < DAY_MS) return
        check(activity, prefs, false)
    }

    /** 个人页手动检查，失败/最新都会提示。 */
    fun manualCheck(activity: AppCompatActivity) {
        check(activity, Prefs.get(activity), true)
    }

    private fun check(activity: AppCompatActivity, prefs: Prefs, manual: Boolean) {
        if (checking) return
        val current = UpdateChecker.currentVersion(activity)
        if (current.second.endsWith("-debug")) {
            if (manual) toast(activity, "调试包不支持自动更新")
            return
        }
        checking = true
        prefs.lastUpdateCheck = System.currentTimeMillis()
        if (manual) toast(activity, "正在检查更新…")

        scope.launch {
            var failed = false
            val info = try {
                UpdateChecker.check(activity)
            } catch (e: Exception) {
                failed = true
                DebugLog.log("检查更新失败: ${e.message}")
                null
            }
            checking = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            if (failed) {
                if (manual) toast(activity, "检查更新失败，请稍后再试")
                return@launch
            }
            if (info == null) {
                if (manual) toast(activity, "已是最新版本")
                return@launch
            }
            showDialog(activity, info)
        }
    }

    private fun showDialog(activity: AppCompatActivity, info: UpdateInfo) {
        val scroll = ScrollView(activity)
        val text = TextView(activity)
        text.text = buildString {
            append(info.changelog.trim().ifEmpty { "暂无更新说明" })
            if (info.size > 0) {
                append("\n\n安装包大小：")
                append(formatSize(info.size))
            }
            append("\n\n如果应用内安装失败，可以点「打开网页」到 GitHub 手动下载。")
        }
        text.textSize = 13f
        text.setTextIsSelectable(true)
        text.setPadding(32, 24, 32, 24)
        scroll.addView(text)
        scroll.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()
        )

        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.versionName}")
            .setView(scroll)
            .setPositiveButton("立即更新") { _, _ -> downloadAndInstall(activity, info) }
            .setNegativeButton("以后再说", null)
            .setNeutralButton("打开网页") { _, _ -> openProjectPage(activity) }
            .show()
    }

    /** 跳转到 GitHub 仓库，方便手动下载安装包。 */
    private fun openProjectPage(activity: AppCompatActivity) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/siye4366/xxt-Tool"))
            )
        } catch (e: Exception) {
            toast(activity, "无法打开浏览器")
        }
    }

    private fun downloadAndInstall(activity: AppCompatActivity, info: UpdateInfo) {
        if (installing) return
        installing = true
        val file = File(activity.cacheDir, "update-${info.versionCode}.apk")
        if (file.exists()) file.delete()

        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        progress.max = 100
        progress.isIndeterminate = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载 v${info.versionName}")
            .setView(progress)
            .setCancelable(false)
            .show()

        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                download(info, file) { percent ->
                    activity.runOnUiThread {
                        if (dialog.isShowing) progress.progress = percent
                    }
                }
            }
            if (dialog.isShowing) dialog.dismiss()
            if (activity.isFinishing || activity.isDestroyed) {
                installing = false
                return@launch
            }
            if (!ok) {
                installing = false
                AlertDialog.Builder(activity)
                    .setTitle("下载失败")
                    .setMessage("已尝试直连和镜像。可以在浏览器打开发布页手动下载安装包。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("打开网页") { _, _ -> openProjectPage(activity) }
                    .show()
                return@launch
            }
            val error = withContext(Dispatchers.IO) { verifyApk(activity, file) }
            if (error != null) {
                installing = false
                file.delete()
                toast(activity, error)
                return@launch
            }
            installApk(activity, file)
            installing = false
        }
    }

    private fun download(info: UpdateInfo, file: File, onProgress: (Int) -> Unit): Boolean {
        val urls = UpdateChecker.downloadUrls(info.apkUrl)
        for ((index, url) in urls.withIndex()) {
            if (index > 0) DebugLog.log("下载改用镜像: ${url.take(80)}")
            if (downloadFrom(url, info, file, onProgress)) return true
            if (file.exists()) file.delete()
        }
        return false
    }

    private fun downloadFrom(
        url: String,
        info: UpdateInfo,
        file: File,
        onProgress: (Int) -> Unit,
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "xxtT")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var sum = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            sum += read
                            if (total > 0) {
                                onProgress((sum * 100 / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            DebugLog.log("下载更新失败: ${e.message}")
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(activity: AppCompatActivity, file: File): String? {
        val pm = activity.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: return "无法读取更新包"
        if (archive.packageName != activity.packageName) return "更新包包名不一致"
        val newCode = PackageInfoCompat.getLongVersionCode(archive).toInt()
        val currentCode = UpdateChecker.currentVersion(activity).first
        if (newCode <= currentCode) return "更新包版本不高于当前版本"
        val currentInfo = try {
            pm.getPackageInfo(activity.packageName, flags)
        } catch (e: Exception) {
            return "无法读取当前应用签名"
        }
        val currentSigs = signatureHashes(currentInfo)
        val archiveSigs = signatureHashes(archive)
        if (currentSigs.isEmpty() || archiveSigs.isEmpty() ||
            currentSigs.intersect(archiveSigs).isEmpty()
        ) {
            return "更新包签名与当前应用不一致，无法覆盖安装"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signatureHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()
        return signatures.map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun installApk(activity: AppCompatActivity, file: File) {
        val pm = activity.packageManager
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            toast(activity, "请允许“安装未知应用”后重试")
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (e: Exception) {
                DebugLog.log("打开未知来源设置失败: ${e.message}")
            }
            return
        }

        // 交给系统安装器（和手动点 APK 一样），比 PackageInstaller 自建 session 兼容性更好；
        // 某些系统上后者会在 commit 阶段返回 STATUS_FAILURE(1)。
        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            DebugLog.log("已交给系统安装器: ${file.name}")
            toast(activity, "请在系统安装界面点「更新」")
        } catch (e: Exception) {
            DebugLog.log("启动安装失败: ${e.message}")
            toast(activity, "安装失败：${e.message}")
        }
    }

    private fun formatSize(bytes: Long): String {
        return if (bytes < 1024 * 1024) {
            String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
        } else {
            String.format(Locale.CHINA, "%.1f MB", bytes / 1024.0 / 1024.0)
        }
    }

    private fun toast(activity: AppCompatActivity, message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}

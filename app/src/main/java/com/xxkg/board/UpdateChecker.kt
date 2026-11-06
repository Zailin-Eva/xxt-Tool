package com.xxkg.board

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val changelog: String,
    val apkUrl: String,
    val size: Long,
)

/**
 * 从 GitHub Releases 检查新版本。
 *
 * 国内网络下 api.github.com 和 github.com 经常一个能通、一个不能，
 * 开代理/关代理的表现也不一样。所以直连失败后按顺序尝试镜像，
 * 下载同理（镜像只做 URL 前缀转发，不改安装包内容，签名校验仍然生效）。
 */
object UpdateChecker {

    private const val REPO = "siye4366/xxt-Tool"
    private const val DIRECT_API = "https://api.github.com/repos/$REPO/releases/latest"

    private val API_URLS = listOf(
        DIRECT_API,
        "https://gh-proxy.com/https://api.github.com/repos/$REPO/releases/latest",
        "https://ghproxy.net/https://api.github.com/repos/$REPO/releases/latest",
        "https://api.kkgithub.com/repos/$REPO/releases/latest",
    )

    private val DOWNLOAD_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** 当前安装包的 (versionCode, versionName)。 */
    fun currentVersion(context: Context): Pair<Int, String> {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info).toInt() to (info.versionName ?: "?")
        } catch (e: Exception) {
            0 to "?"
        }
    }

    /** v2.1.0 / 2.1.0 / v2.1 -> 20100 / 20100 / 20100 */
    fun parseVersionCode(tag: String): Int? {
        val value = tag.trim().removePrefix("v").removePrefix("V")
        val parts = value.split(".")
        if (parts.size < 2) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (major < 0 || minor < 0 || patch < 0) return null
        return major * 10000 + minor * 100 + patch
    }

    /** 直连 + 镜像，按顺序尝试。 */
    fun downloadUrls(apkUrl: String): List<String> {
        if (apkUrl.isEmpty()) return emptyList()
        return listOf(apkUrl) + DOWNLOAD_PREFIXES.map { it + apkUrl }
    }

    /**
     * 返回 null 表示已是最新；网络/解析失败抛 IOException，方便调用方区分提示。
     *
     * 直连和镜像同时发起，谁先成功用谁：开代理时直连通常最快，
     * 关代理时镜像更快，用户不用等一串超时。
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = firstBody(API_URLS)
        parse(context, body)
    }

    private suspend fun firstBody(urls: List<String>): String = coroutineScope {
        val channel = Channel<Pair<String, Result<String>>>(Channel.UNLIMITED)
        for (url in urls) {
            launch(Dispatchers.IO) {
                channel.trySend(url to runCatching { fetch(url) })
            }
        }
        val errors = ArrayList<String>()
        var body: String? = null
        for (i in urls.indices) {
            val (url, result) = channel.receive()
            if (result.isSuccess) {
                body = result.getOrNull()
                DebugLog.log("更新源可用: ${hostOf(url)}")
            } else {
                errors.add("${hostOf(url)}: ${result.exceptionOrNull()?.message ?: "失败"}")
            }
            if (body != null) break
        }
        channel.close()
        coroutineContext.cancelChildren()
        body ?: throw IOException("检查更新失败，已尝试直连和镜像（${errors.joinToString("；")}）")
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "xxtT")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 200) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun parse(context: Context, body: String): UpdateInfo? {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("解析更新信息失败")
        }

        val tag = json.optString("tag_name")
        val versionCode = parseVersionCode(tag) ?: return null
        val currentCode = currentVersion(context).first
        if (versionCode <= currentCode) return null

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("-debug", ignoreCase = true)) continue
            apkUrl = asset.optString("browser_download_url")
            size = asset.optLong("size")
            break
        }
        if (apkUrl.isEmpty()) return null

        return UpdateInfo(
            versionCode = versionCode,
            versionName = tag.removePrefix("v").removePrefix("V"),
            tag = tag,
            changelog = if (json.isNull("body")) "" else json.optString("body"),
            apkUrl = apkUrl,
            size = size,
        )
    }

    private fun hostOf(url: String): String =
        url.removePrefix("https://").substringBefore('/')
}

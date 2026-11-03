package com.xxkg.board

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
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

/** 从 GitHub Releases 检查新版本。 */
object UpdateChecker {

    private const val REPO = "siye4366/xxt-Tool"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
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

    /**
     * 返回 null 表示已是最新；网络/解析失败抛 IOException，方便调用方区分提示。
     */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API)
            .header("User-Agent", "xxtT")
            .header("Accept", "application/vnd.github+json")
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                if (response.code != 200) {
                    throw IOException("HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message ?: "网络错误")
        }

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("解析更新信息失败")
        }

        val tag = json.optString("tag_name")
        val versionCode = parseVersionCode(tag) ?: return@withContext null
        val currentCode = currentVersion(context).first
        if (versionCode <= currentCode) return@withContext null

        val assets = json.optJSONArray("assets") ?: return@withContext null
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
        if (apkUrl.isEmpty()) return@withContext null

        UpdateInfo(
            versionCode = versionCode,
            versionName = tag.removePrefix("v").removePrefix("V"),
            tag = tag,
            changelog = if (json.isNull("body")) "" else json.optString("body"),
            apkUrl = apkUrl,
            size = size,
        )
    }
}

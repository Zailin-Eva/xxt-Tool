package com.xxkg.board

import android.webkit.CookieManager

/** 从 WebView 的 CookieManager 里收集 chaoxing 全站 Cookie。 */
object Cookies {

    val HOSTS = listOf(
        "https://chaoxing.com",
        "https://i.chaoxing.com",
        "https://mooc1.chaoxing.com",
        "https://mooc1-api.chaoxing.com",
        "https://mooc2-ans.chaoxing.com",
        "https://passport2.chaoxing.com",
        "https://passport2-api.chaoxing.com",
        "https://mobilelearn.chaoxing.com",
        "https://notice.chaoxing.com"
    )

    fun harvest(): Map<String, String> {
        val cm = CookieManager.getInstance()
        val map = LinkedHashMap<String, String>()
        for (h in HOSTS) {
            val raw = try {
                cm.getCookie(h)
            } catch (e: Exception) {
                null
            } ?: continue
            for (part in raw.split(";")) {
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    if (k.isNotEmpty()) map[k] = v
                }
            }
        }
        return map
    }

    fun header(map: Map<String, String>): String =
        map.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** 收割指定域名的 Cookie（雨课堂等单域名站点）。 */
    fun harvestFor(host: String): Map<String, String> {
        val url = if (host.startsWith("http")) host else "https://$host"
        val raw = try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (part in raw.split(";")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val k = part.substring(0, idx).trim()
                val v = part.substring(idx + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        return map
    }

    /** 让指定域名下的这些 Cookie 过期，用于单独退出某个账号。 */
    fun clearFor(host: String, header: String) {
        if (header.isEmpty()) return
        val url = if (host.startsWith("http")) host else "https://$host"
        val cm = CookieManager.getInstance()
        for (part in header.split(";")) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            if (key.isEmpty()) continue
            try {
                cm.setCookie(url, "$key=; Max-Age=0; path=/")
            } catch (e: Exception) {
                // 忽略单个非法 Cookie
            }
        }
        cm.flush()
    }

    /** 雨课堂登录成功的标志（Django 风格的 session）。 */
    fun looksYtLoggedIn(map: Map<String, String>): Boolean =
        map.containsKey("sessionid") || map.containsKey("session_id")

    /** 判定是否已经登录成功 */
    fun looksLoggedIn(map: Map<String, String>): Boolean =
        map.containsKey("_uid") || map.containsKey("_d") || map.containsKey("uf")
}

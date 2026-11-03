package com.xxkg.board

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.io.File

/**
 * 仅 debug 包存在：把外部准备好的雨课堂登录态写入 Prefs，便于在没有扫码条件下做拉取验证。
 *
 * adb shell am start -n com.xxkg.board/.DevSeedYtActivity --es file /data/user/0/com.xxkg.board/files/yt_cookie.txt
 */
class DevSeedYtActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs.get(this)
        var cookie = ""
        var host = "changjiang.yuketang.cn"
        var uid = "yuketang"
        val path = intent.getStringExtra("file")
        if (!path.isNullOrEmpty()) {
            try {
                val f = File(path)
                if (f.exists()) {
                    for (line in f.readLines()) {
                        when {
                            line.startsWith("cookie=") -> cookie = line.removePrefix("cookie=").trim()
                            line.startsWith("host=") -> host = line.removePrefix("host=").trim()
                            line.startsWith("uid=") -> uid = line.removePrefix("uid=").trim()
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
        if (cookie.isNotEmpty()) {
            p.provider = Provider.YUKETANG
            p.ytHost = host
            p.ytCookie = cookie
            p.ytUid = uid
            p.loggedOut = false
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}

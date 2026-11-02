package com.xxkg.board

import android.app.Activity
import android.os.Bundle
import java.io.File

/**
 * 仅 debug 包存在的调试入口：把外部准备好的登录态写入 Prefs，
 * 便于在没有真机登录的条件下做抓取验证。release 包不包含本类。
 *
 * adb shell am start -n com.xxkg.board/.DevSeedActivity --es file /data/local/tmp/c.txt
 */
class DevSeedActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Prefs.get(this)
        val path = intent.getStringExtra("file")
        if (!path.isNullOrEmpty()) {
            try {
                val f = File(path)
                if (f.exists()) {
                    for (line in f.readLines()) {
                        when {
                            line.startsWith("cookie=") -> p.cookie = line.removePrefix("cookie=")
                            line.startsWith("uid=") -> p.uid = line.removePrefix("uid=")
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
        intent.getStringExtra("cookie")?.let { p.cookie = it }
        intent.getStringExtra("uid")?.let { p.uid = it }
        finish()
    }
}

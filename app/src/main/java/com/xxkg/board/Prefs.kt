package com.xxkg.board

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** 全部本地状态：登录 Cookie、设置、缓存、手动删除记录。 */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("board", Context.MODE_PRIVATE)

    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putS(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun putI(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun l(k: String, d: Long) = sp.getLong(k, d)
    private fun putL(k: String, v: Long) = sp.edit().putLong(k, v).apply()

    /** 登录后的 Cookie 串（加密保存） */
    var cookie: String
        get() = Sec.dec(s(K_COOKIE))
        set(v) = putS(K_COOKIE, Sec.enc(v))

    /** _uid，作为 gas/taskmsg 的 userid */
    var uid: String
        get() = s(K_UID)
        set(v) = putS(K_UID, v)

    var puid: String
        get() = s(K_PUID)
        set(v) = putS(K_PUID, v)

    var userName: String
        get() = s(K_NAME)
        set(v) = putS(K_NAME, v)

    /** 待批阅时间窗口 x（天） */
    var xDays: Int
        get() = i(K_X, 7)
        set(v) = putI(K_X, v)

    /** 已完成保留窗口 y（天） */
    var yDays: Int
        get() = i(K_Y, 7)
        set(v) = putI(K_Y, v)

    /** 未完成排序方式 0..3 */
    var sortMode: Int
        get() = i(K_SORT, 0)
        set(v) = putI(K_SORT, v)

    var lastSync: Long
        get() = l(K_SYNC, 0L)
        set(v) = putL(K_SYNC, v)

    /** 最近一次抓取的原始任务 JSON */
    var cache: String
        get() = s(K_CACHE)
        set(v) = putS(K_CACHE, v)

    val isLoggedIn: Boolean
        get() = cookie.isNotEmpty() && uid.isNotEmpty()

    /** 是否主动退出过登录（区别于 Cookie 过期） */
    var loggedOut: Boolean
        get() = sp.getBoolean(K_LOGGED_OUT, false)
        set(v) = sp.edit().putBoolean(K_LOGGED_OUT, v).apply()

    /** 退出登录：清账号、课程、作业缓存；保留时间窗口/排序等本机设置 */
    fun clearAccount() {
        sp.edit()
            .remove(K_COOKIE)
            .remove(K_UID)
            .remove(K_PUID)
            .remove(K_NAME)
            .remove(K_CACHE)
            .remove(K_STATE)
            .remove(K_DELETED)
            .remove(K_SYNC)
            .apply()
        loggedOut = true
    }

    /** 设备唯一标识，用于伪装官方 UA */
    val uniqueId: String
        get() {
            var v = s(K_UNIQ)
            if (v.isEmpty()) {
                v = UUID.randomUUID().toString().replace("-", "")
                putS(K_UNIQ, v)
            }
            return v
        }

    /** 用户手动清理掉的条目 key */
    fun deletedKeys(): MutableSet<String> =
        HashSet(sp.getStringSet(K_DELETED, emptySet()) ?: emptySet<String>())

    fun deleteKey(key: String) {
        val set = deletedKeys()
        set.add(key)
        sp.edit().putStringSet(K_DELETED, set).apply()
    }

    fun restoreKey(key: String) {
        val set = deletedKeys()
        set.remove(key)
        sp.edit().putStringSet(K_DELETED, set).apply()
    }

    fun clearDeleted() {
        sp.edit().remove(K_DELETED).apply()
    }

    /** 每条任务上一次看到的状态与进入该状态的时刻：key -> (状态名, 时刻) */
    fun states(): MutableMap<String, Pair<String, Long>> {
        val map = HashMap<String, Pair<String, Long>>()
        val raw = s(K_STATE)
        if (raw.isNotEmpty()) {
            try {
                val o = JSONObject(raw)
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = o.optString(k)
                    val i = v.lastIndexOf('|')
                    if (i > 0) {
                        map[k] = v.substring(0, i) to (v.substring(i + 1).toLongOrNull() ?: 0L)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return map
    }

    fun saveStates(map: Map<String, Pair<String, Long>>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v.first + "|" + v.second)
        putS(K_STATE, o.toString())
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }

    companion object {
        private const val K_COOKIE = "cookie"
        private const val K_UID = "uid"
        private const val K_PUID = "puid"
        private const val K_NAME = "name"
        private const val K_X = "x_days"
        private const val K_Y = "y_days"
        private const val K_SORT = "sort"
        private const val K_SYNC = "last_sync"
        private const val K_CACHE = "cache"
        private const val K_DELETED = "deleted"
        private const val K_STATE = "states"
        private const val K_UNIQ = "unique_id"
        private const val K_LOGGED_OUT = "logged_out"

        @Volatile
        private var instance: Prefs? = null

        fun get(ctx: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(ctx).also { instance = it }
            }
    }
}

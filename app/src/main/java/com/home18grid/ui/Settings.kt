package com.home18grid.ui

import android.content.Context
import android.content.SharedPreferences
import com.home18grid.PrefsKeys

/**
 * 模块 App 侧的设置读写。
 *
 * 必须用 [Context.MODE_WORLD_READABLE] 打开：LSPosed 拦截该 flag，
 * 把 prefs 落到宿主进程可读的位置，`xposedsharedprefs=true` 才生效。
 * 在非 LSPosed 环境（例如直接装 APK 当普通应用打开）下，
 * MODE_WORLD_READABLE 会触发 SecurityException，此时退回私有模式，
 * 界面仍可用，只是开关不会被宿主读到。
 */
object Settings {

    private fun prefs(context: Context): SharedPreferences =
        runCatching {
            @Suppress("DEPRECATION")
            context.getSharedPreferences(PrefsKeys.FILE, Context.MODE_WORLD_READABLE)
        }.getOrElse {
            context.getSharedPreferences(PrefsKeys.FILE, Context.MODE_PRIVATE)
        }

    /** LSPosed 环境判定：世界可读模式能开成功即认为 prefs 可被宿主读到 */
    fun isPrefsShared(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(PrefsKeys.FILE, Context.MODE_WORLD_READABLE)
        true
    }.getOrDefault(false)

    fun getBool(context: Context, key: String, def: Boolean = PrefsKeys.DEFAULT_ENABLED): Boolean =
        runCatching { prefs(context).getBoolean(key, def) }.getOrDefault(def)

    fun setBool(context: Context, key: String, value: Boolean) {
        // 用 commit() 而不是 apply()：apply 是异步落盘，用户改完开关立刻切到桌面时
        // 文件可能还没写完，宿主侧 XSharedPreferences.reload() 就读不到新值。
        // 开关是低频操作、文件只有几个 boolean，同步写的开销可以忽略。
        runCatching { prefs(context).edit().putBoolean(key, value).commit() }
    }
}
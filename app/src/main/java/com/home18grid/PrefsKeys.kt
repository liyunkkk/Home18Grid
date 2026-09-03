package com.home18grid

/**
 * 模块设置的存储契约。
 *
 * 模块 UI 进程用 [android.content.SharedPreferences] 写，
 * 宿主进程用 [de.robv.android.xposed.XSharedPreferences] 读，
 * 两侧共用本文件里的 key，避免字面量漂移。
 *
 * 约束：本文件不得引用 Xposed API 或 Android framework 之外的任何类型，
 * 保证在模块 App 进程与宿主进程里都能安全加载。
 */
object PrefsKeys {

    /**
     * prefs 文件名（不含 .xml）。
     *
     * 需要在 AndroidManifest 里声明 `xposedsharedprefs=true`，
     * LSPosed 才会把它放到模块可被宿主读取的位置。
     */
    const val FILE = "hyperfree_settings"

    /** 功能开关默认值：全部开启，与未引入开关前的行为保持一致 */
    const val DEFAULT_ENABLED = true
}

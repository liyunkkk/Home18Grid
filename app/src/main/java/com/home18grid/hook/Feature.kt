package com.home18grid.hook

import com.home18grid.PrefsKeys
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

/**
 * 运行期功能开关（宿主进程侧只读）。
 *
 * 读取模块 App 写入的 SharedPreferences（LSPosed 的 `xposedsharedprefs` 机制），
 * 未安装设置 / 文件不可读 / 解析失败时一律回退到 [PrefsKeys.DEFAULT_ENABLED]，
 * 保证「开关读不到」永远不会让已有功能凭空消失。
 *
 * ## 开关的作用边界（重要）
 *
 * 开关只裁剪 **FolderSheet 尺寸菜单里的选项注入**，不裁剪数据层与渲染层：
 *
 * - [DataHook] / [LayoutHook] 始终覆盖 [Const.SPECS] 全表。
 *   这两层负责让「DB 里已落库的自定义 itemType」能被正确查询、度量与渲染。
 *   一旦按开关裁剪它们，用户关掉某规格后，已经设成该规格的文件夹会连同
 *   里面的应用一起从桌面消失（SQL 过滤掉了），甚至在渲染层缺失时
 *   check-cast FolderIcon2x2 直接打崩桌面进程。
 *
 * - [SheetHook] 的选项注入按开关裁剪：关掉某规格后，长按文件夹的尺寸面板
 *   不再出现该选项，用户无法再把文件夹切换成这个规格。
 *
 * 也就是说：开关 = 「这个规格是否可用」，而不是「已有文件夹是否被强行还原」。
 * 已经设成该规格的文件夹保持原样，需要还原请在尺寸面板里改回其它规格。
 */
object Feature {

    @Volatile
    private var prefs: XSharedPreferences? = null

    fun install() {
        prefs = runCatching {
            XSharedPreferences(Const.MODULE_PKG, PrefsKeys.FILE)
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] prefs unavailable, all features default ON: $it")
        }.getOrNull()

        val file = prefs?.file
        if (file != null && !file.canRead()) {
            // 用户还没打开过模块设置时文件不存在，属正常情况，仅记录不报错
            XposedBridge.log("[${Const.TAG}] prefs not readable yet: ${file.absolutePath}")
        }
        XposedBridge.log("[${Const.TAG}] enabled specs: " + enabledSpecs().joinToString { it.uiTitle })
    }

    /** 某规格当前是否允许在尺寸面板里被选用 */
    fun isEnabled(spec: Const.GridSpec): Boolean {
        val p = prefs ?: return PrefsKeys.DEFAULT_ENABLED
        return runCatching {
            // reload() 内部先比对文件 mtime，未变更时不重复解析；
            // 只在面板打开这类低频路径调用，改开关后无需重启桌面即可生效。
            p.reload()
            p.getBoolean(spec.prefKey, PrefsKeys.DEFAULT_ENABLED)
        }.getOrDefault(PrefsKeys.DEFAULT_ENABLED)
    }

    fun isEnabled(itemType: Int): Boolean {
        val spec = Const.specOf(itemType) ?: return false
        return isEnabled(spec)
    }

    /** 当前开启的规格，顺序与 [Const.SPECS] 声明顺序一致 */
    fun enabledSpecs(): List<Const.GridSpec> = Const.SPECS.values.filter { isEnabled(it) }
}

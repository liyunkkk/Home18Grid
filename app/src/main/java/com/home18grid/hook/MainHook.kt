package com.home18grid.hook

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。
 *
 * 只在宿主 com.miui.home 中生效，按三层依次装载 Hook：
 *   1. DataHook   itemType 语义层 + DB 持久化（重启不丢），覆盖三种自定义类型
 *   2. LayoutHook 渲染层 + 独立动画载体（18 宫格 6x3 / 横三格 3x1 / 竖三格 1x3）
 *   3. SheetHook  FolderSheet 菜单 UI 注入（三个自定义尺寸选项）
 *   4. 各层独立 try/catch，任一层失败不影响其余层，也不会打崩桌面进程
 *
 * HyperOShape (com.xzakota.oshape) 替代说明：
 *   三宫格 itemType (0x20013/0x20031) 取值与 HyperOShape 一致，
 *   已有文件夹的 DB 数据无需迁移即可被本模块直接接管，可安全卸载它。
 *   注意：不要与 HyperOShape 同时启用，双方都会 hook 同一批方法，
 *   同时生效时行为不可预期。
 */
class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != Const.HOST) return

        XposedBridge.log("[${Const.TAG}] injected into ${Const.HOST}")

        install("DataHook") { DataHook.install(lpparam.classLoader) }
        install("LayoutHook") { LayoutHook.install(lpparam.classLoader) }
        install("SheetHook") { SheetHook.install(lpparam.classLoader) }
    }

    private inline fun install(name: String, block: () -> Unit) {
        try {
            block()
            XposedBridge.log("[${Const.TAG}] $name installed")
        } catch (t: Throwable) {
            XposedBridge.log("[${Const.TAG}] $name FAILED: ${Log.getStackTraceString(t)}")
        }
    }
}
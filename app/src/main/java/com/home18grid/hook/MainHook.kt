package com.home18grid.hook

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 模块入口。
 *
 * 只在宿主 com.miui.home 中生效，按四层依次装载 Hook：
 *   1. DataHook   itemType 语义层 + DB 持久化（重启不丢）
 *   2. LayoutHook 6x3 渲染层（图标尺寸与定位）
 *   3. SheetHook  FolderSheet 菜单 UI 注入（"18 宫格"选项）
 *   4. 各层独立 try/catch，任一层失败不影响其余层，也不会打崩桌面进程
 *
 * 与 HyperOShape (com.xzakota.oshape) 共存说明：
 * 双方都用 after hook 且各自只处理自己的 itemType
 * (HyperOShape: 0x20013/0x20031，本模块: 0x20018)，
 * 菜单里的三宫格与 18 宫格选项会同时出现，互不干扰。
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
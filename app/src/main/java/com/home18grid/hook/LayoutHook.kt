package com.home18grid.hook

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 渲染层 Hook：把 itemType = 0x20018 的文件夹画成 6x3 的 18 格。
 *
 * 三条链路：
 *
 * 1. FolderIcon.fromXml(ILauncher, ViewGroup, FolderInfo, boolean, IFolder)
 *    宿主原实现只按 0x16 / 0x15 选大文件夹布局，0x20018 会 fallback 到
 *    folder_icon_1x1，导致 18 宫格文件夹显示成普通小图标。
 *    改成让它走 folder_icon_2x2_9（res/q8h.xml），从而复用宿主已经调好的
 *    FolderIcon2x2_9 + LauncherFolder2x2IconContainer + 预览容器整套层级。
 *
 * 2. FolderIcon2x2_9 的构造里写死 mLargeIconNum=8 / mItemsMaxCount=12 /
 *    mIconColumCount=3（三个 protected setter 在父类 FolderIcon2x2 上）。
 *    在 FolderIcon2x2.setup 之后按实际 itemType 改成 18 / 18 / 6。
 *    mLargeIconNum = 18 是"全部 18 个图标都能直接点击启动"的开关：
 *    onMeasureChild2x2 里 index < mLargeIconNum 的子 View 才会被
 *    setIconViewType(BIGICONVIEW)。
 *
 * 3. FolderIconPreviewContainer2X2_9 的三个布局方法
 *    (preMeasure2x2 / preSetup2x2 / getSmallItemsRectF)
 *    整体交给 FolderPreviewContainer6X3 的等分算法接管。
 */
object LayoutHook {

    fun install(cl: ClassLoader) {
        hookFromXml(cl)
        hookFolderIconSizes(cl)
        hookPreviewContainer(cl)
    }

    // ------------------------------------------------------------------
    // 1. 布局选择
    // ------------------------------------------------------------------

    private fun hookFromXml(cl: ClassLoader) {
        val folderIcon = XposedHelpers.findClass(Const.CLS_FOLDER_ICON, cl)

        // 5 参、第 4 个参数是 boolean 的重载做布局判定
        val decider = folderIcon.declaredMethods.firstOrNull { m ->
            m.name == "fromXml" &&
                m.parameterTypes.size == 5 &&
                m.parameterTypes[3] == Boolean::class.javaPrimitiveType
        } ?: run {
            XposedBridge.log("[${Const.TAG}] FolderIcon.fromXml(...,boolean,...) not found")
            return
        }

        // 5 参、第 1 个参数是 int(layoutId) 的重载才真正 inflate
        val inflater = folderIcon.declaredMethods.firstOrNull { m ->
            m.name == "fromXml" &&
                m.parameterTypes.size == 5 &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: run {
            XposedBridge.log("[${Const.TAG}] FolderIcon.fromXml(int,...) not found")
            return
        }
        inflater.isAccessible = true

        XposedBridge.hookMethod(decider, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val info = param.args[2] ?: return
                if (DataHook.itemTypeOf(info) != Const.FOLDER_18_GRID) return

                val container = param.args[1] as? ViewGroup ?: return
                val layoutId =
                    HostRes.layout(container.context, Const.RES_LAYOUT_FOLDER_ICON_2X2_9)
                if (layoutId == 0) {
                    XposedBridge.log(
                        "[${Const.TAG}] layout ${Const.RES_LAYOUT_FOLDER_ICON_2X2_9} missing," +
                            " fall back to host behaviour"
                    )
                    return
                }

                // 直接替换返回值，跳过宿主原本的 if-else 布局判定
                param.result = inflater.invoke(
                    null, layoutId, param.args[0], param.args[1], param.args[2], param.args[4]
                )
            }
        })
    }

    // ------------------------------------------------------------------
    // 2. 图标数量与列数
    // ------------------------------------------------------------------

    /**
     * 构造阶段 mInfo 还没绑定，无法判断 itemType，所以改在 setup 之后：
     * FolderIcon2x2.setup(IFolderInfo, IFolder) 时 FolderIcon.mInfo 已由
     * fromXml(int,...) 写入（iput-object p3, v0, FolderIcon->mInfo）。
     */
    private fun hookFolderIconSizes(cl: ClassLoader) {
        val icon2x2 = XposedHelpers.findClass(Const.CLS_FOLDER_ICON_2X2, cl)

        val setup = icon2x2.declaredMethods.firstOrNull {
            it.name == "setup" && it.parameterTypes.size == 2
        } ?: run {
            XposedBridge.log("[${Const.TAG}] FolderIcon2x2.setup not found")
            return
        }

        XposedBridge.hookMethod(setup, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                applyIfMatched(param.thisObject as? View ?: return)
            }
        })

        // 增删应用时宿主会重建预览子 View，重新确认一次尺寸参数
        runCatching {
            XposedHelpers.findAndHookMethod(
                icon2x2, "createOrRemoveView",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        applyIfMatched(param.thisObject as? View ?: return)
                    }
                }
            )
        }
    }

    private fun applyIfMatched(icon: View) {
        val info = runCatching {
            XposedHelpers.getObjectField(icon, "mInfo")
        }.getOrNull() ?: return
        if (DataHook.itemTypeOf(info) != Const.FOLDER_18_GRID) return

        // 三个 setter 都是 protected final，用 callMethod 反射调用
        runCatching {
            XposedHelpers.callMethod(icon, "setMLargeIconNum", Const.GRID_COUNT)
            XposedHelpers.callMethod(icon, "setMItemsMaxCount", Const.GRID_COUNT)
            XposedHelpers.callMethod(icon, "setMIconColumCount", Const.GRID_COLUMNS)
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] FolderIcon2x2 size setters failed: $it")
        }

        // mPreviewContainer 是 public 字段，setup 里由 findViewById 赋值
        val container = runCatching {
            XposedHelpers.getObjectField(icon, "mPreviewContainer") as? View
        }.getOrNull() ?: return
        applyContainerSize(container)
    }

    /**
     * 给预览容器装上 6x3 算法并同步数量上限。
     * mLargeIconNum / mItemsMaxCount 的 setter 在
     * BaseFolderIconPreviewContainer2X2 上（protected final）。
     */
    fun applyContainerSize(container: View) {
        runCatching {
            XposedHelpers.callMethod(container, "setMLargeIconNum", Const.GRID_COUNT)
            XposedHelpers.callMethod(container, "setMItemsMaxCount", Const.GRID_COUNT)
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] preview container size setters failed: $it")
        }

        if (XposedHelpers.getAdditionalInstanceField(container, Const.KEY_HELPER) == null) {
            XposedHelpers.setAdditionalInstanceField(
                container, Const.KEY_HELPER, FolderPreviewContainer6X3(container)
            )
        }
    }

    private fun helperOf(view: View): FolderPreviewContainer6X3? =
        XposedHelpers.getAdditionalInstanceField(view, Const.KEY_HELPER)
            as? FolderPreviewContainer6X3

    // ------------------------------------------------------------------
    // 3. 预览容器布局接管
    // ------------------------------------------------------------------
    /**
     * 只有挂了 helper 的实例才被接管（helper 由 applyContainerSize 挂载），
     * 宿主原生 0x15 / 0x16 的容器拿不到 helper，会原样走自己的实现。
     * 这是「与 HyperOShape 等其他模块共存、互不干扰」的基础。
     *
     * 桌面上的容器由 applyIfMatched() 挂 helper（走 mInfo.itemType 判定），
     * FolderSheet 里手 new 的那个由 SheetHook.injectPreview() 直接挂，
     * 因此这里不需要 hook 构造函数（三参构造的第 3 个参数是 defStyleAttr，
     * 不是 itemType，不能用来判定类型）。
     */
    private fun hookPreviewContainer(cl: ClassLoader) {
        val containerClass = XposedHelpers.findClass(Const.CLS_PREVIEW_CONTAINER_2X2_9, cl)


        XposedHelpers.findAndHookMethod(
            containerClass, "preMeasure2x2",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val helper = helperOf(param.thisObject as View) ?: return
                    helper.onPreMeasure(param.args[0] as Int, param.args[1] as Int)
                    param.result = null
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            containerClass, "preSetup2x2",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val helper = helperOf(param.thisObject as View) ?: return
                    helper.onPreSetup(cl)
                    param.result = null
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            containerClass, "getSmallItemsRectF",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val helper = helperOf(param.thisObject as View) ?: return
                    param.result = helper.getSmallItemsRectF()
                }
            }
        )
    }
}
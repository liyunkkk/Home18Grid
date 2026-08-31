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
        hookIconContainerSpan(cl)
        hookPreviewContainer(cl)
        hookClingLayout(cl)
        hookAnimIconLoc(cl)
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

        /**
         * createOrRemoveView2 才是 loadItemIcons / onIconRemoved / rebindInfo
         * 实际走的那条路，而它开头会按 `this is FolderIcon2x2_9` 把
         * mItemsMaxCount / mLargeIconNum / mLargeIconNum2 重新写回 12/8/8
         * （ENH 补丁版还会看 SmaliDedicatedSettingManager4.mRealLargeFolder）。
         *
         * 只在之后补写数量是没用的：同一个方法里紧接着就用 getItemsMaxCount()
         * 算出该建几个子 View，并调 addItemOnclickListener 按 mLargeIconNum2
         * 决定哪些图标可直接点击。所以这里整段接管：
         *   先按 18 宫格写好数量，再调 createOrRemoveView()——
         *   它是 createOrRemoveView2 去掉「重置数量」那一段后剩下的同样逻辑
         *   （diff 子 View 数量 + addItemOnclickListener），不碰任何计数字段。
         */
        runCatching {
            XposedHelpers.findAndHookMethod(
                icon2x2, "createOrRemoveView2",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val icon = param.thisObject as? View ?: return
                        val info = runCatching {
                            XposedHelpers.getObjectField(icon, "mInfo")
                        }.getOrNull() ?: return
                        if (DataHook.itemTypeOf(info) != Const.FOLDER_18_GRID) return

                        param.result = null
                        applyIfMatched(icon)
                        runCatching { XposedHelpers.callMethod(icon, "createOrRemoveView") }
                            .onFailure {
                                XposedBridge.log("[${Const.TAG}] createOrRemoveView failed: $it")
                            }
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] createOrRemoveView2 hook failed: $it")
        }
    }

    private fun applyIfMatched(icon: View) {
        val info = runCatching {
            XposedHelpers.getObjectField(icon, "mInfo")
        }.getOrNull() ?: return
        if (DataHook.itemTypeOf(info) != Const.FOLDER_18_GRID) return

        // 三个 setter 都是 protected final，用 callMethod 反射调用
        runCatching {
            XposedHelpers.callMethod(icon, "setMLargeIconNum", Const.LARGE_COUNT)
            XposedHelpers.callMethod(icon, "setMLargeIconNum2", Const.LARGE_COUNT)
            XposedHelpers.callMethod(icon, "setMItemsMaxCount", Const.MAX_COUNT)
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
        if (XposedHelpers.getAdditionalInstanceField(container, Const.KEY_HELPER) == null) {
            XposedHelpers.setAdditionalInstanceField(
                container, Const.KEY_HELPER, FolderPreviewContainer6X3(container)
            )
        }

        runCatching {
            XposedHelpers.callMethod(container, "setMLargeIconNum", Const.LARGE_COUNT)
            XposedHelpers.callMethod(container, "setMItemsMaxCount", Const.MAX_COUNT)
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] preview container size setters failed: $it")
        }
    }

    private fun helperOf(view: View): FolderPreviewContainer6X3? =
        XposedHelpers.getAdditionalInstanceField(view, Const.KEY_HELPER)
            as? FolderPreviewContainer6X3

    // ------------------------------------------------------------------
    // 3. 预览区物理尺寸（方块 → 整行长条）
    // ------------------------------------------------------------------

    /**
     * LauncherFolder2x2IconContainer 是 folder_icon_2x2_9 里包住预览容器的那层
     * FrameLayout，构造函数里把 cellX / cellY 写死成 2 / 2：
     *
     *   onMeasure(w, h):
     *     spec = DeviceConfigs.getMiuiWidgetSizeSpec(cellX, cellY, true)
     *     width  = spec >> 32   // 由 cellX 算出
     *     height = (int) spec   // 由 cellY 算出
     *
     * 也就是说不管上层 spanX/spanY 给了多少，这一层永远按 2x2 量出一个正方形，
     * 6 列图标塞进 2 格宽 → 每个图标只有 1/3 格宽，就是「方块里一堆小点」。
     *
     * 这里在 onMeasure 之前把 cellX 改成桌面列数、cellY 改成 2，
     * 预览区才会真正撑成「整行宽 × 2 格高」，18 个图标接近桌面原生图标大小。
     *
     * 只改属于 0x20018 的那些实例：沿 parent 链找到 FolderIcon，读 mInfo.itemType 判定。
     * 宿主原生 2x2_4 / 2x2_9 的容器不受影响。
     */
    private fun hookIconContainerSpan(cl: ClassLoader) {
        val containerClass = XposedHelpers.findClass(Const.CLS_ICON_CONTAINER_2X2, cl)
        val deviceConfigs = XposedHelpers.findClass(Const.CLS_DEVICE_CONFIGS, cl)

        XposedHelpers.findAndHookMethod(
            containerClass, "onMeasure",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val container = param.thisObject as? View ?: return
                    if (!belongsTo18Grid(container)) return

                    runCatching {
                        XposedHelpers.setIntField(
                            container, Const.F_CELL_X, DataHook.cellCountX(deviceConfigs)
                        )
                        XposedHelpers.setIntField(container, Const.F_CELL_Y, Const.SPAN_Y)
                    }.onFailure {
                        XposedBridge.log("[${Const.TAG}] icon container span failed: $it")
                    }
                }
            }
        )
    }
    /** 沿 parent 链向上找宿主 FolderIcon，判断它是否是 18 宫格 */
    private fun belongsTo18Grid(view: View): Boolean {
        var p = view.parent
        var depth = 0
        while (p is View && depth < 4) {
            val info = runCatching { XposedHelpers.getObjectField(p, "mInfo") }.getOrNull()
            if (info != null) return DataHook.itemTypeOf(info) == Const.FOLDER_18_GRID
            p = p.parent
            depth++
        }
        return false
    }

    // ------------------------------------------------------------------
    // 3.5 打开动画的图标类型路由（点击文件夹闪退的根因）
    // ------------------------------------------------------------------

    /**
     * FolderCling 里预置了三个 FolderIcon 占位 View（1x1 / 2x2_4 / 2x2_9），
     * 打开文件夹时 initAnimFolderIcon -> loadAnimFolderIcon 用
     * determineLayoutResource(info) 三选一，findViewById 出来当动画载体：
     *
     *   itemType == 0x15 -> R.id.folder_icon_2x2_4
     *   itemType == 0x16 -> R.id.folder_icon_2x2_9
     *   其他             -> R.id.folder_icon_1x1
     *
     * 0x20018 落到最后一档，动画载体就是 FolderIcon1x1；紧接着
     * setupAnimFolderIcon -> FolderIcon.loadIconPreViews(info) 里按
     * 桌面端 buddyIconView（我们的 FolderIcon2x2_9）走 instanceof FolderIcon2x2
     * 分支，把自身 check-cast 成 FolderIcon2x2，于是：
     *
     *   ClassCastException: FolderIcon1x1 cannot be cast to FolderIcon2x2
     *
     * 这里让 0x20018 也返回 folder_icon_2x2_9 的 view id，动画载体与桌面端
     * 图标类型一致，强转成立。返回 0（资源名找不到）时不改，退回宿主行为。
     */
    private fun hookClingLayout(cl: ClassLoader) {
        val cling = XposedHelpers.findClass(Const.CLS_FOLDER_CLING, cl)
        val folderInfo = XposedHelpers.findClass(Const.CLS_FOLDER_INFO, cl)

        runCatching {
            XposedHelpers.findAndHookMethod(
                cling, "determineLayoutResource", folderInfo,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val info = param.args[0] ?: return
                        if (DataHook.itemTypeOf(info) != Const.FOLDER_18_GRID) return

                        val view = param.thisObject as? View ?: return
                        val id = HostRes.viewId(view.context, Const.RES_ID_FOLDER_ICON_2X2_9)
                        if (id == 0) {
                            XposedBridge.log(
                                "[${Const.TAG}] id ${Const.RES_ID_FOLDER_ICON_2X2_9} missing," +
                                    " folder open would crash"
                            )
                            return
                        }
                        param.result = id
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] determineLayoutResource hook failed: $it")
        }
    }


    // ------------------------------------------------------------------
    // 3.6 展开动画的预览图标映射（第一个图标漂浮在文件夹上方）
    // ------------------------------------------------------------------

    /**
     * FolderAnimController.setupView 里：
     *
     *   p2 = folderIconAnimView.getIconColumCount()          // 我们设成了 6
     *   if (SmaliDedicatedSettingManager4.mFolderColumnNumber != 3) p2 = mFolderColumnNumber
     *   initIconLoc(mFolderColumnNumber, p2, itemType, folderIcon)
     *
     * initIconLoc 只有两条能填 mFolderIconLocMap 的路径：
     *   p2 == p1（列数一致）  -> 填 i->i 的恒等映射
     *   itemType == 0x15     -> 走 2x2_4 的专用映射
     *   其余                 -> 直接 return 0，映射表**保持为空**
     *
     * 宿主 2x2_9 的 mIconColumCount 是 3，而 mFolderColumnNumber 默认也是 3，
     * 或被用户改成 N 时 p2 会被覆盖成同一个 N，所以两者恒等、永远走第一条。
     * 我们把 mIconColumCount 设成 6，于是 6 != 3，itemType 又不是 0x15，
     * 映射表空了。空表的后果在 preFolderIconAnim 里：
     *
     *   for (key in map.keySet()) { ...把预览图标飞到网格位置... }   // 空表，什么都不做
     *   for (i in mLastItemIndex + 1 until previewArray.size)        // mLastItemIndex = 0
     *       addSmallFolderPreViewAnim(...)                           // 从 1 开始
     *
     * 下标 0 既没进第一个循环、也没进第二个循环，从头到尾没人管它的
     * 位置和透明度，于是它就以桌面上的原样停在展开后的文件夹上方 —— 就是
     * 那个「一直漂浮着的第一个图标」。
     *
     * 这里为 0x20018 显式建表，并且对齐宿主 2x2_9 的分工：它把 0..8 共 9 个
     * 下标填进表（含第 9 格里的第一个小图标），返回 8 作为 mLastItemIndex，
     * 剩下的 9..11 交给 addSmallFolderPreViewAnim。照此按 18 格换算，
     * 填 0..17 共 18 个下标、返回 17，余下 18..20 走小图标收尾动画。
     *
     * 应用数少于 18 个时不用额外处理：宿主自己会判
     * key >= min(previewArray.size, desktopImageViews.size) 就跳过，
     * 以及网格子 View 不够时把该预览 alpha 置 0。
     */
    private fun hookAnimIconLoc(cl: ClassLoader) {
        val controller = XposedHelpers.findClass(Const.CLS_FOLDER_ANIM_CONTROLLER, cl)

        val initIconLoc = controller.declaredMethods.firstOrNull {
            it.name == "initIconLoc" && it.parameterTypes.size == 4
        } ?: run {
            XposedBridge.log("[${Const.TAG}] FolderAnimController.initIconLoc not found")
            return
        }

        XposedBridge.hookMethod(initIconLoc, object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.args[2] != Const.FOLDER_18_GRID) return

                val map = runCatching {
                    XposedHelpers.getObjectField(param.thisObject, Const.F_ICON_LOC_MAP)
                        as? MutableMap<Int, Int>
                }.getOrNull() ?: run {
                    XposedBridge.log("[${Const.TAG}] ${Const.F_ICON_LOC_MAP} missing")
                    return
                }

                map.clear()
                for (i in 0 until Const.GRID_COUNT) map[i] = i
                param.result = Const.GRID_COUNT - 1
            }
        })
    }

    // ------------------------------------------------------------------
    // 4. 预览容器布局接管
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
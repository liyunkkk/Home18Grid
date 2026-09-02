package com.home18grid.hook

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 渲染层 Hook：把三种自定义 itemType 的文件夹画成对应的网格，并让它们各自
 * 拥有独立的展开动画载体（独立载体方案，彻底摆脱与宿主九宫格共享单例的污染）。
 *
 * 支持的类型（见 Const.SPECS）：
 *   0x20018  6x3  18 宫格
 *   0x20013  3x1  横向三宫格
 *   0x20031  1x3  纵向三宫格
 *
 * 六条链路：
 *
 * 1. hookClingInflate —— FolderCling.onFinishInflate 之后，为每种 spec 各
 *    inflate 一个 folder_icon_2x2_9 布局实例 addView 进 cling，赋一个运行期
 *    生成的 view id，并把 (itemType -> viewId) 记在 cling 的附加字段上。
 *    这些实例就是各类型专属的动画载体，互不干扰，也不碰宿主原生的三个占位。
 *
 * 2. hookClingLayout —— determineLayoutResource(info) 对自定义类型返回上一步
 *    生成的专属 view id，于是 loadAnimFolderIcon 的 findViewById 取到的是我们
 *    自己的载体，check-cast FolderIcon 成立（布局本身就是 FolderIcon2x2_9）。
 *
 * 3. hookFromXml —— 桌面端 FolderIcon.fromXml 对自定义类型改用 folder_icon_2x2_9
 *    布局，复用宿主 FolderIcon2x2_9 + 预览容器整套层级。
 *
 * 4. hookFolderIconSizes —— 桌面端与动画载体的 FolderIcon2x2 在 setup /
 *    createOrRemoveView(2) 时按 spec 写 mLargeIconNum / mItemsMaxCount，
 *    并按 spec 设 mIconColumCount（独立载体后可自由设，不再污染九宫格）。
 *
 * 5. hookIconContainerSpan —— LauncherFolder2x2IconContainer.onMeasure 时按
 *    spec 把 cellX / cellY 改成目标占位，预览区撑成正确的长条 / 方块。
 *
 * 6. hookPreviewContainer —— FolderIconPreviewContainer2X2_9 的三个布局方法
 *    交给 GridPreviewContainer 等分算法接管（仅挂了 helper 的实例才接管）。
 *
 * 7. hookAnimIconLoc —— FolderAnimController.initIconLoc 对自定义类型直接按
 *    spec 构造恒等映射表，保证展开 / 收起动画里预览图标飞向正确的网格位置。
 */
object LayoutHook {

    /** cling 上记 (itemType -> 专属动画载体 view id) 的附加字段 */
    private const val KEY_CLING_IDS = "home18grid_cling_ids"

    /** FolderAnimController 里展开后的内容网格（收起动画的飞行目标来源） */
    private const val F_ANIM_GRID_VIEW = "mAnimaFolderGridView"

    fun install(cl: ClassLoader) {
        hookClingInflate(cl)
        hookClingLayout(cl)
        hookFromXml(cl)
        hookFolderIconSizes(cl)
        hookIconContainerSpan(cl)
        hookPreviewContainer(cl)
        hookAnimIconLoc(cl)
    }

    // ==================================================================
    // 1. 独立动画载体：为每种 spec 造一个专属 FolderIcon
    // ==================================================================
    private fun hookClingInflate(cl: ClassLoader) {
        val cling = XposedHelpers.findClass(Const.CLS_FOLDER_CLING, cl)

        XposedHelpers.findAndHookMethod(
            cling, "onFinishInflate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val group = param.thisObject as? ViewGroup ?: return
                    if (XposedHelpers.getAdditionalInstanceField(group, KEY_CLING_IDS) != null) return

                    val layoutId = HostRes.layout(group.context, Const.RES_LAYOUT_FOLDER_ICON_2X2_9)
                    if (layoutId == 0) {
                        XposedBridge.log("[${Const.TAG}] cling inflate: layout missing")
                        return
                    }

                    val inflater = group.context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

                    val ids = HashMap<Int, Int>()
                    for (spec in Const.SPECS.values) {
                        runCatching {
                            // 不能用 FolderIcon.fromXml(int,...)：它内部立即读取 FolderInfo
                            // 字段绑数据，载体 inflate 时还没有 info，必然 NPE。
                            // 宿主自己的三个预置载体也是布局 XML 里由 LayoutInflater
                            // 创建的（走 (Context, AttributeSet) 构造，构造内完成
                            // findViewById 绑定），这里走同一条路径。
                            // root=group 只用于生成 LayoutParams，不 attach。
                            val icon = inflater.inflate(layoutId, group, false) as View
                            val viewId = View.generateViewId()
                            icon.id = viewId
                            icon.visibility = View.GONE
                            XposedHelpers.setAdditionalInstanceField(
                                icon, Const.KEY_CLING_SPEC, spec
                            )
                            group.addView(icon)
                            ids[spec.itemType] = viewId
                        }.onFailure {
                            XposedBridge.log(
                                "[${Const.TAG}] cling carrier inflate failed for ${spec.itemType}: $it"
                            )
                        }
                    }
                    XposedHelpers.setAdditionalInstanceField(group, KEY_CLING_IDS, ids)
                }
            }
        )
    }

    /** FolderIcon.fromXml(int layoutId, ILauncher, ViewGroup, FolderInfo, IFolder) —— 真正 inflate 的重载 */
    private fun inflaterOf(folderIcon: Class<*>): java.lang.reflect.Method? {
        val m = folderIcon.declaredMethods.firstOrNull {
            it.name == "fromXml" &&
                it.parameterTypes.size == 5 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (m == null) {
            XposedBridge.log("[${Const.TAG}] FolderIcon.fromXml(int,...) not found")
        } else {
            m.isAccessible = true
        }
        return m
    }

    // ==================================================================
    // 2. determineLayoutResource：自定义类型返回专属载体 id
    // ==================================================================
    private fun hookClingLayout(cl: ClassLoader) {
        val cling = XposedHelpers.findClass(Const.CLS_FOLDER_CLING, cl)
        val folderInfo = XposedHelpers.findClass(Const.CLS_FOLDER_INFO, cl)

        runCatching {
            XposedHelpers.findAndHookMethod(
                cling, "determineLayoutResource", folderInfo,
                object : XC_MethodHook() {
                    @Suppress("UNCHECKED_CAST")
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val info = param.args[0] ?: return
                        val itemType = DataHook.itemTypeOf(info)
                        if (!Const.isCustomFolder(itemType)) return

                        val group = param.thisObject as? View ?: return
                        val ids = XposedHelpers.getAdditionalInstanceField(group, KEY_CLING_IDS)
                            as? Map<Int, Int> ?: return
                        val viewId = ids[itemType] ?: return
                        param.result = viewId
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] determineLayoutResource hook failed: $it")
        }
    }

    // ==================================================================
    // 3. 桌面端布局选择：自定义类型走 folder_icon_2x2_9
    // ==================================================================
    private fun hookFromXml(cl: ClassLoader) {
        val folderIcon = XposedHelpers.findClass(Const.CLS_FOLDER_ICON, cl)

        val decider = folderIcon.declaredMethods.firstOrNull { m ->
            m.name == "fromXml" &&
                m.parameterTypes.size == 5 &&
                m.parameterTypes[3] == Boolean::class.javaPrimitiveType
        } ?: run {
            XposedBridge.log("[${Const.TAG}] FolderIcon.fromXml(...,boolean,...) not found")
            return
        }
        val inflater = inflaterOf(folderIcon) ?: return

        XposedBridge.hookMethod(decider, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val info = param.args[2] ?: return
                if (!Const.isCustomFolder(DataHook.itemTypeOf(info))) return

                val container = param.args[1] as? ViewGroup ?: return
                val layoutId =
                    HostRes.layout(container.context, Const.RES_LAYOUT_FOLDER_ICON_2X2_9)
                if (layoutId == 0) {
                    XposedBridge.log("[${Const.TAG}] fromXml: layout missing, fall back")
                    return
                }
                // decider 是 (ILauncher, ViewGroup, FolderInfo, boolean, IFolder)
                // inflater 是 (int, ILauncher, ViewGroup, FolderInfo, IFolder)
                param.result = inflater.invoke(
                    null, layoutId, param.args[0], param.args[1], param.args[2], param.args[4]
                )
            }
        })
    }

    // ==================================================================
    // 4. 图标数量与列数（桌面端 + 独立动画载体）
    // ==================================================================
    private fun hookFolderIconSizes(cl: ClassLoader) {
        val icon2x2 = XposedHelpers.findClass(Const.CLS_FOLDER_ICON_2X2, cl)

        // setup(IFolderInfo, IFolder)：桌面端此时 mInfo 已绑定；
        // 独立动画载体走 configureAnimFolderIcon 的 setup(null, manager)，mInfo=null，
        // 此时靠载体上挂的 KEY_CLING_SPEC 判定类型。
        val setup = icon2x2.declaredMethods.firstOrNull {
            it.name == "setup" && it.parameterTypes.size == 2
        }
        if (setup != null) {
            XposedBridge.hookMethod(setup, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyIfMatched(param.thisObject as? View ?: return)
                }
            })
        } else {
            XposedBridge.log("[${Const.TAG}] FolderIcon2x2.setup not found")
        }

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

        // createOrRemoveView2 会按 `this is FolderIcon2x2_9` 把数量重置回 12/8/8，
        // 所以整段接管：先按 spec 写好数量，再调 createOrRemoveView() 走剩余逻辑。
        runCatching {
            XposedHelpers.findAndHookMethod(
                icon2x2, "createOrRemoveView2",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val icon = param.thisObject as? View ?: return
                        val spec = specOfIcon(icon) ?: return
                        applyIfMatched(icon)
                        param.result = null
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

    /**
     * 判定一个 FolderIcon 属于哪个 spec：
     *   桌面端图标  -> mInfo.itemType
     *   独立动画载体 -> 载体上挂的 KEY_CLING_SPEC
     */
    private fun specOfIcon(icon: View): Const.GridSpec? {
        val bySpec = XposedHelpers.getAdditionalInstanceField(icon, Const.KEY_CLING_SPEC)
            as? Const.GridSpec
        if (bySpec != null) return bySpec
        val info = runCatching { XposedHelpers.getObjectField(icon, "mInfo") }.getOrNull()
            ?: return null
        return Const.specOf(DataHook.itemTypeOf(info))
    }

    private fun applyIfMatched(icon: View) {
        val spec = specOfIcon(icon) ?: return

        val container = runCatching {
            XposedHelpers.getObjectField(icon, "mPreviewContainer") as? View
        }.getOrNull()

        // 独立载体后可放心设 mIconColumCount：每种类型有自己的载体，不再和九宫格共享。
        runCatching {
            XposedHelpers.callMethod(icon, "setMLargeIconNum", spec.largeCount)
            XposedHelpers.callMethod(icon, "setMLargeIconNum2", spec.largeCount)
            XposedHelpers.callMethod(icon, "setMItemsMaxCount", spec.maxCount)
            XposedHelpers.callMethod(icon, "setMIconColumCount", spec.columns)
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] FolderIcon2x2 size setters failed: $it")
        }

        container?.let { applyContainerSize(it, spec) }
    }

    /** 给预览容器装上对应 spec 的算法辅助类并同步数量上限 */
    fun applyContainerSize(container: View, spec: Const.GridSpec) {
        val existing = helperOf(container)
        if (existing == null || existing.specItemType != spec.itemType) {
            XposedHelpers.setAdditionalInstanceField(
                container, Const.KEY_HELPER, GridPreviewContainer(container, spec)
            )
        }
        runCatching {
            XposedHelpers.callMethod(container, "setMLargeIconNum", spec.largeCount)
            XposedHelpers.callMethod(container, "setMItemsMaxCount", spec.maxCount)
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] preview container size setters failed: $it")
        }
    }

    private fun helperOf(view: View): GridPreviewContainer? =
        XposedHelpers.getAdditionalInstanceField(view, Const.KEY_HELPER)
            as? GridPreviewContainer

    // ==================================================================
    // 5. 预览区物理尺寸（方块 -> 目标占位形状）
    // ==================================================================

    /**
     * LauncherFolder2x2IconContainer 是 folder_icon_2x2_9 里包住预览容器的那层
     * FrameLayout，构造函数里把 cellX / cellY 写死成 2 / 2：
     *
     *   onMeasure(w, h):
     *     spec = DeviceConfigs.getMiuiWidgetSizeSpec(cellX, cellY, true)
     *     width  = spec >> 32   // 由 cellX 算出
     *     height = (int) spec   // 由 cellY 算出
     *
     * 不管上层 spanX/spanY 给了多少，这一层永远按 2x2 量出一个正方形。
     * 各类型的真实形状要在这里写入：
     *   18 宫格 6x3：cellX = 桌面列数（占满整行），cellY = 2
     *   横三宫格 3x1：cellX = 2，cellY = 1（横向长条）
     *   纵三宫格 1x3：cellX = 1，cellY = 2（纵向长条）
     *
     * 只改属于自定义类型的实例：沿 parent 链找到 FolderIcon 读 mInfo.itemType；
     * 动画载体也走这条路（载体的 mInfo 在 loadAnimFolderIcon 时绑定）。
     * 宿主原生 2x2_4 / 2x2_9 的容器 spec 为 null，不受影响。
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
                    val spec = specOfParentIcon(container) ?: return

                    runCatching {
                        val cellX = if (spec.spanX == -1) {
                            DataHook.cellCountX(deviceConfigs)
                        } else spec.spanX
                        XposedHelpers.setIntField(container, Const.F_CELL_X, cellX)
                        XposedHelpers.setIntField(container, Const.F_CELL_Y, spec.spanY)
                    }.onFailure {
                        XposedBridge.log("[${Const.TAG}] icon container span failed: $it")
                    }
                }
            }
        )
    }

    /** 沿 parent 链向上找宿主 FolderIcon，返回它的 spec（不是自定义类型则 null） */
    private fun specOfParentIcon(view: View): Const.GridSpec? {
        var p: Any? = view
        var depth = 0
        while (p is View && depth < 4) {
            // 动画载体自身挂着 KEY_CLING_SPEC，优先识别
            XposedHelpers.getAdditionalInstanceField(p, Const.KEY_CLING_SPEC)
                ?.let { return it as Const.GridSpec }
            val info = runCatching { XposedHelpers.getObjectField(p, "mInfo") }.getOrNull()
            if (info != null) return Const.specOf(DataHook.itemTypeOf(info))
            p = p.parent
            depth++
        }
        return null
    }

    // ==================================================================
    // 6. 预览容器布局接管
    // ==================================================================

    /**
     * 只有挂了 helper 的实例才被接管（helper 由 applyContainerSize 挂载），
     * 宿主原生 0x15 / 0x16 的容器拿不到 helper，会原样走自己的实现。
     * 这是「与其他模块共存、互不干扰」的基础。
     *
     * 桌面上的容器由 applyIfMatched() 挂 helper（走 mInfo.itemType / KEY_CLING_SPEC
     * 判定），FolderSheet 里手 new 的那个由 SheetHook.injectPreview() 直接挂，
     * 因此这里不需要 hook 构造函数。
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

    // ==================================================================
    // 7. 展开动画的预览图标映射
    // ==================================================================

    /**
     * FolderAnimController.setupView 里：
     *
     *   p2 = folderIconAnimView.getIconColumCount()
     *   if (SmaliDedicatedSettingManager4.mFolderColumnNumber != 3) p2 = mFolderColumnNumber
     *   initIconLoc(mFolderColumnNumber, p2, itemType, folderIcon)
     *
     * initIconLoc 只有两条能填 mFolderIconLocMap 的路径：
     *   p2 == p1（列数一致）  -> 填 i->i 的恒等映射，返回 min(p2*p2-1, 末位)
     *   itemType == 0x15     -> 走 2x2_4 的专用映射
     *   其余                 -> return 0，映射表**保持为空**
     *
     * 独立载体方案下，我们已按 spec 给载体设了 mIconColumCount
     * （6 / 3 / 1），但全局 mFolderColumnNumber 恒为 3：
     *   18 宫格 6 != 3、纵向 1x3 的 1 != 3 -> 仍落进空表分支 -> 漂浮图标
     * 所以这里仍需对自定义类型直接构造映射表。
     *
     * 建表规则：填 0..min(gridCount, 网格真实 childCount)-1、返回该值 -1，
     * 余下的预览槽交给宿主 addSmallFolderPreViewAnim 收尾。这与宿主九宫格
     * 「填 0..8、返回 8，余下 9..11 走收尾动画」的分工完全一致。
     *
     * 为什么必须用 childCount 夹一刀（v1.1.5 定稿，v1.1.4 验证通过）：
     * preFolderIconAnim 对 mappedGridIndex >= mAnimaFolderGridView.getChildCount()
     * 的预览槽直接 setAlpha(0)（smali cond_76）。若 18 宫格恒等映射 0..17 而
     * 网格只铺出 9 个 child，则 9..17 全部命中这条硬切透明分支，收起期间恒为
     * 透明、动画结束才被桌面真图标接管 —— 这就是「第 10 个图标起闪现」。
     *
     * 应用数不足时不用额外处理：宿主自己会判
     * key >= min(previewArray.size, desktopImageViews.size) 就跳过。
     */
    private fun hookAnimIconLoc(cl: ClassLoader) {
        val controller = XposedHelpers.findClass(Const.CLS_FOLDER_ANIM_CONTROLLER, cl)
        // 1. 预览槽补齐。
        //
        // 注意这里**不碰** DISPLAY_COUNT_MAX。该字段语义是「展开后 FolderGridView 里
        // 参与飞行动画的子 View 上限」，宿主取值 getMaxRow() * mFolderColumnNumber，
        // 恰好等于实际铺出来的格子数：
        //   preFolderGridAnim: mDisplayChildCount = min(gridView.childCount, DISPLAY_COUNT_MAX)
        //   hideNotDoAnimIcons: 把 [first + DISPLAY_COUNT_MAX, +mFolderColumnCount)
        //                       这一整行的 item alpha 强制设为 0
        // 宿主原生值下被 hide 的那一行必然在屏幕外，所以看不见。
        //
        // v1.1.3 在这里写值，踩了两个坑（已修，勿再加回）：
        //   a) 取 maxOf(spec.maxCount, spec.gridCount)，三宫格算出 6 比宿主的 9 更小，
        //      hideNotDoAnimIcons 于是把屏内可见的 index 6/7/8 清成透明 —— 这就是
        //      「三宫格从第 7 个图标开始闪现」。
        //   b) 用 setAdditionalInstanceField 记待写值且从不清除。FolderAnimController
        //      是 Folder 持有的长生命周期对象、所有文件夹共用，开过三宫格后残留的 6
        //      会在打开**原生九宫格**时被写回去 —— 「九宫格也从第 7 个起闪现」，
        //      属于我们污染了宿主原生行为。
        //
        // 正解是不动这个字段：映射表已被下面的 min(gridCount, childCount) 限住，
        // 参与飞行的槽位不可能超过网格真实 child 数，宿主原生值本来就够用。
        val setupView = controller.declaredMethods.firstOrNull {
            it.name == "setupView" && it.parameterTypes.size == 2
        }
        if (setupView != null) {
            XposedBridge.hookMethod(setupView, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val desktopIcon = param.args[1] as? View ?: return
                    val spec = specOfIcon(desktopIcon) ?: return
                    // 补齐预览槽：对齐 FolderIcon2x2.createOrRemoveView 的数量逻辑，
                    // 否则 mDesktopImageViews 长度不足，后段图标拿不到目标槽位。
                    runCatching {
                        val container = XposedHelpers.callMethod(desktopIcon, "getMPreviewContainer")
                        if (container != null) {
                            applyContainerSize(container as View, spec)
                            XposedHelpers.callMethod(desktopIcon, "createOrRemoveView")
                        }
                    }.onFailure {
                        XposedBridge.log("[${Const.TAG}] preview slots top-up failed: $it")
                    }
                }

                // 不覆写 afterHookedMethod：v1.1.4 起不再改写 DISPLAY_COUNT_MAX。
                // 映射表已被 initIconLoc 里的 min(gridCount, childCount) 限住，
                // 参与飞行的槽位不可能超过网格真实 child 数，宿主原生值本来就够用。
                // 写这个字段会经由 hideNotDoAnimIcons 把屏内可见图标清成透明，
                // 连带污染原生九宫格 —— 这正是 v1.1.3「第 7 个起闪现」的根因，勿再加回。
            })
        }

        // 2. 自定义类型的飞行映射表：填 0..min(gridCount, childCount)-1
        val initIconLoc = controller.declaredMethods.firstOrNull {
            it.name == "initIconLoc" && it.parameterTypes.size == 4
        } ?: run {
            XposedBridge.log("[${Const.TAG}] FolderAnimController.initIconLoc not found")
            return
        }

        XposedBridge.hookMethod(initIconLoc, object : XC_MethodHook() {
            @Suppress("UNCHECKED_CAST")
            override fun beforeHookedMethod(param: MethodHookParam) {
                val spec = Const.specOf(param.args[2] as? Int ?: return) ?: return

                val map = runCatching {
                    XposedHelpers.getObjectField(param.thisObject, Const.F_ICON_LOC_MAP)
                        as? MutableMap<Int, Int>
                }.getOrNull() ?: run {
                    XposedBridge.log("[${Const.TAG}] ${Const.F_ICON_LOC_MAP} missing")
                    return
                }

                map.clear()
                // 只映射「展开后网格里真实存在 child」的格位。
                // 宿主 preFolderIconAnim 对 mappedGridIndex >= gridView.childCount 的预览槽
                // 直接 setAlpha(0)（smali cond_76）。18 宫格恒等映射 0..17 时 9..17 全部命中，
                // 收起期间恒透明、动画结束才被桌面真图标接管 —— 这就是「第 10 个起闪现」。
                // 把它们排除在映射表外，就会落到宿主 addSmallFolderPreViewAnim 收尾分支
                // （mResetState: alpha/scale=1 + 弹簧，跟着最后一个大图标的位移飞），
                // 与九宫格「填 0..8、返回 8、余下 9..11 走小图标动画」的分工完全一致。
                val childCount = runCatching {
                    (XposedHelpers.getObjectField(param.thisObject, F_ANIM_GRID_VIEW)
                        as? ViewGroup)?.childCount ?: 0
                }.getOrDefault(0)
                val mapped = minOf(spec.gridCount, childCount)
                for (i in 0 until mapped) map[i] = i
                param.result = mapped - 1
            }
        })

        // 3. folderAnimEnd 兜底：收起动画结束后强制预览图标可见。
        // 防个别 View 被宿主 setAlpha(0) 分支或弹簧中断后残留半透明。
        //
        // 这里**不加**任何外挂补间。v1.1.2 曾在 preFolderGridAnim 里对预览容器子 View
        // 叠加 ViewPropertyAnimator alpha/scale，与宿主 Folme 弹簧体系
        // （setGridViewItemFolme -> handleCloseGridItemState 驱动同一批 View）直接冲突：
        // 两套动画引擎同时写同一属性、结束时互相覆盖 —— 这正是「先飞回前 9 个、停顿、
        // 后 9 个突现」的直接原因。正解是让映射表与网格真实 child 数对齐（见上），
        // 该飞的走宿主飞行分支、超出的走宿主小图标收尾分支，无需外挂补间。
        val folderAnimEnd = controller.declaredMethods.firstOrNull {
            it.name == "folderAnimEnd" && it.parameterTypes.size == 1
        }
        if (folderAnimEnd != null) {
            XposedBridge.hookMethod(folderAnimEnd, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val isFolderOpen = param.args[0] as? Boolean ?: true
                    if (isFolderOpen) return // 只在收起动画结束时兜底
                    val desktopIcon = runCatching {
                        XposedHelpers.getObjectField(param.thisObject, "mFolderDesktopIcon") as? View
                    }.getOrNull() ?: return
                    if (specOfIcon(desktopIcon) == null) return
                    val container = runCatching {
                        XposedHelpers.callMethod(desktopIcon, "getMPreviewContainer") as? ViewGroup
                    }.getOrNull() ?: return
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i) ?: continue
                        child.animate().cancel()
                        child.alpha = 1f
                        child.scaleX = 1f
                        child.scaleY = 1f
                        child.visibility = View.VISIBLE
                    }
                }
            })
        }
    }
}

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

    /** FolderAnimController 上记 setupView 阶段算出的目标 DISPLAY_COUNT_MAX */
    private const val K_PENDING_DISPLAY_MAX = "home18grid_pending_display_max"

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
     * 建表规则对齐宿主九宫格的分工：它填 0..8 共 9 个下标、返回 8，
     * 余下 9..11 交给 addSmallFolderPreViewAnim 收尾。按各 spec 换算即
     * 填 0..gridCount-1、返回 gridCount-1，余下走收尾动画：
     *   6x3：填 0..17，返回 17，余下 18..20
     *   3x1：填 0..2，返回 2，余下 3..5
     *   1x3：填 0..2，返回 2，余下 3..5
     *
     * 应用数不足时不用额外处理：宿主自己会判
     * key >= min(previewArray.size, desktopImageViews.size) 就跳过。
     */
    private fun hookAnimIconLoc(cl: ClassLoader) {
        val controller = XposedHelpers.findClass(Const.CLS_FOLDER_ANIM_CONTROLLER, cl)

        // 1. 修复 DISPLAY_COUNT_MAX 默认写死 9 的问题（v1.1.3 修正）：
        // setupView 执行序列：clearAnimList → ... → mDesktopImageViews = 桌面图标.getPreviewArray()
        // → DISPLAY_COUNT_MAX = getMaxRow() * 3 → initIconLoc。
        // 必须在 before 里做两件事：
        //   a) 对桌面图标的预览容器补齐槽位（getItemsMaxCount = 21）并调
        //      createOrRemoveView() 让 mPvChildList 长度 = min(count, 21)，
        //      否则 mDesktopImageViews 拷贝出的数组长度不足，
        //      preFolderGridAnim 中后段图标走 setExposedGridViewItemFolme（直隐）分支。
        //   b) 修 DISPLAY_COUNT_MAX 的来源：宿主 getMaxRow() 实为
        //      ceil(adapterCount/3) 经 mFolderRowNumber 全局设置钳制，3 列布局下
        //      18 个图标 -> row = 6 -> maxRow=6 -> DISPLAY_COUNT_MAX = 18；正确值为
        //      maxOf(spec.maxCount, gridCount) = 21（含末格 4 小图标）。
        //      initIconLoc 恒等映射填 0..17、返回 17，余下 18..20 由
        //      addSmallFolderPreViewAnim 收尾，DISPLAY_COUNT_MAX 只需 >= 需要值。
        val setupView = controller.declaredMethods.firstOrNull {
            it.name == "setupView" && it.parameterTypes.size == 2
        }
        if (setupView != null) {
            XposedBridge.hookMethod(setupView, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val desktopIcon = param.args[1] as? View ?: return
                    val spec = specOfIcon(desktopIcon) ?: return
                    // a) 补齐预览槽：对齐 FolderIcon2x2.createOrRemoveView 的数量逻辑
                    runCatching {
                        val container = XposedHelpers.callMethod(desktopIcon, "getMPreviewContainer")
                        if (container != null) {
                            applyContainerSize(container as View, spec)
                            // createOrRemoveView 依赖 mInfo.count() 与 mItemsMaxCount 比对
                            XposedHelpers.callMethod(desktopIcon, "createOrRemoveView")
                        }
                    }.onFailure {
                        XposedBridge.log("[${Const.TAG}] preview slots top-up failed: $it")
                    }
                    // b) 记下待写入的 DISPLAY_COUNT_MAX（宿主在 after 阶段写默认值，
                    //    所以这里的值要在 after 再覆盖一次）
                    XposedHelpers.setAdditionalInstanceField(
                        param.thisObject, K_PENDING_DISPLAY_MAX, maxOf(spec.maxCount, spec.gridCount)
                    )
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val pending = XposedHelpers.getAdditionalInstanceField(
                        param.thisObject, K_PENDING_DISPLAY_MAX
                    ) as? Int ?: return
                    runCatching {
                        XposedHelpers.setIntField(param.thisObject, Const.F_DISPLAY_COUNT_MAX, pending)
                    }.onFailure {
                        XposedBridge.log("[${Const.TAG}] set DISPLAY_COUNT_MAX failed: $it")
                    }
                }
            })
        }

        // 2. 自定义类型恒等映射表
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
                for (i in 0 until spec.gridCount) map[i] = i
                param.result = spec.gridCount - 1
            }
        })

        // 3. 收起动画（v1.1.3 移除补间冲突）：
        // v1.1.2 在 preFolderGridAnim 里对预览容器子 View 叠加 ViewPropertyAnimator
        // alpha/scale 补间，与宿主 Folme 弹簧动画体系（setGridViewItemFolme ->
        // handleCloseGridItemState 驱动同一批 View）直接冲突：两套动画引擎同时写
        // 同一属性，结束时互相覆盖 —— 这正是「先飞回前 9 个、停顿、后 9 个突现」的
        // 直接原因。正本清源的做法是保证 DISPLAY_COUNT_MAX = 21 + 预览槽补齐
        // （见 setupView hook），让全部 18 个图标都进入宿主原生
        // setGridViewItemFolme 飞行分支，不再需要任何外挂补间。
        // folderAnimEnd 的兜底恢复保留（动画结束后强制可见，防个别 View 残留半透明）。
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

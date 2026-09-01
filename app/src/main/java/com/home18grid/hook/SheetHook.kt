package com.home18grid.hook

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Locale

/**
 * FolderSheet（长按文件夹 → 「文件夹尺寸」面板）UI 注入，三类型泛化版。
 *
 * 在宿主 1x1 / 2x2_4 / 2x2_9 三个选项之外，追加三个自定义选项：
 *   18 格（6x3）、横三格（3x1）、竖三格（1x3），见 Const.SPECS。
 *
 * 宿主面板结构（由 res/NLT.xml 的 aapt xmltree 逐节点核实）：
 *
 *   FolderSheet (extends AbstractFloatingView, implements VisualCheckGroup.OnCheckedChangeListener)
 *   └─ ScrollView
 *      └─ ConstraintLayout                                   ← 预览区
 *         ├─ ImageView  folder_picker_select_wallpaper_bg
 *         ├─ ImageView  folder_picker_select_default_folder_bg
 *         ├─ ImageView  folder_picker_select_big_folder_bg    ← 大文件夹共用背板
 *         ├─ FolderIconPreviewContainer2X2_4
 *         ├─ FolderIconPreviewContainer2X2_9
 *         └─ VisualCheckGroup  (id/visual_check_group)        ← 选项区
 *            └─ VisualCheckBox x3
 *               ├─ BorderLayout
 *               │  └─ FixedAspectRatioLottieAnimView          选中边框/图示
 *               └─ VisualCheckedTextView                      标题
 *
 * 注入策略（三点关键，都建立在已核实的 miuix 实现细节上）：
 *
 * 1. 往 VisualCheckGroup 里 addView 一个 VisualCheckBox 即可，
 *    单选互斥与回调都是白拿的：VisualCheckGroup 构造里装了
 *    PassThroughHierarchyChangeListener，onChildViewAdded 会对
 *    id == -1 的 VisualCheckBox 调 generateViewId() 并挂上
 *    CheckedStateTracker；tracker 再走 setCheckedId(id) →
 *    回调 FolderSheet.onCheckedChanged(group, checkedId)。
 *
 * 2. VisualCheckBox 自己也装了 PassThroughHierarchyChangeListener，
 *    会把实现 VisualCheckItem 接口的子 View 收集进 mVisualCheckItems，
 *    setChecked 时逐个 onChecked(boolean)。BorderLayout 与
 *    VisualCheckedTextView 都实现了该接口，所以按宿主同样的层级搭起来，
 *    选中边框与文字变色的动效就自动生效，不用自己写动画。
 *
 * 3. 预览区是 ConstraintLayout，手写 ConstraintSet 需要引 androidx 依赖
 *    并硬编码一堆 anchor id。改为克隆同级 mFolderPickerSelectBigFolderImg2x2_9
 *    的 LayoutParams：约束关系原样继承，宿主改版后自动跟随。
 *    三个自定义预览叠在同一位置，按当前 spec 切换可见性。
 *
 * 状态全部挂在 sheet 实例的 additional field 上，不用 object 静态变量，
 * 避免多次弹出/多实例时状态串台。
 */
object SheetHook {

    /** sheet 上记 (itemType -> 选项 View) 的附加字段 */
    private const val K_BOXES = "h18_boxes"
    /** sheet 上记 (itemType -> 预览容器 View) 的附加字段 */
    private const val K_PREVIEWS = "h18_previews"

    fun install(cl: ClassLoader) {
        val sheet = XposedHelpers.findClass(Const.CLS_FOLDER_SHEET, cl)

        hookInject(sheet, cl)
        hookGroupCheckedChanged(sheet)
        hookCheckedSync(sheet)
        hookPreviewInit(sheet, cl)
        hookVisibilityReset(sheet)
        hookSizeLabel(sheet)
    }

    // ------------------------------------------------------------------
    // 1. 控件注入
    // ------------------------------------------------------------------

    /**
     * initTitleAndRecommendAppsSwitchView() 是 initListener() 的最后一步，
     * 此时 mVisualCheckGroup / 三个 CheckBox / 两个预览容器都已 findViewById 完成，
     * setOnCheckedChangeListener 也已挂好，是最稳的注入时机。
     */
    private fun hookInject(sheetClass: Class<*>, cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "initTitleAndRecommendAppsSwitchView",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    runCatching { injectIfNeeded(sheet, cl) }.onFailure {
                        XposedBridge.log("[${Const.TAG}] sheet inject failed: $it")
                    }
                }
            }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectIfNeeded(sheet: View, cl: ClassLoader) {
        val group = XposedHelpers.getObjectField(sheet, Const.F_VISUAL_CHECK_GROUP) as? ViewGroup
            ?: run {
                XposedBridge.log("[${Const.TAG}] ${Const.F_VISUAL_CHECK_GROUP} not found")
                return
            }

        // 面板复用时 initListener 会再跑一遍；控件还挂在同一个 group 上就不重复注入
        val existing = boxesOf(sheet)
        if (existing.isNotEmpty() && existing.values.first().parent === group) return

        val context = sheet.context
        HostRes.dumpDiagnostics(context)

        val boxes = HashMap<Int, View>()
        for (spec in Const.SPECS.values) {
            val checkBox = buildCheckBox(context, cl, spec)
            group.addView(checkBox)   // ← id 与 checked 监听由 miuix 自动补齐
            boxes[spec.itemType] = checkBox
        }
        XposedHelpers.setAdditionalInstanceField(sheet, K_BOXES, boxes)

        injectPreviews(sheet, cl)
    }

    /**
     * 预览区为每种 spec 各加一个 FolderIconPreviewContainer2X2_9 实例，
     * 分别挂上对应 GridPreviewContainer 算法，叠在同一位置按类型切换。
     *
     * 注意：不要用三参构造去传 itemType。已由 smali 核实，
     * FolderIconPreviewContainer2X2_9(Context, AttributeSet, int) 的第 3 个参数
     * 一路透传到 ViewGroup(Context, AttributeSet, int)，语义是 defStyleAttr，
     * 传自定义 itemType 会被当成主题属性 ID 去解析。这里用两参构造，
     * 再显式调 LayoutHook.applyContainerSize() 挂上算法辅助类。
     */
    @Suppress("UNCHECKED_CAST")
    private fun injectPreviews(sheet: View, cl: ClassLoader) {
        val anchor =
            XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_IMG_2X2_9) as? View
                ?: run {
                    XposedBridge.log("[${Const.TAG}] ${Const.F_PICKER_BIG_FOLDER_IMG_2X2_9} missing")
                    return
                }
        val parent = anchor.parent as? ViewGroup ?: return

        val previews = HashMap<Int, View>()
        for (spec in Const.SPECS.values) {
            val preview = runCatching {
                XposedHelpers.newInstance(
                    XposedHelpers.findClass(Const.CLS_PREVIEW_CONTAINER_2X2_9, cl),
                    arrayOf<Class<*>>(
                        Context::class.java,
                        android.util.AttributeSet::class.java
                    ),
                    sheet.context, null
                ) as ViewGroup
            }.onFailure {
                XposedBridge.log("[${Const.TAG}] preview container create failed: $it")
            }.getOrNull() ?: continue

            preview.id = View.generateViewId()
            preview.visibility = View.GONE
            preview.clipChildren = false
            preview.clipToPadding = false
            preview.layoutParams = cloneLayoutParams(anchor)
            LayoutHook.applyContainerSize(preview, spec)

            parent.addView(preview, parent.indexOfChild(anchor) + 1)
            previews[spec.itemType] = preview
        }
        XposedHelpers.setAdditionalInstanceField(sheet, K_PREVIEWS, previews)
    }

    // ------------------------------------------------------------------
    // 1.5 选项构建
    // ------------------------------------------------------------------

    /**
     * 复刻宿主 VisualCheckBox 的三层结构。
     *
     * 三个自定义类任一实例化失败时逐级退化为 LinearLayout / ImageView / TextView：
     * 此时少了选中动效，但选项照样出现且能点，不会因为换 ROM 版本整个功能消失。
     */
    private fun buildCheckBox(context: Context, cl: ClassLoader, spec: Const.GridSpec): View {
        val box = newView(context, cl, Const.CLS_VISUAL_CHECK_BOX) ?: LinearLayout(context)
        if (box is LinearLayout) {
            box.orientation = LinearLayout.VERTICAL   // VisualCheckBox 构造里已设，退化路径补上
            box.clipChildren = false
            box.clipToPadding = false
        }
        box.tag = Const.TAG_CHECK_BOX
        box.isFocusable = true
        box.isClickable = true

        val bgWidth = HostRes.dimenPx(context, Const.RES_DIMEN_BG_WIDTH, 0)
        val padding = HostRes.dimenPx(context, Const.RES_DIMEN_BORDER_PADDING, 0)

        // --- 第 2 层：BorderLayout（选中背板 + 描边动画） ---
        val border = newView(context, cl, Const.CLS_BORDER_LAYOUT) ?: LinearLayout(context)
        HostRes.drawable(context, Const.RES_DRAWABLE_CHECKBOX_BG)?.let { bg ->
            // BorderLayout.onChecked(): getBackground()==null 时用 mBackGround 并调 setAlpha
            // 做淡入淡出。所以这里只填字段、不设 view background。
            runCatching { XposedHelpers.setObjectField(border, "mBackGround", bg) }
        }

        val frame = FrameLayout(context).apply { setPadding(padding, padding, padding, padding) }

        // --- 第 3 层：图示（复用宿主九宫格那张 drawable） ---
        // 宿主这里放的是 FixedAspectRatioLottieAnimView，但它的 onMeasure 完全
        // 忽略 heightMeasureSpec（height = width * mAspectRatio），构造里还会
        // 无条件 playAnimation()；我们只需要显示一张静态 drawable，
        // 用普通 ImageView 更可控，也少一条 lottie 依赖链。
        val image = ImageView(context)
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.isDuplicateParentStateEnabled = true
        HostRes.drawable(context, Const.RES_DRAWABLE_BORDER_2X2_9)?.let { image.setImageDrawable(it) }

        val imageSize = if (bgWidth > 0) bgWidth else ViewGroup.LayoutParams.WRAP_CONTENT
        frame.addView(image, FrameLayout.LayoutParams(imageSize, imageSize))

        (border as? ViewGroup)?.addView(
            frame,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        (box as? ViewGroup)?.addView(
            border,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        // --- 标题 ---
        val text = (newView(context, cl, Const.CLS_VISUAL_CHECKED_TEXT_VIEW) as? TextView)
            ?: TextView(context)
        text.text = optionLabel(spec)
        text.gravity = Gravity.CENTER
        text.maxLines = 1

        HostRes.dimenPx(context, Const.RES_DIMEN_TEXT_SIZE, 0).let { size ->
            if (size > 0) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, size.toFloat())
        }
        text.setPadding(0, HostRes.dimenPx(context, Const.RES_DIMEN_TITLE_MARGIN_TOP, 0), 0, 0)

        // VisualCheckedTextView 用 mCheckedColor / mUncheckedColor 两个私有 int 字段
        // 在 onChecked 时做文字颜色过渡；用 null AttributeSet 构造出来的默认值不对，这里按宿主颜色填。
        val checked = HostRes.color(context, Const.RES_COLOR_TEXT_CHECKED, Color.WHITE)
        val unchecked = HostRes.color(context, Const.RES_COLOR_TEXT_UNCHECKED, Color.GRAY)
        runCatching {
            XposedHelpers.setIntField(text, "mCheckedColor", checked)
            XposedHelpers.setIntField(text, "mUncheckedColor", unchecked)
        }
        text.setTextColor(unchecked)

        (box as? ViewGroup)?.addView(
            text,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )

        return box
    }

    /**
     * 选项文案。宿主 folder_picker_big_2x2_9_text 是九宫格的（中文「超大」/英文 XXL），
     * 不能直接借用，否则两个选项同名。
     */
    private fun optionLabel(spec: Const.GridSpec): String =
        if (Locale.getDefault().language == "zh") {
            when (spec.itemType) {
                Const.FOLDER_18_GRID -> "18 格"
                Const.FOLDER_3X1 -> "横三格"
                Const.FOLDER_1X3 -> "竖三格"
                else -> "${spec.columns}x${spec.rows}"
            }
        } else "${spec.columns}x${spec.rows}"

    // ------------------------------------------------------------------
    // 2. 选中回调
    // ------------------------------------------------------------------

    /**
     * 宿主 onCheckedChanged(VisualCheckGroup, int) 逐个比对三个内置 CheckBox 的 id，
     * 我们的 id 落不到任何分支（相当于 no-op），所以在 after 里补分支即可，
     * 完全不影响宿主原有逻辑，也不会和别的模块的 after hook 抢。
     */
    private fun hookGroupCheckedChanged(sheetClass: Class<*>) {
        val method = sheetClass.declaredMethods.firstOrNull { m ->
            m.name == "onCheckedChanged" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: run {
            XposedBridge.log("[${Const.TAG}] onCheckedChanged(group,int) not found")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sheet = param.thisObject as? View ?: return
                val checkedId = param.args[1] as? Int ?: return
                val spec = specOfBox(sheet, checkedId) ?: return
                runCatching { switchToSpec(sheet, spec) }.onFailure {
                    XposedBridge.log("[${Const.TAG}] switchToSpec failed: $it")
                }
            }
        })
    }

    /** 按 view id 反查它是哪个 spec 的选项 */
    private fun specOfBox(sheet: View, viewId: Int): Const.GridSpec? {
        for ((type, box) in boxesOf(sheet)) {
            if (box.id == viewId) return Const.specOf(type)
        }
        return null
    }

    /**
     * 切到自定义类型，对应宿主的 switchToBigFolder2x2_9()：
     *   隐藏另外三种预览 → 显示自己的 → 开启应用推荐 → 记下 mFolderType。
     *
     * mFolderType 是点「确定」时 onClick 读出来传给
     * ConvertSizeController.convertFolderSize(info, mFolderType) 的值，
     * 是整条切换链真正的落地点。
     */
    private fun switchToSpec(sheet: View, spec: Const.GridSpec) {
        callVoid(sheet, "setDefaultFolderGone")
        callVoid(sheet, "setBigFolderGone2x2_4")
        callVoid(sheet, "setBigFolderGone2x2_9")

        // setBigFolderGone2x2_9 把共用背板也隐藏了，这里单独恢复
        (XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_BG) as? View)
            ?.visibility = View.VISIBLE

        // 只显示当前 spec 的预览，其余两个自定义预览也藏起来
        for ((type, preview) in previewsOf(sheet)) {
            preview.visibility = if (type == spec.itemType) View.VISIBLE else View.GONE
        }

        // 18 格容量足够开放「智能推荐应用」；三宫格只有 3 格，关掉才有意义
        runCatching {
            XposedHelpers.callMethod(sheet, "configAppPredict", spec.itemType == Const.FOLDER_18_GRID)
        }

        runCatching { XposedHelpers.setIntField(sheet, Const.F_FOLDER_TYPE, spec.itemType) }

        boxesOf(sheet)[spec.itemType]?.let {
            runCatching { XposedHelpers.callMethod(it, "setChecked", true) }
        }
    }

    /**
     * 宿主 setCheckedBox(int) 开头就 `if (type != 2 && != 21 && != 22) return`，
     * 只会摆弄它认识的三个 CheckBox。这里补一手：切到宿主类型时取消我们的勾选。
     */
    private fun hookCheckedSync(sheetClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "setCheckedBox", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val type = param.args[0] as? Int ?: return
                    if (Const.isCustomFolder(type)) return
                    for (box in boxesOf(sheet).values) {
                        runCatching { XposedHelpers.callMethod(box, "setChecked", false) }
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // 3. 预览图标
    // ------------------------------------------------------------------

    /**
     * initPreviewIcon() 按当前 itemType 决定给哪个容器塞 FolderPreviewIconView
     * 并调 loadItemIcons。自定义类型会落到 else 分支（当普通文件夹处理），
     * 所以在 after 里补上自己那一份，流程照抄 initFolderPreviewIcon2x2_9()。
     */
    private fun hookPreviewInit(sheetClass: Class<*>, cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "initPreviewIcon",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val info = XposedHelpers.getObjectField(sheet, Const.F_FOLDER_INFO) ?: return
                    val spec = Const.specOf(DataHook.itemTypeOf(info)) ?: return
                    val preview = previewsOf(sheet)[spec.itemType] ?: return

                    runCatching { loadPreview(sheet, preview, info, spec, cl) }.onFailure {
                        XposedBridge.log("[${Const.TAG}] loadPreview failed: $it")
                    }
                    runCatching { switchToSpec(sheet, spec) }
                }
            }
        )
    }

    private fun loadPreview(
        sheet: View,
        preview: View,
        info: Any,
        spec: Const.GridSpec,
        cl: ClassLoader
    ) {
        val iconCache = XposedHelpers.getObjectField(sheet, Const.F_ICON_CACHE)
        val executor = XposedHelpers.getObjectField(sheet, Const.F_SERIAL_EXECUTOR)
        val predictOn = runCatching {
            XposedHelpers.callMethod(
                XposedHelpers.getObjectField(sheet, Const.F_APP_PREDICT_SLIDING_BUTTON),
                "isChecked"
            ) as Boolean
        }.getOrDefault(false)

        val count = runCatching { XposedHelpers.callMethod(info, "count") as Int }.getOrDefault(0)
        val iconViewClass = XposedHelpers.findClass(Const.CLS_PREVIEW_ICON_VIEW, cl)

        repeat(minOf(spec.maxCount, count)) {
            val iconView = XposedHelpers.newInstance(iconViewClass, sheet.context)
            XposedHelpers.callMethod(preview, "addPreView", iconView)
        }

        runCatching {
            XposedHelpers.callMethod(
                preview, "setFolderIconPlaceholderDrawableMatchingWallpaperColor"
            )
        }
        XposedHelpers.callMethod(
            preview, "loadItemIcons", info, iconCache, predictOn, executor, false
        )
    }

    /**
     * 用户切回宿主三种尺寸时把我们的预览藏起来。
     * 宿主三个 setXxxVisible 只管自己那份可见性，不知道我们的存在。
     */
    private fun hookVisibilityReset(sheetClass: Class<*>) {
        for (name in arrayOf(
            "setDefaultFolderVisible", "setBigFolderVisible2x2_4", "setBigFolderVisible2x2_9"
        )) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    sheetClass, name,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val sheet = param.thisObject as? View ?: return
                            for (preview in previewsOf(sheet).values) {
                                preview.visibility = View.GONE
                            }
                        }
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. 标题文案
    // ------------------------------------------------------------------

    /** 面板标题里的尺寸文案，宿主只有 1*1 / 2*2 / 3*3 三种 */
    private fun hookSizeLabel(sheetClass: Class<*>) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                sheetClass, "getFolderSizeByType",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val type = runCatching {
                            XposedHelpers.getIntField(param.thisObject, Const.F_FOLDER_TYPE)
                        }.getOrDefault(-1)
                        val spec = Const.specOf(type) ?: return
                        param.result = "${spec.columns}*${spec.rows}"
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun boxesOf(sheet: Any): Map<Int, View> =
        XposedHelpers.getAdditionalInstanceField(sheet, K_BOXES) as? Map<Int, View>
            ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun previewsOf(sheet: Any): Map<Int, View> =
        XposedHelpers.getAdditionalInstanceField(sheet, K_PREVIEWS) as? Map<Int, View>
            ?: emptyMap()

    private fun callVoid(target: Any, name: String) {
        runCatching { XposedHelpers.callMethod(target, name) }
    }

    /** 用 (Context, AttributeSet) 构造宿主/miuix 自定义 View；失败返回 null 交由调用方退化 */
    private fun newView(context: Context, cl: ClassLoader, className: String): View? =
        runCatching {
            XposedHelpers.newInstance(
                XposedHelpers.findClass(className, cl),
                arrayOf<Class<*>>(Context::class.java, android.util.AttributeSet::class.java),
                context, null
            ) as View
        }.onFailure {
            XposedBridge.log("[${Const.TAG}] instantiate $className failed: $it")
        }.getOrNull()

    /**
     * 克隆同级 View 的 LayoutParams。
     * ConstraintLayout.LayoutParams 等都提供了同类型拷贝构造，约束原样继承。
     */
    private fun cloneLayoutParams(template: View?): ViewGroup.LayoutParams {
        val src = template?.layoutParams
            ?: return ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )

        runCatching {
            val ctor = src.javaClass.getConstructor(src.javaClass)
            return ctor.newInstance(src) as ViewGroup.LayoutParams
        }

        // 退化：至少保住宽高
        return ViewGroup.LayoutParams(src.width, src.height)
    }
}

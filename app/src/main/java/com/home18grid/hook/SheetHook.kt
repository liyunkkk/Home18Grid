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
 * FolderSheet（长按文件夹 → 「文件夹尺寸」面板）UI 注入。
 *
 * 这是模块 v1.0.0 缺的那一层：当时只改了数据与渲染，
 * 面板里没有 18 宫格选项，用户没有入口去切换。
 *
 * 宿主面板结构（由 res/NLT.xml 的 aapt xmltree 逐节点核实）：
 *
 *   FolderSheet (extends AbstractFloatingView, implements VisualCheckGroup.OnCheckedChangeListener)
 *   └─ ScrollView
 *      └─ ConstraintLayout                                   ← 预览区
 *         ├─ ImageView  folder_picker_select_wallpaper_bg
 *         ├─ ImageView  folder_picker_select_default_folder_bg
 *         ├─ ImageView  folder_picker_select_big_folder_bg    ← 三种大文件夹共用背板
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
 *
 * 状态全部挂在 sheet 实例的 additional field 上，不用 object 静态变量，
 * 避免多次弹出/多实例时状态串台。
 */
object SheetHook {

    private const val K_CHECKBOX = "h18_checkbox"
    private const val K_PREVIEW = "h18_preview"

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

    private fun injectIfNeeded(sheet: View, cl: ClassLoader) {
        val group = XposedHelpers.getObjectField(sheet, Const.F_VISUAL_CHECK_GROUP) as? ViewGroup
            ?: run {
                XposedBridge.log("[${Const.TAG}] ${Const.F_VISUAL_CHECK_GROUP} not found")
                return
            }

        // 面板复用时 initListener 会再跑一遍；控件还挂在同一个 group 上就不重复注入
        val existing = checkBoxOf(sheet)
        if (existing != null && existing.parent === group) return

        val context = sheet.context
        HostRes.dumpDiagnostics(context)

        val checkBox = buildCheckBox(context, cl)
        group.addView(checkBox)   // ← id 与 checked 监听由 miuix 自动补齐
        XposedHelpers.setAdditionalInstanceField(sheet, K_CHECKBOX, checkBox)

        injectPreview(sheet, cl)
    }

    /**
     * 预览区加一个 FolderIconPreviewContainer2X2_9 实例，交给 6x3 算法接管。
     *
     * 注意：不要用三参构造去传 itemType。已由 smali 核实，
     * FolderIconPreviewContainer2X2_9(Context, AttributeSet, int) 的第 3 个参数
     * 一路透传到 ViewGroup(Context, AttributeSet, int)，语义是 defStyleAttr，
     * 传 0x20018 会被当成主题属性 ID 去解析。这里用两参构造，
     * 再显式调 LayoutHook.applyContainerSize() 挂上算法辅助类。
     */
    private fun injectPreview(sheet: View, cl: ClassLoader) {
        val anchor =
            XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_IMG_2X2_9) as? View
                ?: run {
                    XposedBridge.log("[${Const.TAG}] ${Const.F_PICKER_BIG_FOLDER_IMG_2X2_9} missing")
                    return
                }
        val parent = anchor.parent as? ViewGroup ?: return

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
        }.getOrNull() ?: return

        preview.id = View.generateViewId()
        preview.visibility = View.GONE
        preview.clipChildren = false
        preview.clipToPadding = false
        preview.layoutParams = cloneLayoutParams(anchor)
        LayoutHook.applyContainerSize(preview)

        parent.addView(preview, parent.indexOfChild(anchor) + 1)
        XposedHelpers.setAdditionalInstanceField(sheet, K_PREVIEW, preview)
    }

    /**
     * 复刻宿主 VisualCheckBox 的三层结构。
     *
     * 三个自定义类任一实例化失败时逐级退化为 LinearLayout / ImageView / TextView：
     * 此时少了选中动效，但选项照样出现且能点，不会因为换 ROM 版本整个功能消失。
     */
    private fun buildCheckBox(context: Context, cl: ClassLoader): View {
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
        text.text = optionLabel()
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
     * 选项文案。宿主 folder_picker_big_2x2_9_text 是九宫格的（中文"超大"/英文 XXL），
     * 不能直接借用，否则两个选项同名。这里自己给：中文环境「18 格」，其余「6x3」。
     */
    private fun optionLabel(): String =
        if (Locale.getDefault().language == "zh") "18 格" else "6x3"

    // ------------------------------------------------------------------
    // 2. 选中回调
    // ------------------------------------------------------------------

    /**
     * 宿主 onCheckedChanged(VisualCheckGroup, int) 逐个比对三个内置 CheckBox 的 id，
     * 我们的 id 落不到任何分支（相当于 no-op），所以在 after 里补一个分支即可，
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
                val box = checkBoxOf(sheet) ?: return
                if (param.args[1] != box.id) return
                runCatching { switchTo18Grid(sheet) }.onFailure {
                    XposedBridge.log("[${Const.TAG}] switchTo18Grid failed: $it")
                }
            }
        })
    }

    /**
     * 切到 18 宫格，对应宿主的 switchToBigFolder2x2_9()：
     *   隐藏另外两种预览 → 显示自己的 → 开启应用推荐 → 记下 mFolderType。
     *
     * mFolderType 是点「确定」时 onClick 读出来传给
     * ConvertSizeController.convertFolderSize(info, mFolderType) 的值，
     * 是整条切换链真正的落地点。
     */
    private fun switchTo18Grid(sheet: View) {
        callVoid(sheet, "setDefaultFolderGone")
        callVoid(sheet, "setBigFolderGone2x2_4")
        callVoid(sheet, "setBigFolderGone2x2_9")

        // setBigFolderGone2x2_9 把共用背板也隐藏了，这里单独恢复
        (XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_BG) as? View)
            ?.visibility = View.VISIBLE
        previewOf(sheet)?.visibility = View.VISIBLE

        // 18 格容量足够，与宿主九宫格一致地开放"智能推荐应用"
        runCatching { XposedHelpers.callMethod(sheet, "configAppPredict", true) }

        runCatching { XposedHelpers.setIntField(sheet, Const.F_FOLDER_TYPE, Const.FOLDER_18_GRID) }

        checkBoxOf(sheet)?.let { runCatching { XposedHelpers.callMethod(it, "setChecked", true) } }
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
                    val box = checkBoxOf(sheet) ?: return
                    if (param.args[0] == Const.FOLDER_18_GRID) return
                    runCatching { XposedHelpers.callMethod(box, "setChecked", false) }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // 3. 预览图标
    // ------------------------------------------------------------------

    /**
     * initPreviewIcon() 按当前 itemType 决定给哪个容器塞 FolderPreviewIconView
     * 并调 loadItemIcons。0x20018 会落到 else 分支（当普通文件夹处理），
     * 所以在 after 里补上自己那一份，流程照抄 initFolderPreviewIcon2x2_9()。
     */
    private fun hookPreviewInit(sheetClass: Class<*>, cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "initPreviewIcon",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val preview = previewOf(sheet) ?: return
                    val info = XposedHelpers.getObjectField(sheet, Const.F_FOLDER_INFO) ?: return
                    if (DataHook.itemTypeOf(info) != Const.FOLDER_18_GRID) return

                    runCatching { loadPreview(sheet, preview, info, cl) }.onFailure {
                        XposedBridge.log("[${Const.TAG}] loadPreview failed: $it")
                    }
                    runCatching { switchTo18Grid(sheet) }
                }
            }
        )
    }

    private fun loadPreview(sheet: View, preview: View, info: Any, cl: ClassLoader) {
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

        repeat(minOf(Const.GRID_COUNT, count)) {
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
                            previewOf(sheet)?.visibility = View.GONE
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
                        if (type == Const.FOLDER_18_GRID) {
                            param.result = "${Const.GRID_COLUMNS}*${Const.GRID_ROWS}"
                        }
                    }
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun checkBoxOf(sheet: Any) =
        XposedHelpers.getAdditionalInstanceField(sheet, K_CHECKBOX) as? View

    private fun previewOf(sheet: Any) =
        XposedHelpers.getAdditionalInstanceField(sheet, K_PREVIEW) as? View

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
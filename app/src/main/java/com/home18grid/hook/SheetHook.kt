package com.home18grid.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
 * FolderSheet（长按文件夹 → 「文件夹尺寸」面板）UI 注入与原生单选链路接管。
 *
 * v1.1.3 核心原则：完全信任 miuix 原生单选机制，不设 OnClickListener、
 * 不手动 setChecked 任何 CheckBox、不预设 View id（由 onChildViewAdded 自动分配）。
 *
 * 原生链路（smali 取证）：
 *   VisualCheckBox.onTouchEvent(ACTION_UP)
 *     -> toggle() -> setChecked(true)
 *     -> mOnCheckedChangeWidgetListener = VisualCheckGroup$CheckedStateTracker
 *     -> tracker: 若 mCheckedId != -1 先 setCheckedStateForView(旧id, false) 取消旧框，
 *        再 setCheckedId(新id) -> FolderSheet.onCheckedChanged(group, checkedId)
 *     -> 宿主只认 3 个原生 box id；自定义 id 在这里拦截接管。
 */
object SheetHook {
    private const val K_BOXES = "h18_boxes"

    fun install(cl: ClassLoader) {
        val sheet = XposedHelpers.findClass(Const.CLS_FOLDER_SHEET, cl)

        hookInject(sheet, cl)
        hookGroupCheckedChanged(sheet, cl)
        hookHostSwitchMethods(sheet, cl)
        hookCheckedSync(sheet)
        hookPreviewInit(sheet, cl)
        hookVisibilityReset(sheet)
        hookSizeLabel(sheet)
    }

    // ------------------------------------------------------------------
    // 1. 控件注入
    // ------------------------------------------------------------------
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
            ?: return
        val existing = boxesOf(sheet)
        if (existing.isNotEmpty() && existing.values.first().parent === group) {
            // 面板复用：仅刷新预览（勾选状态由原生机制维护，不碰）
            refreshSheetPreview(sheet, cl)
            return
        }

        val context = sheet.context
        val boxes = HashMap<Int, View>()
        for (spec in Const.SPECS.values) {
            val checkBox = buildCheckBox(sheet, context, cl, spec)
            group.addView(checkBox)
            boxes[spec.itemType] = checkBox
        }
        XposedHelpers.setAdditionalInstanceField(sheet, K_BOXES, boxes)
        refreshSheetPreview(sheet, cl)
    }

    /**
     * 构造一个选项控件：
     * - id 留空（-1），交给 VisualCheckGroup$PassThroughHierarchyChangeListener.onChildViewAdded
     *   自动 generateViewId 并挂载 CheckedStateTracker —— 这是原生单选的前提。
     * - 不设任何 OnClickListener：onTouchEvent 的 toggle 分支不调 performClick，
     *   外挂 listener 是死代码且吞掉原生触摸反馈。
     * - 子层级必须是 BorderLayout(VisualCheckItem) + VisualCheckedTextView(VisualCheckItem)，
     *   notifyChecked 才能驱动蓝框与文字变色。
     */
    private fun buildCheckBox(
        sheet: View,
        context: Context,
        cl: ClassLoader,
        spec: Const.GridSpec
    ): View {
        val box = newView(context, cl, Const.CLS_VISUAL_CHECK_BOX) ?: LinearLayout(context)
        if (box is LinearLayout) {
            box.orientation = LinearLayout.VERTICAL
            box.clipChildren = false
            box.clipToPadding = false
        }
        box.tag = Const.TAG_CHECK_BOX
        box.isFocusable = true

        val bgWidth = HostRes.dimenPx(context, Const.RES_DIMEN_BG_WIDTH, 0)
        val padding = HostRes.dimenPx(context, Const.RES_DIMEN_BORDER_PADDING, 0)
        val border = newView(context, cl, Const.CLS_BORDER_LAYOUT) ?: LinearLayout(context)
        HostRes.drawable(context, Const.RES_DRAWABLE_CHECKBOX_BG)?.let {
            runCatching { XposedHelpers.setObjectField(border, "mBackGround", it) }
        }

        val frame = FrameLayout(context).apply { setPadding(padding, padding, padding, padding) }
        val image = ImageView(context)
        image.scaleType = ImageView.ScaleType.FIT_XY
        image.isDuplicateParentStateEnabled = true
        val imageSize = if (bgWidth > 0) bgWidth else dp2px(context, 48f)
        image.setImageDrawable(createOptionThumbnailDrawable(context, spec, imageSize))
        frame.addView(image, FrameLayout.LayoutParams(imageSize, imageSize))

        (border as? ViewGroup)?.addView(
            frame,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        (box as? ViewGroup)?.addView(
            border,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        val text = (newView(context, cl, Const.CLS_VISUAL_CHECKED_TEXT_VIEW) as? TextView)
            ?: TextView(context)
        text.text = optionLabel(spec)
        text.gravity = Gravity.CENTER
        text.maxLines = 1
        HostRes.dimenPx(context, Const.RES_DIMEN_TEXT_SIZE, 0).let {
            if (it > 0) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.toFloat())
        }
        text.setPadding(0, HostRes.dimenPx(context, Const.RES_DIMEN_TITLE_MARGIN_TOP, 0), 0, 0)
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
     * 动态生成选项微缩网格图标（深色系，浅色面板清晰可见；夜间模式换浅色系）。
     */
    private fun createOptionThumbnailDrawable(
        context: Context,
        spec: Const.GridSpec,
        sizePx: Int
    ): Drawable {
        val size = if (sizePx > 0) sizePx else dp2px(context, 48f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 夜间模式用白色系，日间用深黑色系（对比宿主浅色半透明面板背景）
        val darkMode = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val fillColor = if (darkMode) 0x59FFFFFF.toInt() else 0x331A1A1A
        val strokeColor = if (darkMode) 0xCCFFFFFF.toInt() else 0x991A1A1A.toInt()

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.STROKE
            strokeWidth = dp2px(context, 0.8f).toFloat()
        }

        val padding = size * 0.14f
        val contentW = size - padding * 2
        val contentH = size - padding * 2
        val cols = spec.columns
        val rows = spec.rows
        val gap = dp2px(context, 1.5f).toFloat()
        val cellW = (contentW - gap * (cols - 1)) / cols
        val cellH = (contentH - gap * (rows - 1)) / rows
        val cellSize = minOf(cellW, cellH)
        val totalW = cols * cellSize + (cols - 1) * gap
        val totalH = rows * cellSize + (rows - 1) * gap
        val startX = (size - totalW) / 2f
        val startY = (size - totalH) / 2f
        val cornerRadius = cellSize * 0.28f
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val left = startX + c * (cellSize + gap)
                val top = startY + r * (cellSize + gap)
                val rect = RectF(left, top, left + cellSize, top + cellSize)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
            }
        }
        return BitmapDrawable(context.resources, bitmap)
    }

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
    // 2. 原生单选回调拦截：onCheckedChanged(VisualCheckGroup, checkedId)
    // ------------------------------------------------------------------
    /**
     * 宿主 onCheckedChanged(group, checkedId) 只认 3 个原生 box id。
     * 自定义 box 被点击时：tracker 已自动取消旧框 + 更新 mCheckedId，
     * 到达这里时 checkedId 是我们的 box id —— 拦截并切换预览。
     * 原生 box 被点击时：放行宿主逻辑，同时取消自定义 box 的选中态
     * （tracker 的取消只针对 mCheckedId 记录的那一个，原生切换会把 mCheckedId
     *  换成原生 id，我们的 box 之前若被选中则由 mFolderType 状态对齐，无需处理；
     *  但从自定义切回原生时 mFolderType 会被 setCheckedBox 写回，状态天然一致）。
     */
    private fun hookGroupCheckedChanged(sheetClass: Class<*>, cl: ClassLoader) {
        val groupClass = XposedHelpers.findClass(Const.CLS_VISUAL_CHECK_GROUP, cl)

        XposedHelpers.findAndHookMethod(
            sheetClass, "onCheckedChanged",
            groupClass, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val checkedId = param.args[1] as Int
                    val boxes = XposedHelpers.getAdditionalInstanceField(sheet, K_BOXES)
                        as? Map<Int, View> ?: return

                    // 命中自定义 box：拦截宿主（宿主不认这个 id 会静默 return），执行切换
                    for ((specType, box) in boxes) {
                        if (box.id == checkedId) {
                            param.result = null
                            val spec = Const.specOf(specType) ?: return
                            runCatching { switchToSpec(sheet, spec, cl) }.onFailure {
                                XposedBridge.log("[${Const.TAG}] switchToSpec failed: $it")
                            }
                            return
                        }
                    }
                    // 原生 box：放行宿主逻辑（switchToXxx -> setCheckedBox 对齐原生状态）
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // 3. 预览与状态切换
    // ------------------------------------------------------------------
    /**
     * 切换到自定义类型：视觉操作复用宿主 switchToBigFolder2x2_9 的序列，
     * 但 mFolderType 写成自定义 itemType（落库由 handleClose -> convertFolderSize 完成）。
     */
    private fun switchToSpec(sheet: View, spec: Const.GridSpec, cl: ClassLoader) {
        callVoid(sheet, "setBigFolderGone2x2_4")
        callVoid(sheet, "setDefaultFolderGone")
        callVoid(sheet, "setBigFolderVisible2x2_9")
        runCatching {
            XposedHelpers.callMethod(sheet, "configAppPredict", spec.itemType == Const.FOLDER_18_GRID)
        }
        runCatching { XposedHelpers.setIntField(sheet, Const.F_FOLDER_TYPE, spec.itemType) }
        refreshSheetPreview(sheet, cl)
    }

    /** 当前文件夹是自定义类型时，把预览区与选项勾选态就位 */
    private fun refreshSheetPreview(sheet: View, cl: ClassLoader) {
        val curType = runCatching {
            XposedHelpers.getIntField(sheet, Const.F_FOLDER_TYPE)
        }.getOrDefault(-1)
        val spec = Const.specOf(curType) ?: return
        val preview = XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_IMG_2X2_9) as? View
            ?: return
        preview.visibility = View.VISIBLE
        LayoutHook.applyContainerSize(preview, spec)
        val info = XposedHelpers.getObjectField(sheet, Const.F_FOLDER_INFO) ?: return
        runCatching { loadPreview(sheet, preview, info, spec, cl) }.onFailure {
            XposedBridge.log("[${Const.TAG}] sheet preview load failed: $it")
        }
        preview.requestLayout()
        preview.invalidate()
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
        if (preview is ViewGroup) {
            preview.removeAllViews()
        }
        val count = runCatching { XposedHelpers.callMethod(info, "count") as Int }.getOrDefault(0)
        val iconViewClass = XposedHelpers.findClass(Const.CLS_PREVIEW_ICON_VIEW, cl)
        repeat(minOf(spec.maxCount, count)) {
            val iconView = XposedHelpers.newInstance(iconViewClass, sheet.context)
            XposedHelpers.callMethod(preview, "addPreView", iconView)
        }

        runCatching {
            XposedHelpers.callMethod(preview, "setFolderIconPlaceholderDrawableMatchingWallpaperColor")
        }
        XposedHelpers.callMethod(
            preview, "loadItemIcons", info, iconCache, predictOn, executor, false
        )
    }

    // ------------------------------------------------------------------
    // 4. 宿主原生切换方法（打进来时同步 mFolderType 视图状态）
    // ------------------------------------------------------------------
    private fun hookHostSwitchMethods(sheetClass: Class<*>, cl: ClassLoader) {
        val methods = arrayOf<Pair<String, Int>>(
            Pair("switchToDefaultFolder", Const.FOLDER_NORMAL),
            Pair("switchToBigFolder2x2_4", Const.FOLDER_2X2_4),
            Pair("switchToBigFolder2x2_9", Const.FOLDER_2X2_9)
        )
        for ((name, type) in methods) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    sheetClass, name,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val sheet = param.thisObject as? View ?: return
                            // 原生 setCheckedBox 会写 mFolderType；自定义 box 的取消
                            // 由 CheckedStateTracker 在切换前自动完成，无需干预
                            refreshSheetPreview(sheet, cl)
                        }
                    }
                )
            }
        }
    }

    private fun hookCheckedSync(sheetClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "setCheckedBox", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val type = param.args[0] as? Int ?: return
                    if (Const.isCustomFolder(type)) {
                        // 宿主开头就 return（不认 0x20018 等），这里补写 mFolderType
                        // 并刷新预览；原生 box 状态不碰（原生面板初始进来时它们本来就该全灭）
                        runCatching { XposedHelpers.setIntField(sheet, Const.F_FOLDER_TYPE, type) }
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // 5. 预览初始化（面板打开时）
    // ------------------------------------------------------------------
    private fun hookPreviewInit(sheetClass: Class<*>, cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "initPreviewIcon",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val info = XposedHelpers.getObjectField(sheet, Const.F_FOLDER_INFO) ?: return
                    val spec = Const.specOf(DataHook.itemTypeOf(info)) ?: return
                    // 打开面板即切换到当前类型的预览形态（不改变 mFolderType，
                    // 只做视觉呈现；勾选态由 setCheckedBox(mFolderType) 之后的
                    // 原生 tracker 状态决定，本方法不参与）
                    val preview = XposedHelpers.getObjectField(
                        sheet, Const.F_PICKER_BIG_FOLDER_IMG_2X2_9
                    ) as? View ?: return
                    preview.visibility = View.VISIBLE
                    LayoutHook.applyContainerSize(preview, spec)
                    runCatching { loadPreview(sheet, preview, info, spec, cl) }
                    preview.requestLayout()
                    preview.invalidate()
                }
            }
        )
    }

    private fun hookVisibilityReset(sheetClass: Class<*>) {
        for (name in arrayOf("setDefaultFolderVisible", "setBigFolderVisible2x2_4")) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    sheetClass, name,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val sheet = param.thisObject as? View ?: return
                            val preview = XposedHelpers.getObjectField(
                                sheet, Const.F_PICKER_BIG_FOLDER_IMG_2X2_9
                            ) as? View ?: return
                            XposedHelpers.setAdditionalInstanceField(preview, Const.KEY_HELPER, null)
                        }
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. 标题文案
    // ------------------------------------------------------------------
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

    private fun callVoid(target: Any, name: String) {
        runCatching { XposedHelpers.callMethod(target, name) }
    }

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

    private fun dp2px(context: Context, dp: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics
        ).toInt()
}
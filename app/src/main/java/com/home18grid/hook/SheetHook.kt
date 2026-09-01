package com.home18grid.hook

import android.content.Context
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
 * FolderSheet（长按文件夹 → 「文件夹尺寸」面板）UI 注入与严格单选管理。
 *
 * 核心机制：
 * 1. 严格单选互斥：集中通过 [selectOption] 统一管理宿主 3 个原生 CheckBox 与所有自定义 CheckBox，
 *    彻底解决多选冲突、亮多个蓝框或无法切换的问题。
 * 2. 动态选项图标：为 18 格 (6x3)、横三格 (3x1)、竖三格 (1x3) 动态生成高保真微缩栅格图标，
 *    告别千篇一律的九宫格占位图。
 * 3. 稳健的预览容器接管：复用原生 2x2_9 约束背板，杜绝漂浮与定位错乱。
 */
object SheetHook {

    private const val K_BOXES = "h18_boxes"

    fun install(cl: ClassLoader) {
        val sheet = XposedHelpers.findClass(Const.CLS_FOLDER_SHEET, cl)

        hookInject(sheet, cl)
        hookHostSwitchMethods(sheet)
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
            val curType = runCatching {
                XposedHelpers.getIntField(sheet, Const.F_FOLDER_TYPE)
            }.getOrDefault(-1)
            selectOption(sheet, curType)
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

        val curType = runCatching {
            XposedHelpers.getIntField(sheet, Const.F_FOLDER_TYPE)
        }.getOrDefault(-1)
        selectOption(sheet, curType)
    }

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
        box.id = View.generateViewId()
        box.tag = Const.TAG_CHECK_BOX
        box.isFocusable = true
        box.isClickable = true

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

        box.setOnClickListener {
            selectOption(sheet, spec.itemType)
            switchToSpec(sheet, spec, cl)
        }

        return box
    }

    /**
     * 动态生成选项微缩网格图标：
     * - 18格 (6x3): 绘制 6 列 x 3 行微缩矩阵
     * - 横三格 (3x1): 绘制 3 列 x 1 行水平条状微缩矩阵
     * - 竖三格 (1x3): 绘制 1 列 x 3 行垂直条状微缩矩阵
     */
    private fun createOptionThumbnailDrawable(
        context: Context,
        spec: Const.GridSpec,
        sizePx: Int
    ): Drawable {
        val size = if (sizePx > 0) sizePx else dp2px(context, 48f)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#44FFFFFF")
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FFFFFF")
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
    // 2. 集中式单选互斥控制
    // ------------------------------------------------------------------

    fun selectOption(sheet: View, targetType: Int) {
        val defaultBox = XposedHelpers.getObjectField(sheet, Const.F_DEFAULT_FOLDER_CHECK_BOX) as? View
        val box2x2_4 = XposedHelpers.getObjectField(sheet, Const.F_BIG_FOLDER_CHECK_BOX_2X2_4) as? View
        val box2x2_9 = XposedHelpers.getObjectField(sheet, Const.F_BIG_FOLDER_CHECK_BOX_2X2_9) as? View

        runCatching { XposedHelpers.callMethod(defaultBox, "setChecked", targetType == Const.FOLDER_NORMAL) }
        runCatching { XposedHelpers.callMethod(box2x2_4, "setChecked", targetType == Const.FOLDER_2X2_4) }
        runCatching { XposedHelpers.callMethod(box2x2_9, "setChecked", targetType == Const.FOLDER_2X2_9) }

        for ((specType, box) in boxesOf(sheet)) {
            val isTarget = (specType == targetType)
            runCatching { XposedHelpers.callMethod(box, "setChecked", isTarget) }
        }
    }

    private fun hookHostSwitchMethods(sheetClass: Class<*>) {
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
                            selectOption(sheet, type)
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
                    selectOption(sheet, type)
                    if (Const.isCustomFolder(type)) {
                        runCatching { XposedHelpers.setIntField(sheet, Const.F_FOLDER_TYPE, type) }
                    }
                }
            }
        )
    }

    private fun switchToSpec(sheet: View, spec: Const.GridSpec, cl: ClassLoader) {
        selectOption(sheet, spec.itemType)

        callVoid(sheet, "setDefaultFolderGone")
        callVoid(sheet, "setBigFolderGone2x2_4")
        (XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_BG) as? View)
            ?.visibility = View.VISIBLE

        val preview = XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_IMG_2X2_9) as? View
        if (preview != null) {
            preview.visibility = View.VISIBLE
            LayoutHook.applyContainerSize(preview, spec)
            val info = XposedHelpers.getObjectField(sheet, Const.F_FOLDER_INFO)
            if (info != null) {
                runCatching { loadPreview(sheet, preview, info, spec, cl) }
            }
            preview.requestLayout()
            preview.invalidate()
        }

        runCatching {
            XposedHelpers.callMethod(sheet, "configAppPredict", spec.itemType == Const.FOLDER_18_GRID)
        }
        runCatching { XposedHelpers.setIntField(sheet, Const.F_FOLDER_TYPE, spec.itemType) }
    }

    // ------------------------------------------------------------------
    // 3. 预览加载
    // ------------------------------------------------------------------

    private fun hookPreviewInit(sheetClass: Class<*>, cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "initPreviewIcon",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val info = XposedHelpers.getObjectField(sheet, Const.F_FOLDER_INFO) ?: return
                    val spec = Const.specOf(DataHook.itemTypeOf(info)) ?: return
                    runCatching { switchToSpec(sheet, spec, cl) }
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
    // 4. 标题文案
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
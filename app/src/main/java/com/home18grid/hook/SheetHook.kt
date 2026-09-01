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
 * 解决两大核心问题：
 * 1. 选项互斥：在切换和同步时显式管理所有自定义 CheckBox 的勾选状态，杜绝多选冲突。
 * 2. 预览定位：直接复用宿主原生的 mFolderPickerSelectBigFolderImg2x2_9 预览容器，
 *    不动态 new View，100% 保留原生 ConstraintLayout 约束，从根本上杜绝图标飘到 (0,0)。
 */
object SheetHook {

    /** sheet 上记 (itemType -> 选项 View) 的附加字段 */
    private const val K_BOXES = "h18_boxes"

    fun install(cl: ClassLoader) {
        val sheet = XposedHelpers.findClass(Const.CLS_FOLDER_SHEET, cl)

        hookInject(sheet, cl)
        hookGroupCheckedChanged(sheet, cl)
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
        if (existing.isNotEmpty() && existing.values.first().parent === group) return

        val context = sheet.context
        val boxes = HashMap<Int, View>()
        for (spec in Const.SPECS.values) {
            val checkBox = buildCheckBox(context, cl, spec)
            group.addView(checkBox)
            boxes[spec.itemType] = checkBox
        }
        XposedHelpers.setAdditionalInstanceField(sheet, K_BOXES, boxes)
    }

    private fun buildCheckBox(context: Context, cl: ClassLoader, spec: Const.GridSpec): View {
        val box = newView(context, cl, Const.CLS_VISUAL_CHECK_BOX) ?: LinearLayout(context)
        if (box is LinearLayout) {
            box.orientation = LinearLayout.VERTICAL
            box.clipChildren = false
            box.clipToPadding = false
        }
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
        HostRes.drawable(context, Const.RES_DRAWABLE_BORDER_2X2_9)?.let { image.setImageDrawable(it) }

        val imageSize = if (bgWidth > 0) bgWidth else ViewGroup.LayoutParams.WRAP_CONTENT
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
    // 2. 选中回调与互斥控制
    // ------------------------------------------------------------------

    private fun hookGroupCheckedChanged(sheetClass: Class<*>, cl: ClassLoader) {
        val method = sheetClass.declaredMethods.firstOrNull {
            it.name == "onCheckedChanged" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: return

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val sheet = param.thisObject as? View ?: return
                val checkedId = param.args[1] as? Int ?: return
                val spec = specOfBox(sheet, checkedId) ?: return
                runCatching { switchToSpec(sheet, spec, cl) }.onFailure {
                    XposedBridge.log("[${Const.TAG}] switchToSpec failed: $it")
                }
            }
        })
    }

    private fun specOfBox(sheet: View, viewId: Int): Const.GridSpec? {
        for ((type, box) in boxesOf(sheet)) {
            if (box.id == viewId) return Const.specOf(type)
        }
        return null
    }

    /**
     * 切换到自定义尺寸：
     * 1. 互斥设置所有自定义 CheckBox 的状态（选中的为 true，其余为 false）
     * 2. 隐藏宿主其它预览，复用原生 2x2_9 预览容器并重新按 spec 装载数据
     */
    private fun switchToSpec(sheet: View, spec: Const.GridSpec, cl: ClassLoader) {
        // 1. 严格互斥管理全部自定义 CheckBox
        for ((type, box) in boxesOf(sheet)) {
            val isTarget = (type == spec.itemType)
            runCatching { XposedHelpers.callMethod(box, "setChecked", isTarget) }
        }

        // 2. 面板隐藏 1x1 和 2x2_4 预览，展示共用大背板
        callVoid(sheet, "setDefaultFolderGone")
        callVoid(sheet, "setBigFolderGone2x2_4")
        (XposedHelpers.getObjectField(sheet, Const.F_PICKER_BIG_FOLDER_BG) as? View)
            ?.visibility = View.VISIBLE

        // 3. 复用宿主原生 2x2_9 预览容器
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

    /** 宿主 setCheckedBox 触发时同步取消所有自定义 box 的高亮（切换到 1x1/2x2/九宫格时） */
    private fun hookCheckedSync(sheetClass: Class<*>) {
        XposedHelpers.findAndHookMethod(
            sheetClass, "setCheckedBox", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val sheet = param.thisObject as? View ?: return
                    val type = param.args[0] as? Int ?: return
                    // 切到宿主原生类型（2, 21, 22）时，全部取消勾选
                    for ((specType, box) in boxesOf(sheet)) {
                        val isTarget = (specType == type)
                        runCatching { XposedHelpers.callMethod(box, "setChecked", isTarget) }
                    }
                }
            }
        )
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

        // 清理已有子 View 并重新填充对应数量的 PreviewIconView
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

    /** 切回宿主原生 1x1 / 2x2_4 时重置 helper 避免污染 */
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
                            // 摘掉自定义 helper
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
}

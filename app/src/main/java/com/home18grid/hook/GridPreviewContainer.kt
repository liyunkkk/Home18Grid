package com.home18grid.hook

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import de.robv.android.xposed.XposedHelpers

/**
 * 通用「列 x 行」预览容器算法。
 *
 * 接管宿主 FolderIconPreviewContainer2X2_9 的三个布局方法：
 *   preMeasure2x2(int, int)  计算单元格尺寸与内外边距
 *   preSetup2x2()            生成 (large + small) 个 FolderIconPreviewInfo 定位信息
 *   getSmallItemsRectF()     返回小图标组所在那一格的矩形（动画缩放锚点）
 *
 * 之前的 FolderPreviewContainer6X3 只支持 6x3，这里泛化为按 GridSpec 参数化，
 * 一套算法同时服务 18 宫格(6x3)、横三宫格(3x1)、纵三宫格(1x3)。
 *
 * 格位分配（与宿主 2x2_9 的「大图标 + 末格小图标组」同构）：
 *   index 0 .. large-1          前 large 格大图标，index < mLargeIconNum，
 *                               被 onMeasureChild2x2 设为 BIGICONVIEW，可直接点击启动；
 *   index large .. large+small  最后一格内部的 2x2 微型小图标，
 *                               index >= mLargeIconNum，设为 SMALLICONVIEW，
 *                               点它走文件夹打开逻辑，表示「还有更多」。
 *
 * 尺寸策略：
 *   单元格边长取 min(可用宽/cols, 可用高/rows)，再乘 ICON_RATIO 得到图标边长，
 *   余下空间按 边:内 = edgeWeight:1 分摊成外边距与图标间距，
 *   保证图标既是正方形、又与圆角卡片边缘留出呼吸感。
 *
 * 所有字段写回都包在 runCatching 里；字段名在未来 ROM 变更后找不到
 * 也只会退化为宿主原生布局，不会抛异常打崩桌面进程。
 */
class GridPreviewContainer(
    private val instance: View,
    private val spec: Const.GridSpec
) {

    private var itemSize: Int = 0
    private var smallItemSize: Int = 0
    private var smallInner: Int = 0
    private var edgeHor: Int = 0
    private var edgeVer: Int = 0
    private var innerHor: Int = 0
    private var innerVer: Int = 0

    /** 当前算法对应的 itemType；LayoutHook 用它检测 spec 变化、避免重复构造 */
    val specItemType: Int get() = spec.itemType

    /** 外边距相对图标间距的权重。宿主 2x2_9 的 edge/inner ≈ 6.25%/4.4% ≈ 1.4 */
    private val edgeWeight = 1.4f

    private val cols get() = spec.columns
    private val rows get() = spec.rows
    private val largeCount get() = spec.largeCount
    private val smallCount get() = spec.smallCount

    fun onPreMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availWidth = View.MeasureSpec.getSize(widthMeasureSpec) -
            instance.paddingStart - instance.paddingEnd
        val availHeight = View.MeasureSpec.getSize(heightMeasureSpec) -
            instance.paddingTop - instance.paddingBottom
        if (availWidth <= 0 || availHeight <= 0) return

        // 单元格取宽高两个方向的较小值，图标才是正方形而不是被拉扁
        val cell = minOf(availWidth / cols, availHeight / rows)
        itemSize = (cell * Const.ICON_RATIO).toInt().coerceAtLeast(1)

        // 水平：cols 个图标 + (cols-1) 个内间距 + 2 个外边距，按权重分摊剩余空间
        val leftWidth = (availWidth - itemSize * cols).coerceAtLeast(0)
        innerHor = if (cols > 1) {
            (leftWidth / ((cols - 1) + 2 * edgeWeight)).toInt()
        } else 0
        edgeHor = ((leftWidth - innerHor * (cols - 1)) / 2).coerceAtLeast(0)

        // 垂直：rows 个图标 + (rows-1) 个内间距 + 2 个外边距
        val leftHeight = (availHeight - itemSize * rows).coerceAtLeast(0)
        innerVer = if (rows > 1) {
            (leftHeight / ((rows - 1) + 2 * edgeWeight)).toInt()
        } else 0
        edgeVer = ((leftHeight - innerVer * (rows - 1)) / 2).coerceAtLeast(0)

        // 末格内部的 2x2 小图标：一格再切四份，中间留一条细缝
        smallInner = (itemSize / 12).coerceAtLeast(1)
        smallItemSize = ((itemSize - smallInner) / 2).coerceAtLeast(1)

        writeBackHostFields()
    }

    /**
     * 把算好的值写回宿主容器字段。
     *
     * onMeasureChild2x2 直接读 mLargeItemWith / mLargeItemHeight（大图标）
     * 与 mSmallItemWith / mSmallItemHeight（小图标）来 measure 子 View，
     * 所以这一步必须做，否则子 View 尺寸仍是宿主原生的 1/3 格。
     */
    private fun writeBackHostFields() {
        runCatching {
            XposedHelpers.setIntField(instance, Const.F_EDGE_HOR, edgeHor)
            XposedHelpers.setIntField(instance, Const.F_EDGE_VER, edgeVer)
            XposedHelpers.setIntField(instance, Const.F_INNER_HOR, innerHor)
            XposedHelpers.setIntField(instance, Const.F_INNER_VER, innerVer)
            XposedHelpers.setIntField(instance, Const.F_LARGE_ITEM_W, itemSize)
            XposedHelpers.setIntField(instance, Const.F_LARGE_ITEM_H, itemSize)
            XposedHelpers.setIntField(instance, Const.F_SMALL_ITEM_W, smallItemSize)
            XposedHelpers.setIntField(instance, Const.F_SMALL_ITEM_H, smallItemSize)
            XposedHelpers.setIntField(instance, Const.F_SMALL_INNER, smallInner)
        }
    }

    /**
     * 生成 (large + small) 个格位的定位信息。
     *
     * FolderIconPreviewInfo 构造签名（已由 smali 核实）：
     *   <init>(int screenX, int screenY, int width, int height, Rect rect, Rect groupRect)
     * onLayout2x2 只取 getGroupRect()，所以第 6 个参数是实际布局矩形。
     */
    @Suppress("UNCHECKED_CAST")
    fun onPreSetup(classLoader: ClassLoader) {
        if (itemSize <= 0) return

        val list = XposedHelpers.callMethod(instance, "getMPvItemLocationInfoList")
            as? MutableList<Any> ?: return
        list.clear()

        val infoClass = XposedHelpers.findClass(Const.CLS_PREVIEW_INFO, classLoader)
        val constructor = XposedHelpers.findConstructorExact(
            infoClass,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Rect::class.java,
            Rect::class.java
        )

        val rtl = isLayoutRtl(classLoader)
        val startX = instance.paddingStart + edgeHor
        val startY = instance.paddingTop + edgeVer

        // 前 large 格：整格大图标
        for (i in 0 until largeCount) {
            val (x, y) = cellOrigin(i, rtl, startX, startY)
            list.add(newInfo(constructor, x, y, itemSize))
        }

        // 末格：内部再排 2x2 的小图标
        if (smallCount > 0) {
            val (groupX, groupY) = cellOrigin(largeCount, rtl, startX, startY)
            for (j in 0 until smallCount) {
                val sr = j / 2
                val rawSc = j % 2
                val sc = if (rtl) 1 - rawSc else rawSc
                val x = groupX + sc * (smallItemSize + smallInner)
                val y = groupY + sr * (smallItemSize + smallInner)
                list.add(newInfo(constructor, x, y, smallItemSize))
            }
        }
    }

    private fun newInfo(
        constructor: java.lang.reflect.Constructor<*>,
        x: Int,
        y: Int,
        size: Int
    ): Any = constructor.newInstance(
        x, y, size, size,
        Rect(0, 0, size, size),
        Rect(x, y, x + size, y + size)
    )

    /** 第 index 个格子的左上角坐标（RTL 时水平镜像，与宿主 isLayoutRtl 分支一致） */
    private fun cellOrigin(index: Int, rtl: Boolean, startX: Int, startY: Int): Pair<Int, Int> {
        val row = index / cols
        val rawCol = index % cols
        val col = if (rtl) cols - 1 - rawCol else rawCol
        return Pair(
            startX + col * (itemSize + innerHor),
            startY + row * (itemSize + innerVer)
        )
    }

    /**
     * 宿主语义是「小图标组所在的那一格」的矩形（不是整个内容区），
     * 打开/收起动画拿它做缩放锚点，所以这里返回末格。
     */
    fun getSmallItemsRectF(): RectF {
        if (itemSize <= 0) return RectF()
        val rtl = isLayoutRtl(instance.context.classLoader)
        val (x, y) = cellOrigin(
            largeCount, rtl,
            instance.paddingStart + edgeHor, instance.paddingTop + edgeVer
        )
        val span = (smallItemSize * 2 + smallInner).toFloat()
        return RectF(x.toFloat(), y.toFloat(), x + span, y + span)
    }

    private fun isLayoutRtl(classLoader: ClassLoader): Boolean = runCatching {
        val cls = XposedHelpers.findClass(Const.CLS_DEVICE_CONFIGS, classLoader)
        XposedHelpers.callStaticMethod(cls, "isLayoutRtl") as Boolean
    }.getOrDefault(false)
}

package com.home18grid.hook

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import de.robv.android.xposed.XposedHelpers

/**
 * 6 列 x 3 行 = 18 格预览容器算法。
 *
 * 接管宿主 FolderIconPreviewContainer2X2_9 的三个布局方法：
 *   preMeasure2x2(int, int)  计算单元格尺寸与内外边距
 *   preSetup2x2()            生成 21 个 FolderIconPreviewInfo 定位信息
 *   getSmallItemsRectF()     返回第 18 格（小图标组）的矩形
 *
 * 格位分配（与宿主 2x2_9 的「8 大 + 4 小」同构，只是把 3x3 换成 6x3）：
 *   index 0..16   前 17 格大图标，index < mLargeIconNum，
 *                 被 onMeasureChild2x2 设为 BIGICONVIEW，可直接点击启动；
 *   index 17..20  第 18 格（右下角）内部的 2x2 微型小图标，
 *                 index >= mLargeIconNum，设为 SMALLICONVIEW，
 *                 点它走文件夹打开逻辑，用来表示「还有更多」。
 *
 * 尺寸策略：
 *   单元格边长取 min(可用宽/6, 可用高/3)，再乘 ICON_RATIO 得到图标边长，
 *   余下的空间按 边:内 = EDGE_WEIGHT:1 的比例分摊成外边距与图标间距，
 *   保证图标既是正方形、又与圆角卡片边缘留出呼吸感。
 *
 * 所有字段写回都包在 runCatching 里；任一字段名在未来 ROM 变更后找不到
 * 也只会退化为宿主原生布局，不会抛异常打崩桌面进程。
 */
class FolderPreviewContainer6X3(private val instance: View) {

    private var itemSize: Int = 0
    private var smallItemSize: Int = 0
    private var smallInner: Int = 0
    private var edgeHor: Int = 0
    private var edgeVer: Int = 0
    private var innerHor: Int = 0
    private var innerVer: Int = 0

    /** 外边距相对图标间距的权重。宿主 2x2_9 的 edge/inner ≈ 6.25%/4.4% ≈ 1.4 */
    private val edgeWeight = 1.4f

    fun onPreMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availWidth = View.MeasureSpec.getSize(widthMeasureSpec) -
            instance.paddingStart - instance.paddingEnd
        val availHeight = View.MeasureSpec.getSize(heightMeasureSpec) -
            instance.paddingTop - instance.paddingBottom
        if (availWidth <= 0 || availHeight <= 0) return

        // 单元格取宽高两个方向的较小值，图标才是正方形而不是被拉扁
        val cell = minOf(availWidth / Const.GRID_COLUMNS, availHeight / Const.GRID_ROWS)
        itemSize = (cell * Const.ICON_RATIO).toInt().coerceAtLeast(1)

        // 水平：6 个图标 + 5 个内间距 + 2 个外边距，按权重分摊剩余空间
        val leftWidth = (availWidth - itemSize * Const.GRID_COLUMNS).coerceAtLeast(0)
        innerHor = (leftWidth / ((Const.GRID_COLUMNS - 1) + 2 * edgeWeight)).toInt()
        edgeHor = ((leftWidth - innerHor * (Const.GRID_COLUMNS - 1)) / 2).coerceAtLeast(0)

        // 垂直：3 个图标 + 2 个内间距 + 2 个外边距
        val leftHeight = (availHeight - itemSize * Const.GRID_ROWS).coerceAtLeast(0)
        innerVer = (leftHeight / ((Const.GRID_ROWS - 1) + 2 * edgeWeight)).toInt()
        edgeVer = ((leftHeight - innerVer * (Const.GRID_ROWS - 1)) / 2).coerceAtLeast(0)

        // 第 18 格内部的 2x2 小图标：一格再切四份，中间留一条细缝
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
     * 生成 21 个格位的定位信息（17 个大格 + 第 18 格内的 4 个小格）。
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

        // 前 17 格：整格大图标
        for (i in 0 until Const.LARGE_COUNT) {
            val (x, y) = cellOrigin(i, rtl, startX, startY)
            list.add(newInfo(constructor, x, y, itemSize))
        }

        // 第 18 格：内部再排 2x2 的 4 个小图标
        val (groupX, groupY) = cellOrigin(Const.LARGE_COUNT, rtl, startX, startY)
        for (j in 0 until Const.SMALL_COUNT) {
            val sr = j / 2
            val rawSc = j % 2
            val sc = if (rtl) 1 - rawSc else rawSc
            val x = groupX + sc * (smallItemSize + smallInner)
            val y = groupY + sr * (smallItemSize + smallInner)
            list.add(newInfo(constructor, x, y, smallItemSize))
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
        val row = index / Const.GRID_COLUMNS
        val rawCol = index % Const.GRID_COLUMNS
        val col = if (rtl) Const.GRID_COLUMNS - 1 - rawCol else rawCol
        return Pair(
            startX + col * (itemSize + innerHor),
            startY + row * (itemSize + innerVer)
        )
    }

    /**
     * 宿主语义是「小图标组所在的那一格」的矩形（不是整个内容区），
     * 打开/收起动画拿它做缩放锚点，所以这里返回第 18 格。
     */
    fun getSmallItemsRectF(): RectF {
        if (itemSize <= 0) return RectF()
        val rtl = isLayoutRtl(instance.context.classLoader)
        val (x, y) = cellOrigin(
            Const.LARGE_COUNT, rtl,
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

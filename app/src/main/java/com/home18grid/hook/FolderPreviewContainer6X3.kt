package com.home18grid.hook

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import de.robv.android.xposed.XposedHelpers

/**
 * 6 列 x 3 行 = 18 格等分预览容器算法。
 *
 * 接管宿主 FolderIconPreviewContainer2X2_9 的三个布局方法：
 *   preMeasure2x2(int, int)  计算单元格尺寸与内外边距
 *   preSetup2x2()            生成 18 个 FolderIconPreviewInfo 定位信息
 *   getSmallItemsRectF()     返回整体内容区矩形
 *
 * 设计要点：
 * 1. 宿主原生 2x2_9 的算法是「4 个大格 + 若干小格」两级尺寸；
 *    这里改成 18 个完全等大的格子，配合 mLargeIconNum = 18，
 *    使 onMeasureChild2x2 把每一个子 View 都当作 BIGICONVIEW 处理，
 *    这是「18 个图标全部可直接点击启动」的关键。
 * 2. 图标保持正方形：先按宽高分别算出可用边长，取较小值，
 *    再把多余空间平摊到边距上做居中，避免图标被拉扁。
 * 3. 所有字段写回都包在 runCatching 里；任一字段名在未来 ROM 变更后
 *    找不到也只会退化为宿主原生布局，不会抛异常打崩桌面进程。
 */
class FolderPreviewContainer6X3(private val instance: View) {

    private var itemSize: Int = 0
    private var edgeHor: Int = 0
    private var edgeVer: Int = 0
    private var innerHor: Int = 0
    private var innerVer: Int = 0

    /** 间距占可用宽度的比例，取值参考宿主 2x2_9 的 m2x2SmallItemMergeInnerPercent */
    private val innerPercent = 0.022f

    fun onPreMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availWidth = View.MeasureSpec.getSize(widthMeasureSpec) -
            instance.paddingStart - instance.paddingEnd
        val availHeight = View.MeasureSpec.getSize(heightMeasureSpec) -
            instance.paddingTop - instance.paddingBottom
        if (availWidth <= 0 || availHeight <= 0) return

        val gap = (availWidth * innerPercent).toInt().coerceAtLeast(1)
        innerHor = gap
        innerVer = gap

        // 6 列 5 个水平间隔、3 行 2 个垂直间隔，分别算出能容纳的最大正方形边长
        val sizeByWidth = (availWidth - innerHor * (Const.GRID_COLUMNS - 1)) / Const.GRID_COLUMNS
        val sizeByHeight = (availHeight - innerVer * (Const.GRID_ROWS - 1)) / Const.GRID_ROWS
        itemSize = minOf(sizeByWidth, sizeByHeight).coerceAtLeast(1)

        // 剩余空间平摊为左右/上下边距，实现整体居中
        val usedWidth = itemSize * Const.GRID_COLUMNS + innerHor * (Const.GRID_COLUMNS - 1)
        val usedHeight = itemSize * Const.GRID_ROWS + innerVer * (Const.GRID_ROWS - 1)
        edgeHor = ((availWidth - usedWidth) / 2).coerceAtLeast(0)
        edgeVer = ((availHeight - usedHeight) / 2).coerceAtLeast(0)

        writeBackHostFields()
    }

    /**
     * 把算好的值写回宿主容器字段。
     *
     * onMeasureChild2x2 会直接读 mLargeItemWith / mLargeItemHeight 来 measure 子 View，
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
            XposedHelpers.setIntField(instance, Const.F_SMALL_ITEM_W, itemSize)
            XposedHelpers.setIntField(instance, Const.F_SMALL_ITEM_H, itemSize)
            XposedHelpers.setIntField(instance, Const.F_SMALL_INNER, innerHor)
        }
    }

    /**
     * 生成 18 个格位的定位信息。
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

        for (i in 0 until Const.GRID_COUNT) {
            val row = i / Const.GRID_COLUMNS
            val rawCol = i % Const.GRID_COLUMNS
            // RTL 语言下水平镜像，与宿主 isLayoutRtl 分支行为保持一致
            val col = if (rtl) Const.GRID_COLUMNS - 1 - rawCol else rawCol

            val x = startX + col * (itemSize + innerHor)
            val y = startY + row * (itemSize + innerVer)

            val rect = Rect(0, 0, itemSize, itemSize)
            val groupRect = Rect(x, y, x + itemSize, y + itemSize)

            list.add(constructor.newInstance(x, y, itemSize, itemSize, rect, groupRect))
        }
    }

    fun getSmallItemsRectF(): RectF {
        val left = (instance.paddingStart + edgeHor).toFloat()
        val top = (instance.paddingTop + edgeVer).toFloat()
        val right = left + (itemSize * Const.GRID_COLUMNS + innerHor * (Const.GRID_COLUMNS - 1))
        val bottom = top + (itemSize * Const.GRID_ROWS + innerVer * (Const.GRID_ROWS - 1))
        return RectF(left, top, right, bottom)
    }

    private fun isLayoutRtl(classLoader: ClassLoader): Boolean = runCatching {
        val cls = XposedHelpers.findClass(Const.CLS_DEVICE_CONFIGS, classLoader)
        XposedHelpers.callStaticMethod(cls, "isLayoutRtl") as Boolean
    }.getOrDefault(false)
}
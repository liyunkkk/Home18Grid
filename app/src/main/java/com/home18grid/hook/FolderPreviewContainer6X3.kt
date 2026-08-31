package com.home18grid.hook

import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import de.robv.android.xposed.XposedHelpers
import java.util.ArrayList

class FolderPreviewContainer6X3(private val instance: View) {

    private var itemWidth: Int = 0
    private var itemHeight: Int = 0
    private var edgeHor: Int = 0
    private var edgeVer: Int = 0
    private var innerHor: Int = 0
    private var innerVer: Int = 0

    fun onPreMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec) - instance.paddingStart - instance.paddingEnd
        val height = View.MeasureSpec.getSize(heightMeasureSpec) - instance.paddingTop - instance.paddingBottom

        // 6列 x 3行 均匀排布
        edgeHor = (width * 0.04f).toInt()
        edgeVer = (height * 0.04f).toInt()
        innerHor = (width * 0.025f).toInt()
        innerVer = (height * 0.025f).toInt()

        // 宽度计算: 6 列，5 个水平间隔
        itemWidth = (width - edgeHor * 2 - innerHor * 5) / 6
        // 高度计算: 3 行，2 个垂直间隔
        itemHeight = (height - edgeVer * 2 - innerVer * 2) / 3

        // 写回宿主容器对应字段保持一致性
        try {
            XposedHelpers.setIntField(instance, "mLarge2x2ItemMergeEdgeHor", edgeHor)
            XposedHelpers.setIntField(instance, "mLarge2x2ItemMergeEdgeVer", edgeVer)
            XposedHelpers.setIntField(instance, "mLarge2x2ItemMergeInnerHor", innerHor)
            XposedHelpers.setIntField(instance, "mLarge2x2ItemMergeInnerVer", innerVer)
            XposedHelpers.setIntField(instance, "mLargeItemWith", itemWidth)
            XposedHelpers.setIntField(instance, "mLargeItemHeight", itemHeight)
            XposedHelpers.setIntField(instance, "mSmallItemWith", itemWidth)
            XposedHelpers.setIntField(instance, "mSmallItemHeight", itemHeight)
            XposedHelpers.setIntField(instance, "mSmall2x2ItemMergeInner", innerHor)
        } catch (_: Throwable) {}
    }

    @Suppress("UNCHECKED_CAST")
    fun onPreSetup(classLoader: ClassLoader) {
        val list = XposedHelpers.callMethod(instance, "getMPvItemLocationInfoList") as? ArrayList<Any> ?: return
        list.clear()

        val infoClass = XposedHelpers.findClass("com.miui.home.folder.FolderIconPreviewInfo", classLoader)
        val constructor = XposedHelpers.findConstructorExact(
            infoClass,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Rect::class.java,
            Rect::class.java
        )

        val startX = instance.paddingStart + edgeHor
        val startY = instance.paddingTop + edgeVer

        for (i in 0 until 18) {
            val col = i % 6
            val row = i / 6

            val x = startX + col * (itemWidth + innerHor)
            val y = startY + row * (itemHeight + innerVer)

            val drawRect = Rect(0, 0, itemWidth, itemHeight)
            val locationRect = Rect(x, y, x + itemWidth, y + itemHeight)

            val itemInfo = constructor.newInstance(0, 0, itemWidth, itemHeight, drawRect, locationRect)
            list.add(itemInfo)
        }
    }

    fun getSmallItemsRectF(): RectF {
        val left = (instance.paddingStart + edgeHor).toFloat()
        val top = (instance.paddingTop + edgeVer).toFloat()
        val right = left + (itemWidth * 6 + innerHor * 5).toFloat()
        val bottom = top + (itemHeight * 3 + innerVer * 2).toFloat()
        return RectF(left, top, right, bottom)
    }
}
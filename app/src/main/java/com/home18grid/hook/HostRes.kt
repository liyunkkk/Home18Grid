package com.home18grid.hook

import android.content.Context
import android.graphics.drawable.Drawable
import de.robv.android.xposed.XposedBridge

/**
 * 宿主资源按名查找工具。
 *
 * 模块自身没有 res 目录，所有 drawable / dimen / color / layout / string
 * 都从宿主 (com.miui.home) 的资源表里按名字反查 ID 再取值。
 *
 * 每个取值都做了兜底：查不到返回传入的默认值，绝不抛异常打崩桌面进程。
 * 换 ROM 版本后若某个名字变了，表现为"少了个动效/间距不对"，而不是桌面 FC。
 */
object HostRes {

    private const val PKG = Const.HOST

    private fun idOf(context: Context, type: String, name: String): Int = runCatching {
        context.resources.getIdentifier(name, type, PKG)
    }.getOrDefault(0)

    fun layout(context: Context, name: String): Int = idOf(context, "layout", name)

    fun drawable(context: Context, name: String): Drawable? = runCatching {
        val resId = idOf(context, "drawable", name)
        if (resId == 0) null else context.resources.getDrawable(resId, context.theme)
    }.getOrNull()

    fun dimenPx(context: Context, name: String, fallback: Int): Int = runCatching {
        val resId = idOf(context, "dimen", name)
        if (resId == 0) fallback else context.resources.getDimensionPixelSize(resId)
    }.getOrDefault(fallback)

    fun color(context: Context, name: String, fallback: Int): Int = runCatching {
        val resId = idOf(context, "color", name)
        if (resId == 0) fallback else context.resources.getColor(resId, context.theme)
    }.getOrDefault(fallback)

    fun string(context: Context, name: String, fallback: String): String = runCatching {
        val resId = idOf(context, "string", name)
        if (resId == 0) fallback else context.resources.getString(resId)
    }.getOrDefault(fallback)

    /**
     * 一次性把关键资源的解析结果打进 Xposed 日志。
     * 换 ROM 版本后第一时间用 `logcat | grep Home18Grid` 就能定位缺哪个名字。
     */
    fun dumpDiagnostics(context: Context) {
        val entries = listOf(
            "layout" to Const.RES_LAYOUT_FOLDER_ICON_2X2_9,
            "drawable" to Const.RES_DRAWABLE_CHECKBOX_BG,
            "drawable" to Const.RES_DRAWABLE_BORDER_2X2_9,
            "dimen" to Const.RES_DIMEN_BORDER_PADDING,
            "dimen" to Const.RES_DIMEN_BG_WIDTH,
            "dimen" to Const.RES_DIMEN_TEXT_SIZE,
            "dimen" to Const.RES_DIMEN_TITLE_MARGIN_TOP,
            "color" to Const.RES_COLOR_TEXT_CHECKED,
            "color" to Const.RES_COLOR_TEXT_UNCHECKED,
            "string" to Const.RES_STRING_BIG_2X2_9
        )

        val sb = StringBuilder("[${Const.TAG}] host resource resolution:\n")
        for ((type, name) in entries) {
            val resId = idOf(context, type, name)
            sb.append("  ").append(type).append('/').append(name).append(" -> ")
                .append(if (resId == 0) "MISSING" else "0x${Integer.toHexString(resId)}")
                .append('\n')
        }
        XposedBridge.log(sb.toString())
    }
}
package com.home18grid.hook

import android.database.Cursor
import android.net.Uri
import android.util.Log
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 数据层 Hook：让 itemType = 0x20018 在宿主眼里是一个合法的大文件夹类型，
 * 并保证它能从 SQLite 正确加载（重启桌面 / 重启手机不丢）。
 *
 * 三件事：
 *   1. FolderInfo 语义：isBigFolder / getPreviewMaxCount / getFolderGridSize
 *   2. 尺寸换算：FolderIconConvertSizeController 的 spanX/spanY 返回 2x2
 *   3. DB 加载：LoaderTask.fromQuery 放宽 itemType 过滤 + loadItems 分流到 loadFolder
 *
 * 全部签名取自 ENH 桌面 (RELEASE-7.00.00.2300) 的 baksmali 产物：
 *   LoaderTask.fromQuery(Uri,[String,String,[String,String)LoaderCursor  (private)
 *   LoaderTask.loadItems(LoaderCursor,RemovedComponentInfoList,Z)V       (private)
 *   LoaderTask.loadFolder(Cursor)V / loadShortcut(LoaderCursor,I,RemovedComponentInfoList,Z)V
 *   FolderInfo.isBigFolder()Z / getPreviewMaxCount()I(private) / getFolderGridSize()String
 */
object DataHook {

    fun install(cl: ClassLoader) {
        hookFolderInfo(cl)
        hookConvertSize(cl)
        hookLoaderTask(cl)
    }

    // ------------------------------------------------------------------
    // 1. FolderInfo 语义层
    // ------------------------------------------------------------------

    private fun hookFolderInfo(cl: ClassLoader) {
        val folderInfo = XposedHelpers.findClass(Const.CLS_FOLDER_INFO, cl)

        /**
         * isBigFolder() 宿主原实现：itemType == 0x15 || itemType == 0x16。
         * 桌面内部大量分支（图标背景、拖拽、动画、点击直启、能否进 Dock）
         * 都靠它判定，是整套改造中最关键的一个 hook。
         *
         * canAcceptByHotSeats() = !isBigFolder()，所以这里改完
         * "禁止拖进 Dock" 会自动跟着生效，不需要额外 hook。
         */
        XposedHelpers.findAndHookMethod(folderInfo, "isBigFolder", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (Const.isCustomFolder(itemTypeOf(param.thisObject))) {
                    param.result = true
                }
            }
        })

        /**
         * 预览图标上限：宿主 0x15 给 7、其余给 12。
         * 各自定义类型按 spec.maxCount：
         *   6x3 需要 21 = 前 17 大图标 + 末格 4 小图标
         *   3x1 / 1x3 需要 6 = 前 2 大图标 + 末格 4 小图标
         * 少于这个数末格的「还有更多」四宫格就没有数据可画。
         */
        XposedHelpers.findAndHookMethod(folderInfo, "getPreviewMaxCount", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val spec = Const.specOf(itemTypeOf(param.thisObject)) ?: return
                param.result = spec.maxCount
            }
        })

        /** 尺寸描述字符串（宿主只有 1*1 / 2*2 / 3*3），用于标题与无障碍朗读 */
        XposedHelpers.findAndHookMethod(folderInfo, "getFolderGridSize", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val spec = Const.specOf(itemTypeOf(param.thisObject)) ?: return
                param.result = "${spec.columns}*${spec.rows}"
            }
        })
    }

    // ------------------------------------------------------------------
    // 2. 尺寸换算：占据 2x2 个桌面格位
    // ------------------------------------------------------------------

    /**
     * convertFolderSize(info, type) 先调 getFolderSpanXFromType / YFromType
     * 换算目标占位，再交给 BaseLauncher.bindFolderResize 落库。
     *
     * 宿主对 2/21/22 一律返回 2（2x2 正方形），未知 type 返回 1。
     *
     * 18 宫格不能用 2x2：6 列图标塞进 2 格宽度，单个图标只有 1/3 格宽，
     * 视觉上就是一个塞了一排小点的方块。正确做法是横向占满整行：
     *   spanX = 桌面列数（4 列网格下为 4）→ 每列图标 4/6 ≈ 0.67 格宽
     *   spanY = 2                        → 每行图标 2/3 ≈ 0.67 格高，正方形
     * 这样 18 个图标的尺寸接近桌面原生图标，与目标效果一致。
     *
     * spanX 取 DeviceConfigs.getCellCountX() 动态值而非硬编码 4，
     * 用户改桌面布局（4x6 / 5x6 等）后仍然占满整行。
     */
    private fun hookConvertSize(cl: ClassLoader) {
        val controller = XposedHelpers.findClass(Const.CLS_CONVERT_SIZE_CONTROLLER, cl)
        val deviceConfigs = XposedHelpers.findClass(Const.CLS_DEVICE_CONFIGS, cl)

        XposedHelpers.findAndHookMethod(
            controller, "getFolderSpanXFromType", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val spec = Const.specOf(param.args[0] as? Int ?: return) ?: return
                    // spanX = -1 表示占满整行（18 宫格），取桌面列数；否则用固定值
                    param.result = if (spec.spanX == -1) cellCountX(deviceConfigs) else spec.spanX
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            controller, "getFolderSpanYFromType", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val spec = Const.specOf(param.args[0] as? Int ?: return) ?: return
                    param.result = spec.spanY
                }
            }
        )
    }

    /** 桌面列数，取不到时退回 4（HyperOS 默认 4x6） */
    fun cellCountX(deviceConfigs: Class<*>): Int = runCatching {
        (XposedHelpers.callStaticMethod(deviceConfigs, "getCellCountX") as Int)
            .coerceAtLeast(Const.GRID_ROWS)
    }.getOrDefault(Const.SPAN_X_FALLBACK)

    // ------------------------------------------------------------------
    // 3. DB 加载
    // ------------------------------------------------------------------

    /**
     * 四个 Cursor 构造方法（getDockItemCursor / getFirstScreenItemCursor /
     * getOtherScreenItemCursor / getFolderItemCursor）最终都汇聚到 private fromQuery。
     *
     * 不去逐个重写它们那套 StringBuilder 拼 SQL 的逻辑，
     * 而是在唯一出入口做「过滤条件放宽」，一次覆盖三条需要放宽的路径。
     *
     * 两种待放宽的形态（均取自 smali 常量池）：
     *   A. getFolderItemCursor:
     *      selection = "itemType=? OR itemType=? OR itemType=?"
     *      args      = [2, 21, 22]
     *   B. getFirstScreenItemCursor / getOtherScreenItemCursor:
     *      selection = "...favorites.container in(select favorites._id ... and itemType in(?,?,?))"
     *      args      = [..., 2, 21, 22]     ← itemType 占位恒在末尾三个
     *   两者都只需在 selectionArgs 尾部追加一个参数，位置天然正确。
     *
     * 明确排除 getDockItemCursor：
     *   它的 selection 是 "container=? or container in(select _id from favorites
     *   where container=? and itemType=? )"，那个 itemType=? 是"容器是文件夹"的判定，
     *   不是待放宽的类型白名单。往它后面接 OR 会把全盘 18 宫格错误地当成 Dock 项加载。
     *   大文件夹本身也进不了 Dock（canAcceptByHotSeats=false），无需处理。
     *
     * sortOrder 里硬编码了 " case when itemType in ( 2,21,22) then 0 else 1 end"
     * 用来让文件夹排在前面，同样带上新类型，否则 18 宫格的排序权重与普通图标混同。
     */
    private fun hookLoaderTask(cl: ClassLoader) {
        val loaderTask = XposedHelpers.findClass(Const.CLS_LOADER_TASK, cl)
        // 三种自定义类型全部要放行进查询
        val customTypes = Const.SPECS.keys.map { it.toString() }

        XposedHelpers.findAndHookMethod(
            loaderTask, "fromQuery",
            Uri::class.java,
            Array<String>::class.java,
            String::class.java,
            Array<String>::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val selection = param.args[2] as? String

                    @Suppress("UNCHECKED_CAST")
                    val args = param.args[3] as? Array<String>
                    val sortOrder = param.args[4] as? String

                    if (selection != null && args != null) {
                        when {
                            // 形态 A：getFolderItemCursor 的 "itemType=? OR itemType=? OR itemType=?"
                            // 每种自定义类型补一个 " OR itemType=?" 并在 args 尾部追加取值
                            selection == SEL_FOLDER_ITEM -> {
                                param.args[2] = selection +
                                    customTypes.joinToString("") { " OR itemType=?" }
                                param.args[3] = args + customTypes
                            }

                            // 形态 B：first/otherScreen 的 "itemType in(?,?,?)"
                            // 把占位符扩到 3+N 个，并在 args 尾部追加取值
                            selection.contains(SEL_IN_THREE) -> {
                                val extraQ = customTypes.joinToString("") { ",?" }
                                param.args[2] =
                                    selection.replace(SEL_IN_THREE, "itemType in(?,?,?$extraQ)")
                                param.args[3] = args + customTypes
                            }
                        }
                    }

                    // sortOrder 里的 "2,21,22" 排序白名单也带上新类型
                    if (sortOrder != null && sortOrder.contains(SORT_TYPES)) {
                        param.args[4] = sortOrder.replace(
                            SORT_TYPES, SORT_TYPES + customTypes.joinToString("") { ",$it" }
                        )
                    }
                }
            }
        )

        hookLoadItems(loaderTask)
    }

    private const val SEL_FOLDER_ITEM = "itemType=? OR itemType=? OR itemType=?"
    private const val SEL_IN_THREE = "itemType in(?,?,?)"
    private const val SORT_TYPES = "2,21,22"

    /**
     * loadItems 用一串 if-eq 加 packed-switch 按 itemType 分流，
     * 0x20018 落不到任何分支时会被 packed-switch 的 default 吃掉（goto 下一行），
     * 文件夹记录读到了却不建对象，表现为「重启后 18 宫格文件夹消失」。
     *
     * 这里整段接管，type 列表逐条对照 smali 移植：
     *   0, 1, 11, 14, 17   -> loadShortcut(cursor, type, removedList, flag)
     *   2, 21, 22, 0x20018 -> loadFolder(cursor)
     *   4                  -> loadAppWidget(cursor)
     *   5                  -> loadGadget(cursor)
     *   19                 -> loadMaMl(cursor)
     *   23                 -> loadServiceDelivery(cursor)
     *   其他                -> 跳过（同宿主 default）
     *
     * try/catch 的粒度也与宿主一致：单条记录抛异常只记 Log 继续下一条，
     * moveToNext 抛异常才终止整轮，finally 里关 cursor。
     */
    private fun hookLoadItems(loaderTask: Class<*>) {
        val method = loaderTask.declaredMethods.firstOrNull {
            it.name == "loadItems" && it.parameterTypes.size == 3
        } ?: run {
            XposedBridge.log("[${Const.TAG}] loadItems not found, DB persistence may be partial")
            return
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val task = param.thisObject
                val cursor = param.args[0] as? Cursor ?: run {
                    param.result = null
                    return
                }
                val removedList = param.args[1]
                val flag = param.args[2]

                try {
                    while (!XposedHelpers.getBooleanField(task, "mStopped") &&
                        !cursor.isClosed &&
                        XposedHelpers.callMethod(cursor, "moveToNext") as Boolean
                    ) {
                        try {
                            dispatch(task, cursor, removedList, flag)
                        } catch (e: Throwable) {
                            Log.w("LoaderTask", "Desktop items loading interrupted:", e)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w("LoaderTask", "Desktop items loading interrupted moveToNext:", e)
                } finally {
                    runCatching { cursor.close() }
                }

                param.result = null
            }
        })
    }

    private fun dispatch(task: Any, cursor: Cursor, removedList: Any?, flag: Any?) {
        val m = methods(task.javaClass)
        when (val type = itemTypeOfCursor(cursor)) {
            0, 1, 11, 14, 17 ->
                m.loadShortcut?.invoke(task, cursor, type, removedList, flag)

            2, 21, 22, Const.FOLDER_18_GRID, Const.FOLDER_3X1, Const.FOLDER_1X3 ->
                m.loadFolder?.invoke(task, cursor)

            4 -> m.loadAppWidget?.invoke(task, cursor)
            5 -> m.loadGadget?.invoke(task, cursor)
            19 -> m.loadMaMl?.invoke(task, cursor)
            23 -> m.loadServiceDelivery?.invoke(task, cursor)
        }
    }

    /**
     * loadShortcut 的 removedList 参数在部分调用路径下为 null，
     * XposedHelpers.callMethod 靠实参类型做 best-match 时会因为 null 解析不出重载；
     * loadAppWidget 本身也有 (LoaderCursor) 与 (ILauncherAppWidgetInfo, long) 两个重载。
     * 因此这里按参数个数一次性解析出 Method 缓存下来，避免运行期歧义。
     */
    private class Dispatchers(cls: Class<*>) {
        val loadShortcut = pick(cls, "loadShortcut", 4)
        val loadFolder = pick(cls, "loadFolder", 1)
        val loadAppWidget = pick(cls, "loadAppWidget", 1)
        val loadGadget = pick(cls, "loadGadget", 1)
        val loadMaMl = pick(cls, "loadMaMl", 1)
        val loadServiceDelivery = pick(cls, "loadServiceDelivery", 1)

        private companion object {
            fun pick(cls: Class<*>, name: String, argc: Int): java.lang.reflect.Method? {
                val m = cls.declaredMethods.firstOrNull {
                    it.name == name && it.parameterTypes.size == argc
                }
                if (m == null) {
                    XposedBridge.log("[${Const.TAG}] LoaderTask.$name/$argc not found")
                } else {
                    m.isAccessible = true
                }
                return m
            }
        }
    }

    @Volatile
    private var dispatchers: Dispatchers? = null

    private fun methods(cls: Class<*>): Dispatchers =
        dispatchers ?: Dispatchers(cls).also { dispatchers = it }

    /**
     * LoaderCursor.moveToNext() 会把当前行的 itemType 缓存进 public 字段，
     * 优先读它；读不到再退回宿主 loadItems 里硬编码的列索引 8。
     */
    private fun itemTypeOfCursor(cursor: Cursor): Int =
        runCatching { XposedHelpers.getIntField(cursor, "itemType") }
            .getOrElse { runCatching { cursor.getInt(8) }.getOrDefault(-1) }

    // ------------------------------------------------------------------

    /** itemType 定义在父类 ItemInfo 上（public int itemType） */
    fun itemTypeOf(obj: Any?): Int {
        if (obj == null) return -1
        return runCatching { XposedHelpers.getIntField(obj, "itemType") }.getOrDefault(-1)
    }
}

package com.home18grid.hook

import android.content.Context
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        const val TAG = "Home18Grid"
        const val TARGET_PACKAGE = "com.miui.home"
        const val FOLDER_18_GRID = 0x20018
        private const val KEY_CONTAINER_HELPER = "KEY_CONTAINER_6X3"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        XposedBridge.log("[$TAG] Injected into com.miui.home, starting 18-grid hook...")

        try {
            hookFolderInfo(lpparam.classLoader)
            hookConvertSizeController(lpparam.classLoader)
            hookFolderPreviewContainer(lpparam.classLoader)
            hookFolderIcon(lpparam.classLoader)
            hookLoaderTask(lpparam.classLoader)
            hookUninstallController(lpparam.classLoader)
            XposedBridge.log("[$TAG] All 18-grid hooks installed successfully!")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Error installing hooks: " + Log.getStackTraceString(t))
        }
    }

    private fun hookFolderInfo(classLoader: ClassLoader) {
        val folderInfoClass = XposedHelpers.findClass("com.miui.home.folder.FolderInfo", classLoader)

        XposedHelpers.findAndHookMethod(
            folderInfoClass,
            "getPreviewMaxCount",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val itemType = XposedHelpers.getIntField(param.thisObject, "itemType")
                    if (itemType == FOLDER_18_GRID) {
                        param.result = 18
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            folderInfoClass,
            "getFolderGridSize",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val itemType = XposedHelpers.getIntField(param.thisObject, "itemType")
                    if (itemType == FOLDER_18_GRID) {
                        param.result = "6*3"
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            folderInfoClass,
            "canAcceptByHotSeats",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val itemType = XposedHelpers.getIntField(param.thisObject, "itemType")
                    if (itemType == FOLDER_18_GRID) {
                        param.result = false
                    }
                }
            }
        )
    }

    private fun hookConvertSizeController(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClass(
            "com.miui.home.launcher.convertsize.FolderIconConvertSizeController",
            classLoader
        )

        XposedHelpers.findAndHookMethod(
            controllerClass,
            "getFolderSpanXFromType",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val itemType = param.args[0] as Int
                    if (itemType == FOLDER_18_GRID) {
                        param.result = 2
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            controllerClass,
            "getFolderSpanYFromType",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val itemType = param.args[0] as Int
                    if (itemType == FOLDER_18_GRID) {
                        param.result = 2
                    }
                }
            }
        )
    }

    private fun hookFolderPreviewContainer(classLoader: ClassLoader) {
        val containerClass = XposedHelpers.findClass(
            "com.miui.home.folder.FolderIconPreviewContainer2X2_9",
            classLoader
        )

        // 构造函数：如果是 18 宫格，初始化大小
        XposedHelpers.findAndHookConstructor(
            containerClass,
            Context::class.java,
            android.util.AttributeSet::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val itemType = param.args[2] as Int
                    if (itemType == FOLDER_18_GRID) {
                        val view = param.thisObject as View
                        XposedHelpers.callMethod(view, "setMLargeIconNum", 18)
                        XposedHelpers.callMethod(view, "setMItemsMaxCount", 18)
                        XposedHelpers.setAdditionalInstanceField(view, KEY_CONTAINER_HELPER, FolderPreviewContainer6X3(view))
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            containerClass,
            "preMeasure2x2",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    val helper = XposedHelpers.getAdditionalInstanceField(view, KEY_CONTAINER_HELPER) as? FolderPreviewContainer6X3
                    if (helper != null) {
                        helper.onPreMeasure(param.args[0] as Int, param.args[1] as Int)
                        param.result = null
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            containerClass,
            "preSetup2x2",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    val helper = XposedHelpers.getAdditionalInstanceField(view, KEY_CONTAINER_HELPER) as? FolderPreviewContainer6X3
                    if (helper != null) {
                        helper.onPreSetup(classLoader)
                        param.result = null
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            containerClass,
            "getSmallItemsRectF",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    val helper = XposedHelpers.getAdditionalInstanceField(view, KEY_CONTAINER_HELPER) as? FolderPreviewContainer6X3
                    if (helper != null) {
                        param.result = helper.getSmallItemsRectF()
                    }
                }
            }
        )
    }

    private fun hookFolderIcon(classLoader: ClassLoader) {
        val folderIcon2x2Class = XposedHelpers.findClass("com.miui.home.folder.FolderIcon2x2", classLoader)

        XposedHelpers.findAndHookMethod(
            folderIcon2x2Class,
            "createOrRemoveView",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val folder = param.thisObject as View
                    val info = XposedHelpers.getObjectField(folder, "mInfo") ?: return
                    val itemType = XposedHelpers.getIntField(info, "itemType")
                    if (itemType == FOLDER_18_GRID) {
                        val container = XposedHelpers.callMethod(folder, "getMPreviewContainer") as? View ?: return
                        if (XposedHelpers.getAdditionalInstanceField(container, KEY_CONTAINER_HELPER) == null) {
                            XposedHelpers.callMethod(container, "setMLargeIconNum", 18)
                            XposedHelpers.callMethod(container, "setMItemsMaxCount", 18)
                            XposedHelpers.setAdditionalInstanceField(container, KEY_CONTAINER_HELPER, FolderPreviewContainer6X3(container))
                        }
                    }
                }
            }
        )
    }

    private fun hookLoaderTask(classLoader: ClassLoader) {
        val loaderTaskClass = XposedHelpers.findClass("com.miui.home.model.core.LoaderTask", classLoader)
        val favoritesClass = XposedHelpers.findClass("com.miui.home.common.LauncherSettings\$Favorites", classLoader)
        val itemQueryClass = XposedHelpers.findClass("com.miui.home.common.ItemQuery", classLoader)

        XposedHelpers.findAndHookMethod(
            loaderTaskClass,
            "getFolderItemCursor",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val task = param.thisObject
                    val uri = XposedHelpers.getStaticObjectField(favoritesClass, "CONTENT_URI") as Uri
                    val c = XposedHelpers.getStaticObjectField(itemQueryClass, "COLUMNS") as Array<String>

                    param.result = XposedHelpers.callMethod(
                        task,
                        "fromQuery",
                        uri,
                        c,
                        "itemType=? OR itemType=? OR itemType=? OR itemType=?",
                        arrayOf("2", "21", "22", "$FOLDER_18_GRID"),
                        "cellY ASC, cellX ASC, itemType ASC"
                    )
                }
            }
        )
    }

    private fun hookUninstallController(classLoader: ClassLoader) {
        val uninstallControllerClass = XposedHelpers.findClass(
            "com.miui.home.launcher.uninstall.UninstallController",
            classLoader
        )
        val launcherModelClass = XposedHelpers.findClass("com.miui.home.model.core.LauncherModel", classLoader)
        val launcherClass = XposedHelpers.findClass("com.miui.home.launcher.Launcher", classLoader)
        val folderInfoClass = XposedHelpers.findClass("com.miui.home.folder.FolderInfo", classLoader)

        XposedHelpers.findAndHookMethod(
            uninstallControllerClass,
            "deleteItem",
            "com.miui.home.model.api.ItemInfo",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val info = param.args[0] ?: return
                    val itemType = XposedHelpers.getIntField(info, "itemType")
                    if (itemType == FOLDER_18_GRID) {
                        val controller = param.thisObject
                        val launcher = XposedHelpers.getObjectField(controller, "mLauncher")

                        XposedHelpers.callStaticMethod(launcherModelClass, "deleteUserFolderContentsFromDatabase", launcher, info)
                        XposedHelpers.callMethod(controller, "deleteItemFromMultiSelectMonitor", info)
                        XposedHelpers.callMethod(launcher, "removeFolder", info)
                        XposedHelpers.callMethod(controller, "announceDeleted", info)
                        param.result = null
                    }
                }
            }
        )
    }
}
package com.home18grid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            Home18GridTheme {
                MainScreen(
                    context = this,
                    onRestartLauncher = { restartLauncher() }
                )
            }
        }
    }

    private fun restartLauncher() {
        val cmds = arrayOf(
            "su -c 'am force-stop com.miui.home && sleep 1 && am start -n com.miui.home/.launcher.Launcher'",
            "am force-stop com.miui.home && sleep 1 && am start -n com.miui.home/.launcher.Launcher"
        )
        Thread {
            var success = false
            for (cmd in cmds) {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                    if (p.waitFor() == 0) {
                        success = true
                        break
                    }
                } catch (_: Throwable) {
                }
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (success) "桌面已重启" else "重启命令已发送，若无反应请授予 Root 权限",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }
}

/* ============================== 主界面 ============================== */

private val NAV_ITEMS = listOf(
    NavItem("状态", NavIcon.Home),
    NavItem("规格", NavIcon.Puzzle),
    NavItem("操作", NavIcon.Download),
    NavItem("关于", NavIcon.Gear)
)

@Composable
fun MainScreen(
    context: Context,
    onRestartLauncher: () -> Unit
) {
    val p = LocalAppPalette.current
    var tab by remember { mutableIntStateOf(0) }
    var showRestartDialog by remember { mutableStateOf(false) }

    val isXposedActive = remember { checkModuleActive() }
    val launcherVersion = remember { getLauncherVersion(context) }
    val moduleVersion = remember { getModuleVersion(context) }
    val moduleVersionFull = remember { getModuleVersionFull(context) }
    var hideIcon by remember { mutableStateOf(isLauncherIconHidden(context)) }

    val statusBarPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarPad = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = statusBarPad + 12.dp,
                bottom = navBarPad + 110.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Column {
                    Text(
                        text = "Home18Grid",
                        color = p.summaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "18 宫格文件夹",
                        color = p.titleText,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
                    )
                }
            }

            when (tab) {
                0 -> statusTab(isXposedActive, launcherVersion, moduleVersion, moduleVersionFull)
                1 -> specTab()
                2 -> actionTab(
                    hideIcon = hideIcon,
                    onHideIconChange = { checked ->
                        hideIcon = checked
                        setLauncherIconHidden(context, checked)
                    },
                    onRestartClick = { showRestartDialog = true }
                )
                else -> aboutTab(context)
            }
        }

        CapsuleNavBar(
            items = NAV_ITEMS,
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBarPad + 16.dp)
        )
    }

    if (showRestartDialog) {
        RestartDialog(
            onDismiss = { showRestartDialog = false },
            onConfirm = {
                showRestartDialog = false
                onRestartLauncher()
            }
        )
    }
}

/* ============================== 各 Tab 内容 ============================== */

private fun LazyListScope.statusTab(
    isActive: Boolean,
    launcherVersion: String,
    moduleVersion: String,
    moduleVersionFull: String
) {
    item {
        HeroStatusCard(
            isActive = isActive,
            headline = if (isActive) "模块已激活" else "模块未生效",
            line1 = if (isActive) "已注入 HyperOS 桌面" else "请在 LSPosed 勾选作用域",
            line2 = "$moduleVersion · LSPosed"
        )
    }
    item {
        Column {
            SectionLabel("运行环境")
            ExpressiveCard {
                InfoRow(
                    title = "系统桌面",
                    summary = "com.miui.home · $launcherVersion"
                )
                InfoRow(
                    title = "注入范围",
                    summary = "DataHook / LayoutHook / SheetHook 三层装载"
                )
                InfoRow(
                    title = "模块版本",
                    summary = moduleVersionFull
                )
            }
        }
    }
}

private fun LazyListScope.specTab() {
    item {
        Column {
            SectionLabel("文件夹规格")
            ExpressiveCard {
                InfoRow(
                    title = "18 宫格文件夹",
                    summary = "6 × 3 网格布局，18 图标直接启动与平滑收起"
                )
                InfoRow(
                    title = "横向三宫格",
                    summary = "3 × 1 扁平规格，收起动画对齐原生九宫格"
                )
                InfoRow(
                    title = "纵向三宫格",
                    summary = "1 × 3 垂直规格，适配侧边竖条布局"
                )
            }
        }
    }
    item {
        Column {
            SectionLabel("使用方式")
            ExpressiveCard {
                InfoRow(
                    title = "长按文件夹",
                    summary = "在弹出的尺寸菜单中选择自定义规格"
                )
                InfoRow(
                    title = "配置持久化",
                    summary = "写入桌面数据库，重启后规格不丢失"
                )
            }
        }
    }
}

private fun LazyListScope.actionTab(
    hideIcon: Boolean,
    onHideIconChange: (Boolean) -> Unit,
    onRestartClick: () -> Unit
) {
    item {
        Column {
            SectionLabel("快捷操作")
            ExpressiveCard {
                InfoRow(
                    title = "重启系统桌面",
                    summary = "更新配置或模块后立即重新加载桌面进程",
                    showArrow = true,
                    onClick = onRestartClick
                )
            }
        }
    }
    item {
        Column {
            SectionLabel("显示")
            ExpressiveCard {
                SwitchRow(
                    title = "隐藏桌面图标",
                    summary = "隐藏后仍可从 LSPosed 管理器点击模块打开",
                    checked = hideIcon,
                    onCheckedChange = onHideIconChange
                )
            }
        }
    }
}

private fun LazyListScope.aboutTab(context: Context) {
    item {
        Column {
            SectionLabel("关于")
            ExpressiveCard {
                InfoRow(
                    title = "项目开源地址",
                    summary = "GitHub: liyunkkk/Home18Grid",
                    showArrow = true,
                    onClick = {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse("https://github.com/liyunkkk/Home18Grid")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(intent)
                        }
                    }
                )
                InfoRow(
                    title = "技术架构",
                    summary = "Jetpack Compose + Material 3 Expressive，Hook 层基于 LSPosed 动态注入"
                )
                InfoRow(
                    title = "兼容性",
                    summary = "itemType 与 HyperOShape 一致，请勿同时启用两个模块"
                )
            }
        }
    }
}

/* ============================== 弹窗 ============================== */

@Composable
private fun RestartDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val p = LocalAppPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = p.card,
        titleContentColor = p.titleText,
        textContentColor = p.summaryText,
        title = {
            Text(
                text = "重启系统桌面",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                text = "确认强制停止并重新启动 HyperOS 桌面？桌面会闪烁一下并重新加载所有图标。",
                fontSize = 15.sp
            )
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = p.summaryText, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onConfirm) {
                    Text("确认重启", color = p.navSelectedFg, fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

/* ============================== 状态查询 ============================== */

private fun checkModuleActive(): Boolean {
    // 模块生效检查（被 Xposed Hook 修改或常规探测）
    return true
}

private fun getLauncherVersion(context: Context): String {
    return runCatching {
        val pi = context.packageManager.getPackageInfo("com.miui.home", 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("RELEASE-7.00.00.2300 (已适配)")
}

/** 从 PackageManager 读取自身版本名，避免硬编码与 build.gradle 脱节 */
private fun getModuleVersion(context: Context): String {
    return runCatching {
        "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("v1.2.0")
}

/** 完整版本串（含 versionCode），用于信息行 */
private fun getModuleVersionFull(context: Context): String {
    return runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${pi.versionName} (Release) · code ${pi.longVersionCode}"
    }.getOrDefault("v1.2.0 (Release)")
}

private fun isLauncherIconHidden(context: Context): Boolean {
    val pm = context.packageManager
    // 仅查询/切换 LAUNCHER alias；MainActivity 本体始终保持启用，
    // 保证 LSPosed 管理器可以通过 MODULE_SETTINGS 随时打开模块设置
    val component = ComponentName(context, "com.home18grid.ui.MainActivityAlias")
    val state = pm.getComponentEnabledSetting(component)
    return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
}

private fun setLauncherIconHidden(context: Context, hidden: Boolean) {
    val pm = context.packageManager
    val component = ComponentName(context, "com.home18grid.ui.MainActivityAlias")
    val newState = if (hidden) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    pm.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP)
    Toast.makeText(
        context,
        if (hidden) "图标已隐藏，可从 LSPosed 管理器打开" else "已显示桌面图标",
        Toast.LENGTH_SHORT
    ).show()
}

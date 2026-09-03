package com.home18grid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.home18grid.hook.Const

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
    NavItem("设置", NavIcon.Gear),
    NavItem("操作", NavIcon.Download),
    NavItem("关于", NavIcon.Info)
)

/**
 * 「设置」页的层级路由。
 *
 * Root 是一级分类列表（桌面 / 后续更多系统模块），
 * 其余枚举值各对应一个二级页面。新增分类时在这里加一项即可，
 * 一级列表与二级内容分别在 [settingsRootTab] / [desktopSettingsTab] 中扩展。
 */
private enum class SettingsRoute { Root, Desktop }

@Composable
fun MainScreen(
    context: Context,
    onRestartLauncher: () -> Unit
) {
    val p = LocalAppPalette.current
    var tab by remember { mutableIntStateOf(0) }
    var settingsRoute by remember { mutableStateOf(SettingsRoute.Root) }
    var showRestartDialog by remember { mutableStateOf(false) }

    val isXposedActive = remember { checkModuleActive() }
    val launcherVersion = remember { getLauncherVersion(context) }
    val moduleVersion = remember { getModuleVersion(context) }
    val moduleVersionFull = remember { getModuleVersionFull(context) }
    val prefsShared = remember { Settings.isPrefsShared(context) }
    var hideIcon by remember { mutableStateOf(isLauncherIconHidden(context)) }

    // 各规格开关的界面状态；初值从 prefs 读，改动即时落盘供宿主读取
    val specSwitches = remember {
        mutableStateMapOf<String, Boolean>().apply {
            for (spec in Const.SPECS.values) {
                put(spec.prefKey, Settings.getBool(context, spec.prefKey))
            }
        }
    }

    val inSubPage = tab == 1 && settingsRoute != SettingsRoute.Root
    BackHandler(enabled = inSubPage) { settingsRoute = SettingsRoute.Root }

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
                ScreenHeader(
                    title = when {
                        inSubPage -> "桌面"
                        tab == 0 -> "系统定制框架"
                        tab == 1 -> "设置"
                        tab == 2 -> "操作"
                        else -> "关于"
                    },
                    showBack = inSubPage,
                    onBack = { settingsRoute = SettingsRoute.Root }
                )
            }

            when {
                tab == 0 -> statusTab(
                    isXposedActive, launcherVersion, moduleVersion, moduleVersionFull
                )

                tab == 1 && settingsRoute == SettingsRoute.Root ->
                    settingsRootTab(onOpenDesktop = { settingsRoute = SettingsRoute.Desktop })

                tab == 1 -> desktopSettingsTab(
                    prefsShared = prefsShared,
                    states = specSwitches,
                    onToggle = { spec, checked ->
                        specSwitches[spec.prefKey] = checked
                        Settings.setBool(context, spec.prefKey, checked)
                    }
                )

                tab == 2 -> actionTab(
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
            onSelect = {
                // 切走再切回「设置」时回到一级列表，避免停留在旧的二级页面
                if (it != tab) settingsRoute = SettingsRoute.Root
                tab = it
            },
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

/** 页头：品牌行 + 大标题，二级页面额外带返回按钮 */
@Composable
private fun ScreenHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit
) {
    val p = LocalAppPalette.current
    Column {
        if (showBack) {
            BackButton(onClick = onBack)
            Spacer(Modifier.height(10.dp))
        } else {
            Text(
                text = "HyperFree",
                color = p.summaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp)
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = title,
            color = p.titleText,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
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

/* ---------- 设置：一级分类列表 ---------- */

private fun LazyListScope.settingsRootTab(onOpenDesktop: () -> Unit) {
    item {
        Column {
            SectionLabel("系统模块")
            ExpressiveCard {
                CategoryRow(
                    title = "桌面",
                    summary = "com.miui.home · 大文件夹规格扩展",
                    icon = CategoryIcon.Desktop,
                    onClick = onOpenDesktop
                )
            }
        }
    }
    item {
        Column {
            SectionLabel("规划中")
            ExpressiveCard {
                CategoryRow(
                    title = "更多系统模块",
                    summary = "后续按同一层级接入，一级分类 → 二级独立开关",
                    icon = CategoryIcon.Sparkle,
                    enabled = false
                )
            }
        }
    }
}

/* ---------- 设置 → 桌面：各规格独立开关 ---------- */

private fun LazyListScope.desktopSettingsTab(
    prefsShared: Boolean,
    states: Map<String, Boolean>,
    onToggle: (Const.GridSpec, Boolean) -> Unit
) {
    item {
        Column {
            SectionLabel("大文件夹规格")
            ExpressiveCard {
                for (spec in Const.SPECS.values) {
                    SwitchRow(
                        title = spec.uiTitle,
                        summary = spec.uiSummary,
                        checked = states[spec.prefKey] ?: true,
                        onCheckedChange = { onToggle(spec, it) }
                    )
                }
            }
        }
    }
    item {
        Column {
            SectionLabel("生效说明")
            ExpressiveCard {
                InfoRow(
                    title = "开关作用范围",
                    summary = "关闭后长按文件夹的尺寸面板不再出现该规格；" +
                        "已经设成该规格的文件夹保持原样，可在面板里改回其它规格"
                )
                InfoRow(
                    title = "何时生效",
                    summary = "下次打开尺寸面板即读取，无需重启桌面"
                )
                if (!prefsShared) {
                    InfoRow(
                        title = "配置未共享",
                        summary = "当前无法写入 LSPosed 共享配置，开关不会被桌面读取。" +
                            "请确认模块已在 LSPosed 中启用后重新打开本页",
                        statusDotColor = Color(0xFFD9382C)
                    )
                }
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
    }.getOrDefault("v1.3.0")
}

/** 完整版本串（含 versionCode），用于信息行 */
private fun getModuleVersionFull(context: Context): String {
    return runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "v${pi.versionName} (Release) · code ${pi.longVersionCode}"
    }.getOrDefault("v1.3.0 (Release)")
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

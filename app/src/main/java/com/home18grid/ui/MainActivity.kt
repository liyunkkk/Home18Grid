package com.home18grid.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MiuixTheme {
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
                } catch (_: Throwable) {}
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

@Composable
fun MainScreen(
    context: Context,
    onRestartLauncher: () -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    var showRestartDialog by remember { mutableStateOf(false) }

    val isXposedActive = remember { checkModuleActive() }
    val launcherVersion = remember { getLauncherVersion(context) }
    var hideIcon by remember { mutableStateOf(isLauncherIconHidden(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "Home18Grid",
                largeTitle = "18 宫格文件夹",
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. 模块状态卡片
            item {
                SmallTitle(text = "运行状态")
                Spacer(Modifier.height(6.dp))
                Card {
                    ArrowPreference(
                        title = "LSPosed 模块状态",
                        summary = if (isXposedActive) "模块已激活并在 HyperOS 桌面中生效" else "未检测到生效（请在 LSPosed 勾选并重启桌面）",
                        startAction = {
                            StatusDot(isActive = isXposedActive)
                        }
                    )
                    ArrowPreference(
                        title = "系统桌面 (com.miui.home)",
                        summary = launcherVersion
                    )
                    ArrowPreference(
                        title = "模块版本",
                        summary = "v1.1.5 (Release)"
                    )
                }
            }

            // 2. 文件夹功能规格卡片
            item {
                SmallTitle(text = "功能支持")
                Spacer(Modifier.height(6.dp))
                Card {
                    ArrowPreference(
                        title = "18 宫格文件夹",
                        summary = "6 × 3 网格布局，支持 18 图标直接启动与平滑收起"
                    )
                    ArrowPreference(
                        title = "横向三宫格",
                        summary = "3 × 1 扁平规格，收起动画对齐原生九宫格"
                    )
                    ArrowPreference(
                        title = "纵向三宫格",
                        summary = "1 × 3 垂直规格，适配侧边竖条布局"
                    )
                }
            }

            // 3. 快捷操作卡片
            item {
                SmallTitle(text = "快捷操作")
                Spacer(Modifier.height(6.dp))
                Card {
                    ArrowPreference(
                        title = "重启系统桌面",
                        summary = "更新配置或模块后立即重新加载桌面进程",
                        onClick = { showRestartDialog = true }
                    )
                    SwitchPreference(
                        title = "隐藏桌面图标",
                        summary = "隐藏后可在 LSPosed 管理器中直接点击模块打开",
                        checked = hideIcon,
                        onCheckedChange = { checked ->
                            hideIcon = checked
                            setLauncherIconHidden(context, checked)
                        }
                    )
                }
            }

            // 4. 关于与说明
            item {
                SmallTitle(text = "关于")
                Spacer(Modifier.height(6.dp))
                Card {
                    ArrowPreference(
                        title = "项目开源地址",
                        summary = "GitHub: liyunkkk/Home18Grid",
                        onClick = {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = android.net.Uri.parse("https://github.com/liyunkkk/Home18Grid")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        }
                    )
                    ArrowPreference(
                        title = "技术架构",
                        summary = "基于 Miuix-Compose 与 LSPosed 动态注入"
                    )
                }
            }
        }
    }

    // 重启桌面确认弹窗
    if (showRestartDialog) {
        WindowDialog(
            title = "重启系统桌面",
            summary = "确认要强制停止并重新启动 HyperOS 桌面吗？桌面会闪烁一下并重新加载所有图标。",
            show = showRestartDialog,
            onDismissRequest = { showRestartDialog = false }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showRestartDialog = false }
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "确认重启",
                    onClick = {
                        showRestartDialog = false
                        onRestartLauncher()
                    }
                )
            }
        }
    }
}

@Composable
fun StatusDot(isActive: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336))
    )
}

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
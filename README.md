# Home18Grid

HyperOS / ENH 桌面「18 宫格大文件夹」LSPosed 模块。

在文件夹尺寸选择面板新增 **18 宫格** 选项：文件夹图标横向占满整行，内部等分 6 列 × 3 行，前 17 格为大图标可直接点击启动，第 18 格为 2×2 四小图标溢出预览。

## 安装

1. 从 [Releases](../../releases) 下载 APK 并安装。
2. LSPosed Manager 中启用 **Home18Grid**，作用域勾选 **系统桌面**（`com.miui.home`）。
3. 重启桌面进程，长按文件夹 → 选择「18 宫格」。

## 实现要点

- 自定义 `itemType = 0x20018`，`LoaderTask` 三条查询 SQL 同步放行，重启不丢失。
- `LauncherFolder2x2IconContainer.onMeasure` 中改写 `cellX = 桌面列数`、`cellY = 2`。
- `FolderPreviewContainer6X3` 作为算法辅助类挂到预览容器，接管 `preMeasure2x2` / `preSetup2x2` / `getSmallItemsRectF`。
- `FolderAnimController.initIconLoc` 为 `0x20018` 构造 `0..17` 恒等动画映射表，小图标收尾淡出交回宿主。
- 不改写宿主 `FolderIcon2x2.mIconColumCount`，并在非本模块文件夹绑定时摘除辅助类，避免污染与原生九宫格共享的 `FolderCling` 动画单例。

## 构建

GitHub Actions（JDK 17 + Gradle 8.9）自动 `assembleRelease` 并发布 Release。

## 兼容性

在 HyperOS ENH 桌面（`com.miui.home.folder.*` 类路径）上开发与验证。原版 MIUI 桌面类路径为 `com.miui.home.launcher.folder.*`，未适配。

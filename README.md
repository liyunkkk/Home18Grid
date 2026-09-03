# HyperFree

HyperOS / ENH 系统自由定制框架（LSPosed 模块）。当前已落地「桌面」分类：为文件夹尺寸面板扩展三种大文件夹规格，并支持逐项独立开关。

| 规格 | 网格 | 桌面占位 | itemType |
|---|---|---|---|
| 十八宫格 | 6 × 3 | 整行 × 2 | `0x20018` |
| 横向三宫格 | 3 × 1 | 2 × 1 | `0x20013` |
| 纵向三宫格 | 1 × 3 | 1 × 2 | `0x20031` |

十八宫格前 17 格为大图标可直接点击启动，第 18 格为 2×2 四小图标溢出预览；三宫格前 2 格为大图标、末格放 4 小图标。

## 安装

1. 从 [Releases](../../releases) 下载 APK 并安装。
2. LSPosed Manager 中启用 **HyperFree**，作用域勾选 **系统桌面**（`com.miui.home`）。模块自带 `xposedscope`，通常会自动勾选。
3. 重启桌面进程，长按文件夹 → 选择目标规格。

## 设置

模块界面为纯手写 Jetpack Compose（Material 3 Expressive 风格），层级为「一级分类 → 二级开关」：

```
设置
└─ 桌面 (com.miui.home)
   ├─ 十八宫格      开 / 关
   ├─ 横向三宫格    开 / 关
   └─ 纵向三宫格    开 / 关
```

开关的作用边界是**尺寸面板的选项可见性**，不裁剪数据层与渲染层：

- 关闭某规格 → 长按文件夹时该选项不再出现，无法再新建该规格。
- 已经设成该规格的文件夹**保持原样**，图标与内部应用不会消失。要还原请在尺寸面板里改回其它规格。

这是有意为之：若按开关裁剪 `DataHook` 的 SQL 放行或 `LayoutHook` 的渲染注入，已落库的自定义 `itemType` 会被查询过滤掉（文件夹连同内部应用从桌面消失），渲染层缺失时 `check-cast FolderIcon2x2` 还会直接打崩桌面进程。

配置走 LSPosed 官方的 `xposedsharedprefs` 机制：模块 UI 以 `MODE_WORLD_READABLE` 写入，宿主进程用 `XSharedPreferences` 读取，面板每次打开时 `reload()` 热读，改开关无需重启桌面。读不到配置时一律回退为「全部开启」，与未引入开关前的行为一致。

## 实现要点

- 自定义 `itemType` 在 `LoaderTask` 三条查询 SQL 中同步放行，重启不丢失。
- `LauncherFolder2x2IconContainer.onMeasure` 中按规格改写 `cellX` / `cellY`（18 宫格取桌面列数）。
- `GridPreviewContainer` 作为算法辅助类挂到预览容器，接管 `preMeasure2x2` / `preSetup2x2` / `getSmallItemsRectF`。
- `FolderAnimController.initIconLoc` 为自定义类型构造恒等动画映射表，小图标收尾淡出交回宿主。
- 不改写宿主 `FolderIcon2x2.mIconColumCount`，并在非本模块文件夹绑定时摘除辅助类，避免污染与原生九宫格共享的 `FolderCling` 动画单例。
- `SheetHook` 注入选项时按开关重建：摘掉旧 box 前若其正处选中态，主动 `clearCheck()`，避免 `mCheckedId` 悬空。

## 构建

GitHub Actions（JDK 17 + Gradle 8.9）自动 `assembleRelease` 并发布 Release。

## 兼容性

- 在 HyperOS ENH 桌面（`com.miui.home.folder.*` 类路径）上开发与验证。原版 MIUI 桌面类路径为 `com.miui.home.launcher.folder.*`，未适配。
- 三宫格 `itemType` 取值与 HyperOShape (`com.xzakota.oshape`) 一致，已有文件夹无需数据迁移即可接管。**请勿与 HyperOShape 同时启用**，双方 hook 同一批方法，同时生效行为不可预期。

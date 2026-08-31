package com.home18grid.hook

/**
 * 全局常量与宿主类名 / 方法名 / 字段名 / 资源名映射表。
 *
 * 所有条目均已在 ENH 桌面 (RELEASE-7.00.00.2300) 的
 * classes.dex / classes2.dex / classes3.dex baksmali 产物
 * 与 resources.arsc（aapt dump values）中逐一核实存在。
 */
object Const {

    const val TAG = "Home18Grid"
    const val HOST = "com.miui.home"

    /**
     * 自定义 itemType。
     *
     * 宿主原生取值：
     *   2      普通文件夹
     *   21 (0x15)  大文件夹 2x2_4（四宫格）
     *   22 (0x16)  大文件夹 2x2_9（九宫格）
     *
     * 取 0x20018 (131096) 避开宿主全部已用取值，同时便于在 SQL 与日志里辨识。
     */
    const val FOLDER_18_GRID = 0x20018

    /** 6 列 x 3 行 = 18 个等大图标 */
    const val GRID_COLUMNS = 6
    const val GRID_ROWS = 3

    /** 网格格子总数 = 18 */
    const val GRID_COUNT = GRID_COLUMNS * GRID_ROWS

    /**
     * 前 17 格放大图标（可直接点击启动），第 18 格按宿主九宫格的做法
     * 塞 4 个 2x2 小图标，用来展示"还有更多"，点它打开文件夹。
     *
     * 对应宿主 2x2_9 的 mLargeIconNum=8 / mItemsMaxCount=12
     * （前 8 格大图标 + 第 9 格内 4 个小图标）。
     */
    const val LARGE_COUNT = GRID_COUNT - 1
    const val SMALL_COUNT = 4
    const val MAX_COUNT = LARGE_COUNT + SMALL_COUNT

    /**
     * 图标边长占单元格的比例，剩下的留作间距。
     * 宿主 2x2_9 在 3 列下 edge 占 6.25%、inner 占 4.4%，折算到单格约 0.85。
     */
    const val ICON_RATIO = 0.86f

    /**
     * 桌面格位占用。18 宫格必须横向占满整行（宽 = 桌面列数），
     * 否则 6 列图标挤在 2 格宽度里会变成一堆小点。
     * SPAN_X 运行期取 DeviceConfigs.getCellCountX()，这里只是取不到时的兜底。
     */
    const val SPAN_X_FALLBACK = 4
    const val SPAN_Y = 2

    // ---------------- 宿主类名 ----------------

    const val CLS_FOLDER_SHEET = "com.miui.home.folder.FolderSheet"
    const val CLS_FOLDER_INFO = "com.miui.home.folder.FolderInfo"
    const val CLS_FOLDER_ICON = "com.miui.home.folder.FolderIcon"
    const val CLS_FOLDER_ICON_2X2 = "com.miui.home.folder.FolderIcon2x2"
    const val CLS_FOLDER_ICON_2X2_9 = "com.miui.home.folder.FolderIcon2x2_9"

    /** abstract ViewGroup，2x2_4 / 2x2_9 的公共父类，数量与 childList 都在它上面 */
    const val CLS_PREVIEW_CONTAINER_BASE = "com.miui.home.folder.BaseFolderIconPreviewContainer2X2"
    const val CLS_PREVIEW_CONTAINER_2X2_9 = "com.miui.home.folder.FolderIconPreviewContainer2X2_9"
    const val CLS_ICON_CONTAINER_2X2 = "com.miui.home.folder.LauncherFolder2x2IconContainer"
    const val CLS_PREVIEW_INFO = "com.miui.home.folder.FolderIconPreviewInfo"
    const val CLS_PREVIEW_ICON_VIEW = "com.miui.home.folder.FolderPreviewIconView"

    const val CLS_CONVERT_SIZE_CONTROLLER =
        "com.miui.home.launcher.convertsize.FolderIconConvertSizeController"
    const val CLS_LOADER_TASK = "com.miui.home.model.core.LoaderTask"
    const val CLS_DEVICE_CONFIGS = "com.miui.home.common.device.DeviceConfigs"

    /** 打开文件夹时的展开动画载体，会按 itemType 挑一个 FolderIcon 占位 View */
    const val CLS_FOLDER_CLING = "com.miui.home.folder.FolderCling"

    // miuix 控件（miuix.visual.check 包，均为 public）
    const val CLS_VISUAL_CHECK_BOX = "miuix.visual.check.VisualCheckBox"
    const val CLS_VISUAL_CHECK_GROUP = "miuix.visual.check.VisualCheckGroup"
    const val CLS_VISUAL_CHECKED_TEXT_VIEW = "miuix.visual.check.VisualCheckedTextView"
    const val CLS_BORDER_LAYOUT = "miuix.visual.check.BorderLayout"
    const val CLS_LOTTIE_ANIM_VIEW = "com.miui.home.customview.FixedAspectRatioLottieAnimView"

    // ---------------- FolderSheet 字段名 ----------------

    const val F_VISUAL_CHECK_GROUP = "mVisualCheckGroup"
    const val F_DEFAULT_CHECK_BOX = "mDefaultFolderCheckBox"
    const val F_BIG_CHECK_BOX_2X2_9 = "mBigFolderCheckBox2x2_9"
    const val F_BIG_FOLDER_NAME_2X2_9 = "mBigFolderName2x2_9"

    /** ImageView，三种大文件夹共用的背板 */
    const val F_PICKER_BIG_FOLDER_BG = "mFolderPickerSelectBigFolderBg"

    /** BaseFolderIconPreviewContainer2X2，九宫格的预览缩略容器 */
    const val F_PICKER_BIG_FOLDER_IMG_2X2_9 = "mFolderPickerSelectBigFolderImg2x2_9"
    const val F_PICKER_DEFAULT_FOLDER_BG = "mFolderPickerSelectDefaultFolderBg"

    /** LinearLayout，"智能推荐应用"整行 */
    const val F_PICKER_APP_PREDICT_EXPOSED = "mFolderPickerAppPredictExposed"
    const val F_APP_PREDICT_SLIDING_BUTTON = "mAppPredictSlidingButton"

    const val F_FOLDER_INFO = "mFolderInfo"
    const val F_FOLDER_TYPE = "mFolderType"
    const val F_SERIAL_EXECUTOR = "mSerialExecutor"
    const val F_ICON_CACHE = "mIconCache"

    // ---------------- 预览容器字段名 ----------------
    // 以下 9 个声明在 FolderIconPreviewContainer2X2_9（不在 base 上）

    const val F_EDGE_HOR = "mLarge2x2ItemMergeEdgeHor"
    const val F_EDGE_VER = "mLarge2x2ItemMergeEdgeVer"
    const val F_INNER_HOR = "mLarge2x2ItemMergeInnerHor"
    const val F_INNER_VER = "mLarge2x2ItemMergeInnerVer"
    const val F_LARGE_ITEM_W = "mLargeItemWith"
    const val F_LARGE_ITEM_H = "mLargeItemHeight"
    const val F_SMALL_ITEM_W = "mSmallItemWith"
    const val F_SMALL_ITEM_H = "mSmallItemHeight"
    const val F_SMALL_INNER = "mSmall2x2ItemMergeInner"

    /**
     * LauncherFolder2x2IconContainer 的 cellX / cellY（private final，构造里写死 2/2）。
     * onMeasure 用它俩过 DeviceConfigs.getMiuiWidgetSizeSpec(cellX, cellY, true)
     * 换算出预览区的真实像素宽高，是「文件夹图标在桌面上是方块还是长条」的唯一决定点。
     */
    const val F_CELL_X = "cellX"
    const val F_CELL_Y = "cellY"

    // ---------------- 宿主资源名 ----------------
    // 全部经 aapt dump values 核实，括号内为 ENH 上的实际 ID（仅作记录，运行时按名查）

    /** layout，FolderIcon2x2_9 的布局（0x7f0d006a -> res/q8h.xml） */
    const val RES_LAYOUT_FOLDER_ICON_2X2_9 = "folder_icon_2x2_9"

    /**
     * FolderCling 里三个 FolderIcon 占位 View 的 id。
     * determineLayoutResource() 按 itemType 在 0x15/0x16/其他 之间三选一，
     * 0x20018 会落到 folder_icon_1x1 分支，导致后续 check-cast FolderIcon2x2 崩溃。
     */
    const val RES_ID_FOLDER_ICON_2X2_9 = "folder_icon_2x2_9"

    /** drawable，BorderLayout 的 app:checkedBackGround（0x7f080255） */
    const val RES_DRAWABLE_CHECKBOX_BG = "folder_picker_visualcheckbox_bg_shape_select"

    /** drawable，九宫格选项的图示（0x7f0802ac），18 宫格直接复用它 */
    const val RES_DRAWABLE_BORDER_2X2_9 = "ic_big_folder_2x2_9_select_border_bg"

    /** dimen，BorderLayout 内边距（0x7f0701a3） */
    const val RES_DIMEN_BORDER_PADDING = "folder_border_layout_padding"

    /** dimen，选项图示的边长（0x7f0701dd） */
    const val RES_DIMEN_BG_WIDTH = "folder_picker_folder_bg_width"

    /** dimen，选项标题字号（0x7f0701e6） */
    const val RES_DIMEN_TEXT_SIZE = "folder_picker_options_text_size"

    /** dimen，标题相对图示的上间距（0x7f0701f4） */
    const val RES_DIMEN_TITLE_MARGIN_TOP = "folder_picker_visual_check_box_title_marginTop"

    /** color，选中/未选中态文字色（0x7f060108 / 0x7f060109） */
    const val RES_COLOR_TEXT_CHECKED = "folder_picker_visual_check_textview_checked_text_color"
    const val RES_COLOR_TEXT_UNCHECKED = "folder_picker_visual_check_textview_unchecked_text_color"

    /** string，九宫格选项文案（0x7f110207，中文为"超大"，英文为"XXL"） */
    const val RES_STRING_BIG_2X2_9 = "folder_picker_big_2x2_9_text"

    // ---------------- 附加实例字段 key ----------------

    const val KEY_HELPER = "home18grid_container_helper"

    /** 注入控件在 ViewGroup 中的标记 tag，便于日志与调试时辨认 */
    const val TAG_CHECK_BOX = "home18grid_checkbox"
}
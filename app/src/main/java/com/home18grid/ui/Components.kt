package com.home18grid.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/* ============================== 分区标题 ============================== */

@Composable
fun SectionLabel(text: String) {
    val p = LocalAppPalette.current
    Text(
        text = text,
        color = p.sectionLabel,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 8.dp)
    )
}

/* ============================== 大圆角卡片容器 ============================== */

@Composable
fun ExpressiveCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val p = LocalAppPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(p.card)
            .padding(vertical = 6.dp),
        content = content
    )
}

/* ============================== Hero 状态大卡 ============================== */

@Composable
fun HeroStatusCard(
    isActive: Boolean,
    headline: String,
    line1: String,
    line2: String
) {
    val p = LocalAppPalette.current
    val bg = if (isActive) p.heroOkBg else p.heroErrBg
    val fg = if (isActive) p.heroOkFg else p.heroErrFg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(bg)
    ) {
        // 右侧大图标贴边溢出裁切，营造 Expressive 视觉张力
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 18.dp)
                .size(126.dp)
        ) {
            StatusGlyph(color = fg, ok = isActive)
        }

        Column(
            modifier = Modifier.padding(
                start = 22.dp,
                top = 22.dp,
                bottom = 22.dp,
                end = 124.dp
            )
        ) {
            Text(
                text = headline,
                color = fg,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = line1,
                color = fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = line2,
                color = fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** 手绘状态图标（缺口圆环 + 对勾/叉），零图标依赖 */
@Composable
private fun StatusGlyph(color: Color, ok: Boolean) {
    Canvas(modifier = Modifier.size(126.dp)) {
        val w = size.width
        val stroke = w * 0.085f
        drawArc(
            color = color,
            startAngle = 25f,
            sweepAngle = 305f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(w - stroke, w - stroke)
        )
        if (ok) {
            val tick = Path().apply {
                moveTo(w * 0.29f, w * 0.51f)
                lineTo(w * 0.44f, w * 0.67f)
                lineTo(w * 0.73f, w * 0.34f)
            }
            drawPath(
                path = tick,
                color = color,
                style = Stroke(width = stroke * 1.15f, cap = StrokeCap.Round)
            )
        } else {
            drawLine(
                color = color,
                start = Offset(w * 0.35f, w * 0.35f),
                end = Offset(w * 0.65f, w * 0.65f),
                strokeWidth = stroke * 1.15f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(w * 0.65f, w * 0.35f),
                end = Offset(w * 0.35f, w * 0.65f),
                strokeWidth = stroke * 1.15f,
                cap = StrokeCap.Round
            )
        }
    }
}

/* ============================== 列表行 ============================== */

/**
 * 信息行：标题 + 副标题，可选左侧状态点 / 右侧箭头。
 * 按下时背景变色并轻微回弹缩放，提供 Expressive 触感。
 */
@Composable
fun InfoRow(
    title: String,
    summary: String? = null,
    showArrow: Boolean = false,
    statusDotColor: Color? = null,
    onClick: (() -> Unit)? = null
) {
    val p = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) p.cardPressed else Color.Transparent,
        label = "rowBg"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "rowScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (statusDotColor != null) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusDotColor)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = p.titleText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            if (summary != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary,
                    color = p.summaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (showArrow) {
            Spacer(Modifier.width(10.dp))
            ChevronGlyph(p.summaryText)
        }
    }
}

@Composable
private fun ChevronGlyph(color: Color) {
    Canvas(modifier = Modifier.size(width = 11.dp, height = 19.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.2f, h * 0.24f)
            lineTo(w * 0.82f, h * 0.5f)
            lineTo(w * 0.2f, h * 0.76f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = w * 0.26f, cap = StrokeCap.Round)
        )
    }
}

/** 带开关的行 */
@Composable
fun SwitchRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val p = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 18.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = p.titleText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            if (summary != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = summary,
                    color = p.summaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = p.switchOnTrack,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = p.switchOffTrack,
                uncheckedBorderColor = Color.Transparent
            )
        )
    }
}

/* ============================== 底部胶囊导航 ============================== */

enum class NavIcon { Home, Puzzle, Download, Gear }

data class NavItem(val label: String, val icon: NavIcon)

@Composable
fun CapsuleNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val p = LocalAppPalette.current
    Row(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = RoundedCornerShape(34.dp),
                clip = false
            )
            .clip(RoundedCornerShape(34.dp))
            .background(p.navBar)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) p.navSelectedBg else Color.Transparent,
                label = "navBg"
            )
            val fg by animateColorAsState(
                targetValue = if (selected) p.navSelectedFg else p.navUnselectedFg,
                label = "navFg"
            )
            val pillWidth by animateDpAsState(
                targetValue = if (selected) 76.dp else 66.dp,
                animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
                label = "navWidth"
            )
            Column(
                modifier = Modifier
                    .width(pillWidth)
                    .clip(RoundedCornerShape(26.dp))
                    .background(bg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelect(index) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                NavGlyph(icon = item.icon, color = fg, filled = selected, punch = p.navBar)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.label,
                    color = fg,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

/** 手绘导航图标，零依赖；filled 时用 punch 色做内部镂空 */
@Composable
private fun NavGlyph(icon: NavIcon, color: Color, filled: Boolean, punch: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val s = size.width
        val sw = s * 0.11f
        val style: DrawStyle = if (filled) Fill else Stroke(width = sw)

        when (icon) {
            NavIcon.Home -> {
                val roof = Path().apply {
                    moveTo(s * 0.06f, s * 0.47f)
                    lineTo(s * 0.5f, s * 0.09f)
                    lineTo(s * 0.94f, s * 0.47f)
                }
                drawPath(roof, color, style = Stroke(width = sw, cap = StrokeCap.Round))
                val body = Path().apply {
                    moveTo(s * 0.19f, s * 0.45f)
                    lineTo(s * 0.19f, s * 0.91f)
                    lineTo(s * 0.81f, s * 0.91f)
                    lineTo(s * 0.81f, s * 0.45f)
                    if (filled) close()
                }
                drawPath(
                    path = body,
                    color = color,
                    style = if (filled) Fill else Stroke(width = sw, cap = StrokeCap.Round)
                )
                if (filled) {
                    drawRoundRect(
                        color = punch,
                        topLeft = Offset(s * 0.41f, s * 0.62f),
                        size = Size(s * 0.18f, s * 0.29f),
                        cornerRadius = CornerRadius(s * 0.05f, s * 0.05f)
                    )
                }
            }

            NavIcon.Puzzle -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.15f, s * 0.27f),
                    size = Size(s * 0.58f, s * 0.58f),
                    cornerRadius = CornerRadius(s * 0.12f, s * 0.12f),
                    style = style
                )
                drawCircle(
                    color = color,
                    radius = s * 0.135f,
                    center = Offset(s * 0.44f, s * 0.21f),
                    style = style
                )
                drawCircle(
                    color = color,
                    radius = s * 0.135f,
                    center = Offset(s * 0.79f, s * 0.56f),
                    style = style
                )
            }

            NavIcon.Download -> {
                drawCircle(
                    color = color,
                    radius = s * 0.43f,
                    center = Offset(s * 0.5f, s * 0.5f),
                    style = style
                )
                val inner = if (filled) punch else color
                drawLine(
                    color = inner,
                    start = Offset(s * 0.5f, s * 0.26f),
                    end = Offset(s * 0.5f, s * 0.68f),
                    strokeWidth = sw,
                    cap = StrokeCap.Round
                )
                val head = Path().apply {
                    moveTo(s * 0.33f, s * 0.52f)
                    lineTo(s * 0.5f, s * 0.7f)
                    lineTo(s * 0.67f, s * 0.52f)
                }
                drawPath(head, inner, style = Stroke(width = sw, cap = StrokeCap.Round))
            }

            NavIcon.Gear -> {
                val cx = s * 0.5f
                val cy = s * 0.5f
                val teeth = 8
                val rInner = s * 0.3f
                val rOuter = s * 0.47f
                repeat(teeth) { i ->
                    val a = (Math.PI * 2.0 / teeth * i).toFloat()
                    drawLine(
                        color = color,
                        start = Offset(cx + rInner * cos(a), cy + rInner * sin(a)),
                        end = Offset(cx + rOuter * cos(a), cy + rOuter * sin(a)),
                        strokeWidth = s * 0.15f,
                        cap = StrokeCap.Round
                    )
                }
                drawCircle(
                    color = color,
                    radius = rInner,
                    center = Offset(cx, cy),
                    style = style
                )
                drawCircle(
                    color = if (filled) punch else color,
                    radius = s * 0.12f,
                    center = Offset(cx, cy),
                    style = if (filled) Fill else Stroke(width = sw)
                )
            }
        }
    }
}
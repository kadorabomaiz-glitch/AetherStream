package com.aether.stream.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.core.theme.LocalAetherColors
import kotlinx.datetime.Clock

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AetherFocusableGlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    cornerRadius: Dp = 16.dp,
    enableScaleOnFocus: Boolean = true,
    content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
    val aetherColors = LocalAetherColors.current
    var isFocused by remember { mutableStateOf(false) }
    var centerKeyDownTimestamp by remember { mutableLongStateOf(0L) }
    val interactionSource = remember { MutableInteractionSource() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused && enableScaleOnFocus) 1.035f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "FocusScale"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> aetherColors.focusBorder
            isSelected -> AetherColors.IceBlue
            else -> aetherColors.subtleBorder
        },
        animationSpec = tween(durationMillis = 180),
        label = "BorderColor"
    )

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused || isSelected) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 180),
        label = "BorderWidth"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isFocused || isSelected) {
            aetherColors.glassFocusedBackground
        } else {
            aetherColors.glassBackground
        },
        animationSpec = tween(durationMillis = 180),
        label = "GlassBgColor"
    )

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .scale(scale)
            .shadow(
                elevation = if (isFocused) 18.dp else 4.dp,
                shape = shape,
                ambientColor = if (isFocused) AetherColors.CyberCyan else Color.Black,
                spotColor = if (isFocused) AetherColors.CyberCyan else Color.Black
            )
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(borderWidth, borderColor), shape)
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (!focusState.isFocused) {
                    centerKeyDownTimestamp = 0L
                }
            }
            .onKeyEvent { event ->
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (centerKeyDownTimestamp == 0L) {
                                centerKeyDownTimestamp = Clock.System.now().toEpochMilliseconds()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            val downTime = centerKeyDownTimestamp
                            centerKeyDownTimestamp = 0L
                            val heldDuration = if (downTime > 0L) {
                                Clock.System.now().toEpochMilliseconds() - downTime
                            } else 0L

                            if (heldDuration >= 550L && onLongClick != null) {
                                onLongClick()
                            } else {
                                onClick()
                            }
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        content = { content(isFocused) }
    )
}

@Composable
fun AetherPrismaLogo(
    onSecretAdminTrigger: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "QUANTUM IPTV ENGINE"
) {
    var clickCount by remember { mutableIntStateOf(0) }
    var firstClickTimestamp by remember { mutableLongStateOf(0L) }
    var isFocused by remember { mutableStateOf(false) }

    val handleLogoClick = {
        val now = Clock.System.now().toEpochMilliseconds()
        if (clickCount == 0 || (now - firstClickTimestamp) > 2000L) {
            clickCount = 1
            firstClickTimestamp = now
        } else {
            clickCount++
            if (clickCount >= 3) {
                clickCount = 0
                firstClickTimestamp = 0L
                onSecretAdminTrigger()
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) 1.5.dp else 0.dp,
                color = if (isFocused) AetherColors.CyberCyan else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = handleLogoClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.size(42.dp)) {
            val w = size.width
            val h = size.height

            val outerPrism = Path().apply {
                moveTo(w * 0.15f, h * 0.12f)
                lineTo(w * 0.90f, h * 0.50f)
                lineTo(w * 0.15f, h * 0.88f)
                lineTo(w * 0.32f, h * 0.50f)
                close()
            }

            drawPath(
                path = outerPrism,
                brush = Brush.linearGradient(
                    colors = listOf(AetherColors.DeepSpaceNavy, AetherColors.ObsidianBlack),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                )
            )

            drawPath(
                path = outerPrism,
                brush = Brush.linearGradient(
                    colors = listOf(AetherColors.CyberCyan, AetherColors.IceBlue),
                    start = Offset(0f, 0f),
                    end = Offset(w, h)
                ),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )

            drawLine(
                color = AetherColors.CyberCyan,
                start = Offset(w * 0.28f, h * 0.50f),
                end = Offset(w * 0.65f, h * 0.50f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "AETHER",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = AetherColors.TextPrimary
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "STREAM",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = AetherColors.CyberCyan
                    )
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
fun AetherSmartLockBadge(
    newGroupCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (newGroupCount <= 0) return

    AetherFocusableGlassCard(
        onClick = onClick,
        cornerRadius = 50.dp,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = "⚡ $newGroupCount neue Gruppen im Filter verfügbar",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = AetherColors.CyberCyan
                )
            )
        }
    }
}
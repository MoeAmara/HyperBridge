package com.d4viddf.hyperbridge.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Measured from the HyperOS 3 reference videos (see hyperisland-ref/SPEC.md).
 * Ratios are relative to the screen width so the island scales per device.
 */
object IslandSpec {
    const val PILL_HEIGHT_RATIO = 0.083f      // compact pill height / screen width
    const val COMPACT_WIDTH_RATIO = 0.347f    // compact (call / timer) pill width
    const val WIDE_WIDTH_RATIO = 0.53f        // wide (charging) pill width
    const val CARD_MARGIN_RATIO = 0.044f      // side margin of expanded cards
    const val CARD_RADIUS_RATIO = 0.055f      // corner radius of expanded cards
    const val CALL_HEIGHT_RATIO = 0.22f       // expanded call card height
    const val MUSIC_HEIGHT_RATIO = 0.42f      // expanded music card height

    const val SPRING_DAMPING = 0.78f          // slight overshoot (~1-2%) as in the video
    const val SPRING_STIFFNESS = 380f         // ~220-260 ms to settle on expand

    val Fill = Color(0xFF000000)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8E8E93)
    val CallGreen = Color(0xFF34C759)
    val ChargeGreen = Color(0xFF4CD964)
    val HangUpRed = Color(0xFFFF3B30)
    val ButtonGrey = Color(0xFF2C2C2E)
    val MusicGreen = Color(0xFF1DB954)
}

private data class IslandRect(val left: Dp, val top: Dp, val width: Dp, val height: Dp, val radius: Dp)

@Composable
fun IslandOverlay(geometry: IslandGeometry, mode: IslandMode) {
    val density = LocalDensity.current
    val screenW = with(density) { geometry.screenWidthPx.toDp() }
    val camX = with(density) { geometry.cameraCenterXPx.toDp() }
    val camY = with(density) { geometry.cameraCenterYPx.toDp() }
    val camD = with(density) { geometry.cameraDiameterPx.toDp() }
    val statusBar = with(density) { geometry.statusBarHeightPx.toDp() }

    val pillH = maxOf(screenW * IslandSpec.PILL_HEIGHT_RATIO, camD + 8.dp)

    fun pill(width: Dp): IslandRect =
        IslandRect(camX - width / 2, camY - pillH / 2, width, pillH, pillH / 2)

    fun card(height: Dp): IslandRect {
        val margin = screenW * IslandSpec.CARD_MARGIN_RATIO
        val top = maxOf(statusBar, camY + camD / 2) + 6.dp
        return IslandRect(margin, top, screenW - margin * 2, height, screenW * IslandSpec.CARD_RADIUS_RATIO)
    }

    val target = when (mode) {
        IslandMode.HIDDEN -> {
            val d = camD + 2.dp
            IslandRect(camX - d / 2, camY - d / 2, d, d, d / 2)
        }
        IslandMode.COMPACT -> pill(screenW * IslandSpec.COMPACT_WIDTH_RATIO)
        IslandMode.WIDE -> pill(screenW * IslandSpec.WIDE_WIDTH_RATIO)
        IslandMode.EXPANDED -> card(screenW * IslandSpec.CALL_HEIGHT_RATIO)
        IslandMode.MUSIC -> card(screenW * IslandSpec.MUSIC_HEIGHT_RATIO)
    }

    val dpSpring = spring<Dp>(
        dampingRatio = IslandSpec.SPRING_DAMPING,
        stiffness = IslandSpec.SPRING_STIFFNESS
    )
    val left by animateDpAsState(target.left, dpSpring, label = "islandLeft")
    val top by animateDpAsState(target.top, dpSpring, label = "islandTop")
    val width by animateDpAsState(target.width, dpSpring, label = "islandWidth")
    val height by animateDpAsState(target.height, dpSpring, label = "islandHeight")
    val radius by animateDpAsState(target.radius, dpSpring, label = "islandRadius")
    val islandAlpha by animateFloatAsState(
        targetValue = if (mode == IslandMode.HIDDEN) 0f else 1f,
        animationSpec = if (mode == IslandMode.HIDDEN) tween<Float>(220, delayMillis = 60) else tween<Float>(120),
        label = "islandAlpha"
    )

    // Shared mock clocks so the timer keeps running across compact <-> expanded.
    val callSeconds by produceState(initialValue = 5) {
        while (true) {
            delay(1000)
            value += 1
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .offset(x = left, y = top)
                .size(width = maxOf(width, 0.dp), height = maxOf(height, 0.dp))
                .graphicsLayer { alpha = islandAlpha }
                .clip(RoundedCornerShape(maxOf(radius, 0.dp)))
                .background(IslandSpec.Fill)
        ) {
            AnimatedContent(
                targetState = mode,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(180, delayMillis = 90)) +
                            scaleIn(initialScale = 0.92f, animationSpec = tween(240, delayMillis = 60)))
                        .togetherWith(fadeOut(tween(90)))
                        .using(SizeTransform(clip = false))
                },
                label = "islandContent"
            ) { m ->
                when (m) {
                    IslandMode.HIDDEN -> Box(Modifier.fillMaxSize())
                    IslandMode.COMPACT -> CompactCallContent(formatClock(callSeconds))
                    IslandMode.WIDE -> WideChargingContent()
                    IslandMode.EXPANDED -> ExpandedCallContent(formatClock(callSeconds))
                    IslandMode.MUSIC -> MusicContent(elapsedSeconds = 44 + callSeconds - 5, totalSeconds = 324)
                }
            }
        }
    }
}

private fun formatClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

private val DigitsStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
private fun CompactCallContent(time: String) {
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Icon(
            imageVector = Icons.Rounded.Call,
            contentDescription = null,
            tint = IslandSpec.CallGreen,
            modifier = Modifier.align(Alignment.CenterStart).size(17.dp)
        )
        Text(
            text = time,
            color = IslandSpec.CallGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = DigitsStyle,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun WideChargingContent() {
    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Text(
            text = "Quick charging",
            color = IslandSpec.TextPrimary.copy(alpha = 0.88f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "69%",
                color = IslandSpec.ChargeGreen,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                style = DigitsStyle,
                maxLines = 1
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = IslandSpec.ChargeGreen,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun ExpandedCallContent(time: String) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Xiaomi",
                color = IslandSpec.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = time,
                color = IslandSpec.TextSecondary,
                fontSize = 13.sp,
                style = DigitsStyle,
                maxLines = 1
            )
        }
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(IslandSpec.ButtonGrey),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                tint = IslandSpec.TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(IslandSpec.HangUpRed),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CallEnd,
                contentDescription = null,
                tint = IslandSpec.TextPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun MusicContent(elapsedSeconds: Int, totalSeconds: Int) {
    val progress = (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
    Box(Modifier.fillMaxSize()) {
        // Soft accent glow at the bottom, tinted by the playing app (green in the reference).
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, IslandSpec.MusicGreen.copy(alpha = 0.22f))
                    )
                )
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF3A3A3C), Color(0xFF111111)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = IslandSpec.TextPrimary.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Wicked Games",
                        color = IslandSpec.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "The Weeknd",
                        color = IslandSpec.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).background(IslandSpec.MusicGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.SkipPrevious, null, tint = IslandSpec.TextPrimary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(44.dp))
                Icon(Icons.Rounded.Pause, null, tint = IslandSpec.TextPrimary, modifier = Modifier.size(38.dp))
                Spacer(Modifier.width(44.dp))
                Icon(Icons.Rounded.SkipNext, null, tint = IslandSpec.TextPrimary, modifier = Modifier.size(30.dp))
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatClock(elapsedSeconds),
                        color = IslandSpec.TextSecondary,
                        fontSize = 11.sp,
                        style = DigitsStyle
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(IslandSpec.TextPrimary.copy(alpha = 0.2f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .clip(RoundedCornerShape(2.dp))
                                .background(IslandSpec.TextPrimary)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = formatClock(totalSeconds),
                        color = IslandSpec.TextSecondary,
                        fontSize = 11.sp,
                        style = DigitsStyle
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(IslandSpec.MusicGreen.copy(alpha = 0.55f))
                )
            }
        }
    }
}

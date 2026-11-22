package com.d4viddf.hyperbridge.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Colours of the island (sizes live in IslandLayout). */
object IslandSpec {
    val Fill = Color(0xFF000000)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8E8E93)
    val CallGreen = Color(0xFF34C759)
    val ChargeGreen = Color(0xFF4CD964)
    val HangUpRed = Color(0xFFFF3B30)
    val ButtonGrey = Color(0xFF2C2C2E)
    val MusicGreen = Color(0xFF1DB954)
}

// Motion, fitted frame-by-frame to the HyperOS 3 promo (25 fps):
// grow ~5 frames (~200 ms) + 1-3% overshoot; width leads height slightly (iOS-like).
private val GROW_W: AnimationSpec<Float> = spring(dampingRatio = 0.74f, stiffness = 540f)
private val GROW_H: AnimationSpec<Float> = spring(dampingRatio = 0.80f, stiffness = 420f)
private val GROW_POS: AnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 460f)
// Shrink-then-grow switch: a quick, non-bouncy contraction toward the camera (~3 frames).
private val CONTRACT: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1100f)
// Dismiss: springs back into the camera without overshoot.
private val SHRINK: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 620f)
private val DRAG_BACK: AnimationSpec<Float> = spring(dampingRatio = 0.62f, stiffness = 520f)

/** All animated values of the single morphing shape, in px. */
private class IslandAnim(start: IslandRectPx, private val blurPx: Float) {
    val cx = Animatable(start.cx)
    val top = Animatable(start.top)
    val w = Animatable(start.w)
    val h = Animatable(start.h)
    val r = Animatable(start.r)
    val shapeAlpha = Animatable(0f)
    val contentAlpha = Animatable(0f)
    val contentScale = Animatable(1f)
    val contentBlur = Animatable(0f)
    val dragX = Animatable(0f)

    suspend fun snapShape(t: IslandRectPx) {
        cx.snapTo(t.cx)
        top.snapTo(t.top)
        w.snapTo(t.w)
        h.snapTo(t.h)
        r.snapTo(t.r)
    }

    suspend fun animateShape(
        t: IslandRectPx,
        wSpec: AnimationSpec<Float>,
        hSpec: AnimationSpec<Float>,
        posSpec: AnimationSpec<Float>
    ) {
        coroutineScope {
            launch { cx.animateTo(t.cx, posSpec) }
            launch { top.animateTo(t.top, posSpec) }
            launch { w.animateTo(t.w, wSpec) }
            launch { h.animateTo(t.h, hSpec) }
            launch { r.animateTo(t.r, posSpec) }
        }
    }

    suspend fun contentOut() {
        coroutineScope {
            launch { contentAlpha.animateTo(0f, tween(80, easing = LinearEasing)) }
            launch { contentBlur.animateTo(blurPx, tween(80)) }
        }
    }
}

/** Finger follows with resistance: never more than [limit] px away. */
private fun rubberBand(dx: Float, limit: Float): Float {
    if (limit <= 0f) return 0f
    val a = abs(dx)
    return sign(dx) * limit * (1f - 1f / (a * 0.6f / limit + 1f))
}

@Composable
fun IslandOverlay(geometry: IslandGeometry, mode: IslandMode, calibration: IslandCalibration) {
    val density = LocalDensity.current
    val d = density.density
    val blurPx = with(density) { 6.dp.toPx() }

    val latestGeometry by rememberUpdatedState(geometry)
    val latestCalibration by rememberUpdatedState(calibration)
    val currentMode by rememberUpdatedState(mode)

    fun targetOf(m: IslandMode): IslandRectPx = IslandLayout.target(m, latestGeometry, latestCalibration, d)

    val anim = remember { IslandAnim(IslandLayout.camera(geometry, calibration, d), blurPx) }
    var displayedMode by remember { mutableStateOf(IslandMode.HIDDEN) }
    var transitioning by remember { mutableStateOf(false) }

    // 1) Mode transitions: grow out of the camera, shrink-then-grow switches, shrink back into the camera.
    LaunchedEffect(mode) {
        transitioning = true
        val from = displayedMode
        if (mode == IslandMode.HIDDEN) {
            if (from != IslandMode.HIDDEN && anim.shapeAlpha.value > 0f) {
                anim.contentOut()
                val cam = targetOf(IslandMode.HIDDEN)
                coroutineScope {
                    launch { anim.dragX.animateTo(0f, SHRINK) }
                    launch { anim.animateShape(cam, SHRINK, SHRINK, SHRINK) }
                    launch {
                        // Only disappear once the shape is (almost) back inside the camera hole.
                        snapshotFlow { anim.w.value }.first { it <= cam.w * 1.25f + 1f }
                        anim.shapeAlpha.animateTo(0f, tween(90))
                    }
                }
            }
            anim.shapeAlpha.snapTo(0f)
            anim.contentAlpha.snapTo(0f)
            displayedMode = IslandMode.HIDDEN
        } else {
            if (from == IslandMode.HIDDEN || anim.shapeAlpha.value < 0.05f) {
                // Start as a camera-sized black dot sitting on the camera.
                anim.snapShape(targetOf(IslandMode.HIDDEN))
                anim.dragX.snapTo(0f)
                anim.contentAlpha.snapTo(0f)
                anim.shapeAlpha.snapTo(1f)
            } else if (from != mode) {
                // Switch: content out, contract toward the camera, then grow into the new shape.
                anim.contentOut()
                coroutineScope {
                    launch { anim.dragX.animateTo(0f, SHRINK) }
                    anim.animateShape(
                        IslandLayout.contracted(latestGeometry, latestCalibration, d),
                        CONTRACT, CONTRACT, CONTRACT
                    )
                }
                anim.shapeAlpha.snapTo(1f)
            }
            displayedMode = mode
            anim.contentScale.snapTo(0.9f)
            anim.contentBlur.snapTo(blurPx)
            val tgt = targetOf(mode)
            coroutineScope {
                launch { anim.animateShape(tgt, GROW_W, GROW_H, GROW_POS) }
                launch {
                    // Content appears only once the shape is ~70% grown.
                    val useH = abs(tgt.h - anim.h.value) > abs(tgt.w - anim.w.value)
                    val start = if (useH) anim.h.value else anim.w.value
                    val end = if (useH) tgt.h else tgt.w
                    if (abs(end - start) > 1f) {
                        withTimeoutOrNull(450) {
                            snapshotFlow { if (useH) anim.h.value else anim.w.value }
                                .first { (it - start) / (end - start) >= 0.7f }
                        }
                    }
                    coroutineScope {
                        launch { anim.contentAlpha.animateTo(1f, tween(160)) }
                        launch { anim.contentBlur.animateTo(0f, tween(180)) }
                        launch { anim.contentScale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f)) }
                    }
                }
            }
        }
        transitioning = false
    }

    // 2) Live calibration: when sizes/offsets change while the island is settled, follow immediately.
    val liveTarget = IslandLayout.target(mode, geometry, calibration, d)
    LaunchedEffect(liveTarget) {
        if (!transitioning && displayedMode == mode && mode != IslandMode.HIDDEN) {
            anim.snapShape(liveTarget)
        }
    }

    // 3) Swipe left/right to dismiss (events come from the small touch window).
    LaunchedEffect(Unit) {
        IslandController.drag.collect { ev ->
            val screenW = latestGeometry.screenWidthPx.toFloat()
            when (ev) {
                is IslandDrag.Move -> {
                    if (currentMode != IslandMode.HIDDEN) {
                        anim.dragX.snapTo(rubberBand(ev.totalDx, screenW * 0.22f))
                    }
                }
                is IslandDrag.End -> {
                    if (currentMode != IslandMode.HIDDEN) {
                        val far = abs(ev.totalDx) > screenW * 0.16f
                        val fling = abs(ev.velocityX) > 900f &&
                                (ev.totalDx == 0f || sign(ev.velocityX) == sign(ev.totalDx))
                        if (far || fling) {
                            IslandController.show(IslandMode.HIDDEN)
                        } else {
                            launch { anim.dragX.animateTo(0f, DRAG_BACK) }
                        }
                    }
                }
            }
        }
    }

    // Content is laid out once at its final size and revealed by the morphing clip (no reflow per frame).
    val contentRect = IslandLayout.target(displayedMode, geometry, calibration, d)
    val contentW = with(density) { contentRect.w.toDp() }
    val contentH = with(density) { contentRect.h.toDp() }

    // Shared mock clock so the timer keeps running across compact <-> expanded.
    val callSeconds by produceState(initialValue = 5) {
        while (true) {
            delay(1000)
            value += 1
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .layout { measurable, _ ->
                    val wPx = anim.w.value.coerceAtLeast(0f).roundToInt()
                    val hPx = anim.h.value.coerceAtLeast(0f).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
                    layout(wPx, hPx) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val w = anim.w.value.coerceAtLeast(0f)
                    val h = anim.h.value.coerceAtLeast(0f)
                    translationX = anim.cx.value + anim.dragX.value - w / 2f
                    translationY = anim.top.value
                    alpha = anim.shapeAlpha.value
                    shape = RoundedCornerShape(anim.r.value.coerceIn(0f, minOf(w, h) / 2f))
                    clip = true
                }
                .background(IslandSpec.Fill)
        ) {
            Box(
                Modifier
                    .wrapContentSize(align = Alignment.Center, unbounded = true)
                    .requiredSize(contentW, contentH)
                    .graphicsLayer {
                        alpha = anim.contentAlpha.value
                        val s = anim.contentScale.value
                        scaleX = s
                        scaleY = s
                        val b = anim.contentBlur.value
                        renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                    }
            ) {
                when (displayedMode) {
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

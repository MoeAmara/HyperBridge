package com.d4viddf.hyperbridge.island

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.collectAsState
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
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    val TimerBlue = Color(0xFF2F6BFF)
}

// Motion, fitted frame-by-frame to the HyperOS 3 promo (25 fps):
// grow ~5 frames (~200 ms) + 1-3% overshoot; width leads height slightly (iOS-like).
private val GROW_W: AnimationSpec<Float> = spring(dampingRatio = 0.74f, stiffness = 540f)
private val GROW_H: AnimationSpec<Float> = spring(dampingRatio = 0.80f, stiffness = 420f)
private val GROW_POS: AnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 460f)
// Shrink-then-grow switch: a quick, non-bouncy contraction toward the camera (~3 frames).
private val CONTRACT: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1100f)

/** Pill <-> its own expanded card: morph directly (no contraction step). */
private fun isOwnMorph(a: IslandMode, b: IslandMode): Boolean =
    a != b && ((b.isCard() && b.compact() == a) || (a.isCard() && a.compact() == b))

// Dismiss: springs back into the camera without overshoot.
private val SHRINK: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 620f)

/** Progress-driven content entry during a pill <-> card morph (tied to the shape's height). */
private data class MorphEntry(val startH: Float, val endH: Float, val expanding: Boolean)

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
    val press = Animatable(1f)

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

    /** Staggered morph: [first] dimension starts now, the rest follows after [leadMs]. */
    suspend fun morphShape(
        t: IslandRectPx,
        wSpec: AnimationSpec<Float>,
        hSpec: AnimationSpec<Float>,
        posSpec: AnimationSpec<Float>,
        widthFirst: Boolean,
        leadMs: Long
    ) {
        coroutineScope {
            launch { cx.animateTo(t.cx, posSpec) }
            launch {
                if (!widthFirst && leadMs > 0) delay(leadMs)
                w.animateTo(t.w, wSpec)
            }
            launch {
                if (widthFirst && leadMs > 0) delay(leadMs)
                launch { top.animateTo(t.top, posSpec) }
                launch { r.animateTo(t.r, posSpec) }
                h.animateTo(t.h, hSpec)
            }
        }
    }

    suspend fun contentOut() {
        coroutineScope {
            launch { contentAlpha.animateTo(0f, tween(80, easing = LinearEasing)) }
            launch { contentBlur.animateTo(blurPx, tween(80)) }
        }
    }
}

@Composable
fun IslandOverlay(
    geometry: IslandGeometry,
    mode: IslandMode,
    calibration: IslandCalibration,
    media: IslandMediaInfo?
) {
    val density = LocalDensity.current
    val d = density.density
    val blurPx = with(density) { 6.dp.toPx() }
    val motion by IslandController.motion.collectAsState()
    val pressed by IslandController.pressed.collectAsState()
    val live by IslandController.shownLive.collectAsState()

    val latestGeometry by rememberUpdatedState(geometry)
    val latestCalibration by rememberUpdatedState(calibration)
    val latestMotion by rememberUpdatedState(motion)

    fun targetOf(m: IslandMode): IslandRectPx = IslandLayout.target(m, latestGeometry, latestCalibration, d)

    val anim = remember { IslandAnim(IslandLayout.camera(geometry, calibration, d), blurPx) }
    var displayedMode by remember { mutableStateOf(IslandMode.HIDDEN) }
    var transitioning by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf<MorphEntry?>(null) }

    // Press anticipation: the island squeezes slightly under the finger, then springs back.
    LaunchedEffect(pressed) {
        if (pressed) {
            val s = latestMotion.squeeze.coerceIn(0.85f, 1f)
            val target = if (displayedMode.isCard()) 1f - (1f - s) / 2f else s
            anim.press.animateTo(target, tween(60))
        } else {
            anim.press.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 700f))
        }
    }

    // 1) Mode transitions: grow out of the camera, pill <-> card morph, shrink-then-grow switches,
    //    shrink back into the camera.
    LaunchedEffect(mode) {
        transitioning = true
        entry = null
        val from = displayedMode
        if (mode == IslandMode.HIDDEN) {
            if (from != IslandMode.HIDDEN && anim.shapeAlpha.value > 0f) {
                anim.contentOut()
                val cam = targetOf(IslandMode.HIDDEN)
                coroutineScope {
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
                anim.contentAlpha.snapTo(0f)
                anim.shapeAlpha.snapTo(1f)
            } else if (isOwnMorph(from, mode)) {
                // Pill <-> its own card: ONE continuous, springy morph (tunable in Island Test).
                // Width leads height; the new content comes in from blur, tied to the growth.
                anim.shapeAlpha.snapTo(1f)
                val tgt = targetOf(mode)
                val expanding = mode.isCard()
                val mo = latestMotion
                val damp = if (expanding) mo.bounce.coerceIn(0.3f, 1f)
                else (mo.bounce + 0.12f).coerceIn(0.3f, 1f)
                val stiff = (if (expanding) mo.speed else mo.speed * 1.3f).coerceIn(50f, 3000f)
                val wSpec = spring<Float>(dampingRatio = damp, stiffness = stiff * 1.15f)
                val hSpec = spring<Float>(dampingRatio = (damp + 0.04f).coerceAtMost(1f), stiffness = stiff)
                val posSpec = spring<Float>(dampingRatio = (damp + 0.08f).coerceAtMost(1f), stiffness = stiff)
                val lead = mo.widthLeadMs.coerceIn(0f, 200f).toLong()
                val startH = anim.h.value
                try {
                    coroutineScope {
                        launch {
                            anim.morphShape(
                                tgt, wSpec, hSpec, posSpec,
                                widthFirst = expanding,
                                leadMs = if (expanding) lead else lead / 2
                            )
                        }
                        launch {
                            // Old content leaves fast while the shape is already moving.
                            anim.contentAlpha.animateTo(0f, tween(70, easing = LinearEasing))
                            anim.contentBlur.snapTo(0f)
                            anim.contentScale.snapTo(1f)
                            entry = MorphEntry(startH, tgt.h, expanding)
                            displayedMode = mode
                            anim.contentAlpha.snapTo(1f)
                        }
                    }
                } finally {
                    entry = null
                }
                displayedMode = mode
                if (anim.contentAlpha.value < 1f) anim.contentAlpha.snapTo(1f)
                transitioning = false
                return@LaunchedEffect
            } else if (from != mode) {
                anim.contentOut()
                if (!(from.isCard() && !mode.isCard())) {
                    // Switch: contract toward the camera, then grow into the new shape.
                    // (A card collapsing to its pill springs straight down to the pill instead.)
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

    // Content is laid out once at its final size and revealed by the morphing clip (no reflow per frame).
    val contentRect = IslandLayout.target(displayedMode, geometry, calibration, d)
    val cardContent = displayedMode.isCard()
    val contentW = with(density) { contentRect.w.toDp() }
    val contentH = with(density) { contentRect.h.toDp() }
    val entryBlurMax = with(density) { motion.blurDp.coerceIn(0f, 40f).dp.toPx() }
    val entryDrop = with(density) { 6.dp.toPx() }

    // Shared mock clock so the timer keeps running across compact <-> expanded.
    val callSeconds by produceState(initialValue = 5) {
        while (true) {
            delay(1000)
            value += 1
        }
    }
    val mockClock = formatClock(callSeconds)

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
                    translationX = anim.cx.value - w / 2f
                    translationY = anim.top.value
                    alpha = anim.shapeAlpha.value
                    val p = anim.press.value
                    scaleX = p
                    scaleY = p
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
                        // Cards: content grows with the shape (iOS-like), gently clamped.
                        val fit = if (cardContent && contentRect.w > 1f)
                            (anim.w.value / contentRect.w).coerceIn(0.85f, 1.02f) else 1f
                        val e = entry
                        if (e != null) {
                            val span = e.endH - e.startH
                            val prog = if (abs(span) > 1f) ((anim.h.value - e.startH) / span).coerceIn(0f, 1f) else 1f
                            val a = ((prog - 0.35f) / 0.5f).coerceIn(0f, 1f)
                            alpha = anim.contentAlpha.value * a
                            val base = if (e.expanding) 0.9f else 0.94f
                            val s = (base + (1f - base) * a) * fit
                            scaleX = s
                            scaleY = s
                            translationY = (1f - a) * -entryDrop
                            val b = entryBlurMax * (1f - a)
                            renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                        } else {
                            alpha = anim.contentAlpha.value
                            val s = anim.contentScale.value * fit
                            scaleX = s
                            scaleY = s
                            translationY = 0f
                            val b = anim.contentBlur.value
                            renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                        }
                    }
            ) {
                when (displayedMode) {
                    IslandMode.HIDDEN -> Box(Modifier.fillMaxSize())
                    IslandMode.COMPACT -> CompactCallContent(live, mockClock)
                    IslandMode.WIDE -> WideChargingContent()
                    IslandMode.EXPANDED -> ExpandedCallContent(live, mockClock)
                    IslandMode.MUSIC -> MusicContent(media, mockElapsedSeconds = 44 + callSeconds - 5)
                    IslandMode.COMPACT_MUSIC -> CompactMusicContent(media)
                    IslandMode.INCOMING -> IncomingCallContent(live)
                    IslandMode.TIMER_PILL -> TimerPillContent(live, callSeconds)
                    IslandMode.TIMER_CARD -> TimerCardContent(live, callSeconds)
                    IslandMode.REC_PILL -> RecPillContent(live, mockClock)
                    IslandMode.REC_CARD -> RecCardContent(live, mockClock)
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

/** Wall clock that ticks while the composable is visible (for live timers). */
@Composable
private fun rememberNow(periodMs: Long = 250L): Long {
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** Soft pulse 0..1 (incoming-call icon, recorder dot). */
@Composable
private fun rememberPulse(periodMs: Int = 700): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val v by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseV"
    )
    return v
}

@Composable
private fun CompactCallContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val incoming = live?.kind == LiveKind.INCOMING_CALL
    val pulse = if (incoming) rememberPulse() else 0f
    val right = when {
        live == null -> mockTime
        incoming -> live.title.ifBlank { "Call" }
        else -> live.timeText(now) ?: live.text.ifBlank { "Call" }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Icon(
            imageVector = Icons.Rounded.Call,
            contentDescription = null,
            tint = IslandSpec.CallGreen,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(17.dp)
                .graphicsLayer {
                    val s = 1f + 0.12f * pulse
                    scaleX = s
                    scaleY = s
                    rotationZ = if (incoming) (pulse - 0.5f) * 24f else 0f
                }
        )
        Text(
            text = right,
            color = IslandSpec.CallGreen,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = DigitsStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterEnd).widthIn(max = 64.dp)
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

/** Round icon button whose bounds are reported for tap routing. */
@Composable
private fun RoundButton(
    button: IslandButton,
    sizeDp: Int,
    fill: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color = IslandSpec.TextPrimary,
    ring: Color? = null
) {
    val touchPad = with(LocalDensity.current) { 6.dp.toPx() }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .reportButton(button, touchPad)
            .clip(CircleShape)
            .background(fill)
            .then(if (ring != null) Modifier.border(3.dp, ring, CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size((sizeDp * 0.5f).dp)
        )
    }
}

@Composable
private fun ExpandedCallContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val name = live?.title?.ifBlank { null } ?: if (live == null) "Xiaomi" else "Call"
    val time = if (live == null) mockTime else live.timeText(now) ?: live.text
    val showSpeaker = live == null || live.action(ActionRole.SPEAKER) != null
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        live?.avatar?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
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
        if (showSpeaker) {
            RoundButton(IslandButton.SPEAKER, 44, IslandSpec.ButtonGrey, Icons.AutoMirrored.Rounded.VolumeUp)
            Spacer(Modifier.width(14.dp))
        }
        RoundButton(IslandButton.HANG_UP, 52, IslandSpec.HangUpRed, Icons.Rounded.CallEnd)
    }
}

@Composable
private fun IncomingCallContent(live: IslandLive?) {
    val pulse = rememberPulse(600)
    val name = live?.title?.ifBlank { null } ?: if (live == null) "Xiaomi" else "Unknown"
    val sub = live?.text?.ifBlank { null } ?: "Incoming call"
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(IslandSpec.ButtonGrey),
            contentAlignment = Alignment.Center
        ) {
            val av = live?.avatar
            if (av != null) {
                Image(bitmap = av, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    text = name.take(1).uppercase(),
                    color = IslandSpec.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                color = IslandSpec.TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                color = IslandSpec.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        RoundButton(IslandButton.DECLINE, 48, IslandSpec.HangUpRed, Icons.Rounded.CallEnd)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.graphicsLayer {
            val s = 1f + 0.06f * pulse
            scaleX = s
            scaleY = s
        }) {
            RoundButton(IslandButton.ANSWER, 48, IslandSpec.CallGreen, Icons.Rounded.Call)
        }
    }
}

/** Timer / stopwatch display time; mock counts down from 04:56 (timer). */
private fun liveOrMockTime(live: IslandLive?, now: Long, mockSeconds: Int, stopwatch: Boolean): String =
    if (live == null) formatDuration(if (stopwatch) mockSeconds.toLong() else (296L - mockSeconds).coerceAtLeast(0L))
    else live.timeText(now) ?: "--:--"

@Composable
private fun TimerPillContent(live: IslandLive?, mockSeconds: Int) {
    val now = rememberNow()
    val stopwatch = live?.kind == LiveKind.STOPWATCH
    val paused = live?.paused == true
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Icon(
            imageVector = if (stopwatch) Icons.Rounded.Timer else Icons.Rounded.HourglassBottom,
            contentDescription = null,
            tint = IslandSpec.TimerBlue,
            modifier = Modifier.align(Alignment.CenterStart).size(17.dp)
        )
        Text(
            text = liveOrMockTime(live, now, mockSeconds, stopwatch),
            color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = DigitsStyle,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun TimerCardContent(live: IslandLive?, mockSeconds: Int) {
    val now = rememberNow()
    val stopwatch = live?.kind == LiveKind.STOPWATCH
    val paused = live?.paused == true
    val label = when {
        live == null -> "Timer"
        stopwatch -> "Stopwatch"
        else -> "Timer"
    }
    val primary = live == null || live.action(ActionRole.PAUSE, ActionRole.RESUME) != null
    val lap = live?.action(ActionRole.LAP)
    val secondary = live == null || lap != null || live.action(ActionRole.STOP) != null
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (stopwatch) Icons.Rounded.Timer else Icons.Rounded.HourglassBottom,
            contentDescription = null,
            tint = IslandSpec.TimerBlue,
            modifier = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = liveOrMockTime(live, now, mockSeconds, stopwatch),
                color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                style = DigitsStyle,
                maxLines = 1
            )
            Text(text = label, color = IslandSpec.TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
        if (primary) {
            RoundButton(
                IslandButton.PRIMARY, 52, Color.Transparent,
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                iconTint = IslandSpec.TimerBlue, ring = IslandSpec.TimerBlue
            )
            Spacer(Modifier.width(12.dp))
        }
        if (secondary) {
            RoundButton(
                IslandButton.SECONDARY, 44, IslandSpec.ButtonGrey,
                if (stopwatch && lap != null && !paused) Icons.Rounded.Flag else Icons.Rounded.Close
            )
        }
    }
}

@Composable
private fun RecDot(pulse: Float, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .graphicsLayer { alpha = 0.45f + 0.55f * pulse }
            .clip(CircleShape)
            .background(IslandSpec.HangUpRed)
    )
}

@Composable
private fun RecPillContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val paused = live?.paused == true
    val pulse = if (paused) 1f else rememberPulse(800)
    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) { RecDot(pulse, 10) }
        Text(
            text = if (live == null) mockTime else live.timeText(now) ?: "REC",
            color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            style = DigitsStyle,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun RecCardContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val paused = live?.paused == true
    val pulse = if (paused) 1f else rememberPulse(800)
    val primary = live == null || live.action(ActionRole.PAUSE, ActionRole.RESUME) != null
    val stop = live == null || live.action(ActionRole.STOP) != null
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RecDot(pulse, 14)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (live == null) mockTime else live.timeText(now) ?: "--:--",
                color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                style = DigitsStyle,
                maxLines = 1
            )
            Text(
                text = when {
                    paused -> "Paused"
                    live == null -> "Recording"
                    else -> live.title.ifBlank { "Recording" }
                },
                color = IslandSpec.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (primary) {
            RoundButton(
                IslandButton.PRIMARY, 44, IslandSpec.ButtonGrey,
                if (paused) Icons.Rounded.Mic else Icons.Rounded.Pause
            )
            Spacer(Modifier.width(12.dp))
        }
        if (stop) {
            RoundButton(IslandButton.SECONDARY, 52, IslandSpec.HangUpRed, Icons.Rounded.Stop)
        }
    }
}

/** Reports a control's on-screen bounds so taps on the touch window can be routed to it. */
private fun Modifier.reportButton(button: IslandButton, paddingPx: Float): Modifier =
    this.onGloballyPositioned { IslandController.setButtonRect(button, it.boundsInWindow(), paddingPx) }

/** Rounded album art, or a neutral placeholder with a note. */
@Composable
private fun AlbumArt(art: androidx.compose.ui.graphics.ImageBitmap?, sizeDp: Float, radiusDp: Float) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(radiusDp.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A3A3C), Color(0xFF111111)))),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = IslandSpec.TextPrimary.copy(alpha = 0.7f),
                modifier = Modifier.size((sizeDp * 0.5f).dp)
            )
        }
    }
}

/** Sound-wave bars; they move only while music is playing. */
@Composable
private fun SoundWave(playing: Boolean, color: Color) {
    val heights = if (playing) {
        val transition = rememberInfiniteTransition(label = "wave")
        listOf(420, 300, 520, 360).mapIndexed { i, period ->
            val v by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(period, delayMillis = i * 60, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar$i"
            )
            v
        }
    } else {
        listOf(0.3f, 0.45f, 0.3f, 0.4f)
    }
    Row(
        modifier = Modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        heights.forEach { h ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(h)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun CompactMusicContent(media: IslandMediaInfo?) {
    val accent = media?.accent ?: IslandSpec.MusicGreen
    val playing = media?.playing ?: true
    Box(Modifier.fillMaxSize().padding(horizontal = 7.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) {
            AlbumArt(media?.art, sizeDp = 22f, radiusDp = 6f)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp)) {
            SoundWave(playing = playing, color = accent)
        }
    }
}

@Composable
private fun MusicContent(media: IslandMediaInfo?, mockElapsedSeconds: Int) {
    val touchPad = with(LocalDensity.current) { 10.dp.toPx() }
    // Real media: extrapolate the position from the last PlaybackState (ticks 4x/s while playing).
    val positionMs by produceState(initialValue = media?.currentPositionMs() ?: 0L, media) {
        val m = media ?: return@produceState
        value = m.currentPositionMs()
        while (m.playing) {
            delay(250)
            value = m.currentPositionMs(SystemClock.elapsedRealtime())
        }
    }
    val elapsedSeconds: Int
    val totalSeconds: Int
    if (media != null) {
        elapsedSeconds = (positionMs / 1000L).toInt()
        totalSeconds = (media.durationMs / 1000L).toInt()
    } else {
        elapsedSeconds = mockElapsedSeconds
        totalSeconds = 324
    }
    val progress = if (totalSeconds > 0) (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f) else 0f
    val accent = media?.accent ?: IslandSpec.MusicGreen
    val playing = media?.playing ?: true
    val title = media?.title ?: "Wicked Games"
    val artist = media?.artist ?: "The Weeknd"

    Box(Modifier.fillMaxSize()) {
        // Soft accent glow at the bottom, tinted by the album art (green in the reference).
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, accent.copy(alpha = 0.22f))
                    )
                )
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(media?.art, sizeDp = 44f, radiusDp = 8f)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = IslandSpec.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = artist,
                        color = IslandSpec.TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val icon = media?.appIcon
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp).clip(CircleShape)
                    )
                } else {
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
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.SkipPrevious, null, tint = IslandSpec.TextPrimary,
                    modifier = Modifier.size(30.dp).reportButton(IslandButton.PREV, touchPad)
                )
                Spacer(Modifier.width(44.dp))
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null,
                    tint = IslandSpec.TextPrimary,
                    modifier = Modifier.size(38.dp).reportButton(IslandButton.PLAY_PAUSE, touchPad)
                )
                Spacer(Modifier.width(44.dp))
                Icon(
                    Icons.Rounded.SkipNext, null, tint = IslandSpec.TextPrimary,
                    modifier = Modifier.size(30.dp).reportButton(IslandButton.NEXT, touchPad)
                )
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
                        text = if (totalSeconds > 0) formatClock(totalSeconds) else "--:--",
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
                        .background(accent.copy(alpha = 0.55f))
                )
            }
        }
    }
}

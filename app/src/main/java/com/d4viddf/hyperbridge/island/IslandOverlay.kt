package com.d4viddf.hyperbridge.island

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Colours of the island (sizes live in IslandLayout). */
object IslandSpec {
    val Fill = Color(0xFF000000)
    /** Expanded cards are a very dark grey (HyperOS 3), compact pills pure black. */
    val CardFill = Color(0xFF1C1C1E)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8E8E93)
    val CallGreen = Color(0xFF34C759)
    val ChargeGreen = Color(0xFF4CD964)
    val HangUpRed = Color(0xFFFF3B30)
    val RecRed = Color(0xFFFF4A1C)
    val ButtonGrey = Color(0xFF3A3A3C)
    val MusicGreen = Color(0xFF1DB954)
    val TimerBlue = Color(0xFF3D7BFF)
    val HandleGrey = Color(0x40FFFFFF)
}

/** Where pill content sits (camera position inside the pill, padding at both ends). */
private val LocalPillFrame = compositionLocalOf<PillFrame?> { null }

/** Expanded card fill: frosted tint over the real blur, or dark translucent when blur is unavailable. */
private val CardFallbackFill = Color(0xE01C1C1E)
private val CardBlurTint = Color(0x8C0E0E10)

// Grow out of the camera / shrink back into it (unchanged since Phase 1.1 — Moe likes it).
private val GROW_W: AnimationSpec<Float> = spring(dampingRatio = 0.74f, stiffness = 540f)
private val GROW_H: AnimationSpec<Float> = spring(dampingRatio = 0.80f, stiffness = 420f)
private val GROW_POS: AnimationSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 460f)
// Shrink-then-grow switch between unrelated contents: quick contraction toward the camera.
private val CONTRACT: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1100f)
// Dismiss: springs back into the camera without overshoot.
private val SHRINK: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 620f)

/** Pill <-> its own expanded card: one liquid morph (no contraction step). */
private fun isOwnMorph(a: IslandMode, b: IslandMode): Boolean =
    a != b && ((b.isCard() && b.compact() == a) || (a.isCard() && a.compact() == b))

/**
 * A running pill <-> card morph. Both contents are drawn: the old one is stretched with the
 * shape and melts away, the new one grows with the shape out of blur. Their scale is always
 * shape width / their own laid-out width, so content never leads or lags the shape.
 */
private data class Morph(
    val from: IslandMode,
    val fromPlan: IslandPlan,
    val fromW: Float,
    val toW: Float,
    val startW: Float,
    val endW: Float,
    val startH: Float,
    val endH: Float
)

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

    /** Liquid morph: 0 = old content, 1 = new content (fast, no bounce). */
    val fade = Animatable(1f)

    /** Jelly squash of the whole shape. */
    val jx = Animatable(1f)
    val jy = Animatable(1f)

    /** 0 = black pill, 1 = card fill. */
    val cardness = Animatable(0f)

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

    /** "Jelly": squash (expanding: narrower + taller) then spring back. */
    suspend fun jelly(expanding: Boolean, amount: Float, bounce: Float) {
        val a = amount.coerceIn(0f, 0.2f)
        if (a <= 0.001f) return
        val dir = if (expanding) 1f else -1f
        val rebound = spring<Float>(dampingRatio = (bounce * 0.65f).coerceIn(0.3f, 1f), stiffness = 260f)
        coroutineScope {
            launch {
                jx.animateTo(1f - a * dir, tween(110))
                jx.animateTo(1f, rebound)
            }
            launch {
                jy.animateTo(1f + a * dir, tween(110))
                jy.animateTo(1f, rebound)
            }
        }
    }
}

/** The second-activity circle: position, size, visibility and its content fade. */
private class BubbleAnim {
    val cx = Animatable(0f)
    val top = Animatable(0f)
    val size = Animatable(0f)
    val alpha = Animatable(0f)
    val content = Animatable(0f)
}

/** Content widths of a pill (null for cards / hidden / the mock charging pill). */
private fun pillSlots(mode: IslandMode, live: IslandLive?, mockSeconds: Int, tm: TextMeasurer, d: Float): PillSlots? {
    fun textW(t: String, size: TextUnit, tnum: Boolean): Float = tm.measure(
        t,
        TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold, fontFeatureSettings = if (tnum) "tnum" else null),
        softWrap = false,
        maxLines = 1
    ).size.width.toFloat()
    return when (mode) {
        IslandMode.COMPACT, IslandMode.TIMER_PILL, IslandMode.REC_PILL -> {
            val t = pillTimeText(mode, live, mockSeconds) ?: return null
            // Every digit counted as "0" so the width doesn't change every second.
            val digits = t.any { it.isDigit() }
            val tmpl = if (digits) t.map { if (it.isDigit()) '0' else it }.joinToString("") else t
            PillSlots(pillLeftDp(mode) * d, textW(tmpl, PillTimeSize, digits) + 1.5f * d)
        }
        IslandMode.COMPACT_MUSIC -> PillSlots(22f * d, 18f * d)
        IslandMode.INCOMING_PILL -> PillSlots(19f * d, textW("Incoming call", IncomingTextSize, false) + 1.5f * d)
        else -> null
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
    val live by IslandController.shownLive.collectAsState()
    val bubble by IslandController.bubble.collectAsState()
    val blurState by IslandController.blurState.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    // Shared mock clock so mock timers keep running across compact <-> expanded.
    val mockSeconds by produceState(initialValue = 5) {
        while (true) {
            delay(1000)
            value += 1
        }
    }

    val latestGeometry by rememberUpdatedState(geometry)
    val latestCalibration by rememberUpdatedState(calibration)
    val latestMotion by rememberUpdatedState(motion)
    val latestLive by rememberUpdatedState(live)
    val latestMock by rememberUpdatedState(mockSeconds)
    val latestBubble by rememberUpdatedState(bubble)

    fun withBubble(m: IslandMode, b: IslandBubble?): Boolean =
        b != null && m != IslandMode.HIDDEN && !m.isCard()

    fun planOf(m: IslandMode): IslandPlan = IslandLayout.plan(
        m, latestGeometry, latestCalibration, d,
        pillSlots(m, latestLive, latestMock, textMeasurer, d),
        withBubble(m, latestBubble)
    )

    fun targetOf(m: IslandMode): IslandRectPx = planOf(m).main

    fun islandSpring(): AnimationSpec<Float> = spring(
        dampingRatio = latestMotion.bounce.coerceIn(0.3f, 1f),
        stiffness = latestMotion.speed.coerceIn(60f, 3000f)
    )

    val anim = remember { IslandAnim(IslandLayout.camera(geometry, calibration, d), blurPx) }
    val bAnim = remember { BubbleAnim() }
    val blurMix = remember { Animatable(0f) }
    var displayedMode by remember { mutableStateOf(IslandMode.HIDDEN) }
    var transitioning by remember { mutableStateOf(false) }
    var morph by remember { mutableStateOf<Morph?>(null) }
    var shownBubble by remember { mutableStateOf<IslandBubble?>(null) }

    LaunchedEffect(mode) {
        transitioning = true
        morph = null
        val from = displayedMode
        if (mode == IslandMode.HIDDEN) {
            if (from != IslandMode.HIDDEN && anim.shapeAlpha.value > 0f) {
                anim.contentOut()
                val cam = targetOf(IslandMode.HIDDEN)
                coroutineScope {
                    launch { anim.cardness.animateTo(0f, tween(160)) }
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
            anim.cardness.snapTo(0f)
            anim.jx.snapTo(1f)
            anim.jy.snapTo(1f)
            displayedMode = IslandMode.HIDDEN
        } else {
            if (from == IslandMode.HIDDEN || anim.shapeAlpha.value < 0.05f) {
                // Start as a camera-sized black dot sitting on the camera.
                anim.snapShape(targetOf(IslandMode.HIDDEN))
                anim.contentAlpha.snapTo(0f)
                anim.cardness.snapTo(0f)
                anim.shapeAlpha.snapTo(1f)
            } else if (isOwnMorph(from, mode)) {
                // Pill <-> its own card: ONE spring for width, height, position and corners.
                anim.shapeAlpha.snapTo(1f)
                val tgt = targetOf(mode)
                val srcPlan = planOf(from)
                val mo = latestMotion
                val expanding = mode.isCard()
                val damping = mo.bounce.coerceIn(0.3f, 1f)
                val stiffness = mo.speed.coerceIn(60f, 3000f)
                val shapeSpec = spring<Float>(dampingRatio = damping, stiffness = stiffness)
                // Content fades on the same "speed" but much faster and without bounce.
                val fadeSpec = spring<Float>(dampingRatio = 1f, stiffness = (stiffness * 6f).coerceIn(300f, 10000f))
                morph = Morph(
                    from = from,
                    fromPlan = srcPlan,
                    fromW = srcPlan.main.w,
                    toW = tgt.w,
                    startW = anim.w.value,
                    endW = tgt.w,
                    startH = anim.h.value,
                    endH = tgt.h
                )
                displayedMode = mode
                anim.contentAlpha.snapTo(1f)
                anim.contentBlur.snapTo(0f)
                anim.contentScale.snapTo(1f)
                anim.fade.snapTo(0f)
                try {
                    coroutineScope {
                        launch { anim.animateShape(tgt, shapeSpec, shapeSpec, shapeSpec) }
                        launch { anim.fade.animateTo(1f, fadeSpec) }
                        launch { anim.cardness.animateTo(if (expanding) 1f else 0f, tween(220)) }
                        if (mo.jelly) launch { anim.jelly(expanding, mo.jellyAmount, damping) }
                    }
                } finally {
                    morph = null
                    anim.fade.snapTo(1f)
                }
                transitioning = false
                return@LaunchedEffect
            } else if (from != mode) {
                anim.contentOut()
                if (!(from.isCard() && !mode.isCard())) {
                    // Unrelated contents: contract toward the camera, then grow into the new shape.
                    coroutineScope {
                        launch { anim.cardness.animateTo(0f, tween(120)) }
                        launch {
                            anim.animateShape(
                                IslandLayout.contracted(latestGeometry, latestCalibration, d),
                                CONTRACT, CONTRACT, CONTRACT
                            )
                        }
                    }
                }
                anim.shapeAlpha.snapTo(1f)
            }
            displayedMode = mode
            anim.contentScale.snapTo(0.9f)
            anim.contentBlur.snapTo(blurPx)
            val tgt = targetOf(mode)
            coroutineScope {
                launch { anim.cardness.animateTo(if (mode.isCard()) 1f else 0f, tween(220)) }
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

    // The plan for what is requested right now (measured content + bubble), used for following,
    // for the touch window and for the blur window.
    val livePlan = IslandLayout.plan(
        mode, geometry, calibration, d,
        pillSlots(mode, live, mockSeconds, textMeasurer, d),
        withBubble(mode, bubble)
    )
    LaunchedEffect(livePlan, mode) {
        IslandController.setLayout(if (mode == IslandMode.HIDDEN) null else livePlan.main, livePlan.bubble)
    }

    // Calibration, a bubble arriving / leaving, or a counter growing (59:59 -> 1:00:00): follow with the
    // island spring. Keyed on `transitioning` too, so a change that lands mid-animation is applied after it.
    LaunchedEffect(livePlan.main, transitioning, displayedMode) {
        if (!transitioning && displayedMode == mode && mode != IslandMode.HIDDEN) {
            val sp = islandSpring()
            anim.animateShape(livePlan.main, sp, sp, sp)
        }
    }

    // Second activity: splits out of the pill's left side, merges back into it when it ends.
    val bubbleTarget = livePlan.bubble
    LaunchedEffect(bubbleTarget, bubble?.kind, bubble?.id) {
        val b = bubble
        if (bubbleTarget != null && b != null) {
            val sp = islandSpring()
            if (bAnim.alpha.value < 0.5f) {
                shownBubble = b
                val pill = livePlan.main
                bAnim.cx.snapTo(pill.left + bubbleTarget.w / 2f)
                bAnim.top.snapTo(bubbleTarget.top)
                bAnim.size.snapTo(bubbleTarget.w)
                bAnim.content.snapTo(0f)
                bAnim.alpha.snapTo(1f)
                coroutineScope {
                    launch { bAnim.cx.animateTo(bubbleTarget.cx, sp) }
                    launch {
                        delay(110)
                        bAnim.content.animateTo(1f, tween(170))
                    }
                }
            } else {
                val prev = shownBubble
                if (prev == null || prev.kind != b.kind || prev.id != b.id) {
                    bAnim.content.animateTo(0f, tween(70))
                    shownBubble = b
                    coroutineScope {
                        launch { bAnim.content.animateTo(1f, tween(150)) }
                        launch { bAnim.cx.animateTo(bubbleTarget.cx, sp) }
                        launch { bAnim.top.animateTo(bubbleTarget.top, sp) }
                        launch { bAnim.size.animateTo(bubbleTarget.w, sp) }
                    }
                } else {
                    coroutineScope {
                        launch { bAnim.cx.animateTo(bubbleTarget.cx, sp) }
                        launch { bAnim.top.animateTo(bubbleTarget.top, sp) }
                        launch { bAnim.size.animateTo(bubbleTarget.w, sp) }
                    }
                }
            }
        } else if (bAnim.alpha.value > 0f) {
            bAnim.content.animateTo(0f, tween(70))
            val into = anim.cx.value - anim.w.value / 2f + bAnim.size.value / 2f
            coroutineScope {
                launch { bAnim.cx.animateTo(into, spring(dampingRatio = 1f, stiffness = 700f)) }
                launch {
                    delay(140)
                    bAnim.alpha.animateTo(0f, tween(90))
                }
            }
            bAnim.alpha.snapTo(0f)
            shownBubble = null
        }
    }

    // Real blur behind a settled card: the service puts a blur window exactly behind it, then the card's
    // fill turns from dark translucent into a frosted tint. Collapse = back to the solid fill at once.
    val cardSettled = displayedMode == mode && mode.isCard() && !transitioning
    LaunchedEffect(cardSettled, livePlan.main, blurState.real) {
        if (cardSettled && blurState.real) {
            IslandController.setBlurRect(livePlan.main)
            delay(90)
            blurMix.animateTo(1f, tween(200))
        } else {
            blurMix.snapTo(0f)
            IslandController.setBlurRect(null)
        }
    }

    val blurMax = with(density) { motion.blurDp.coerceIn(0f, 40f).dp.toPx() }

    Box(Modifier.fillMaxSize()) {
        // Bubble first: it sits behind the pill, so it can split out of the pill's side.
        Box(
            Modifier
                .layout { measurable, _ ->
                    val s = bAnim.size.value.coerceAtLeast(0f).roundToInt()
                    val placeable = measurable.measure(Constraints.fixed(s, s))
                    layout(s, s) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val s = bAnim.size.value.coerceAtLeast(0f)
                    translationX = bAnim.cx.value - s / 2f
                    translationY = bAnim.top.value
                    alpha = bAnim.alpha.value
                    shape = CircleShape
                    clip = true
                }
                .background(IslandSpec.Fill)
        ) {
            val shown = bubble ?: shownBubble
            if (shown != null) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = bAnim.content.value
                            val b = blurMax * 0.6f * (1f - bAnim.content.value)
                            renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                        },
                    contentAlignment = Alignment.Center
                ) { BubbleContent(shown, media, mockSeconds) }
            }
        }

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
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = anim.jx.value
                    scaleY = anim.jy.value
                    shape = RoundedCornerShape(anim.r.value.coerceIn(0f, minOf(w, h) / 2f))
                    clip = true
                }
                .drawBehind {
                    val cardFill = lerp(CardFallbackFill, CardBlurTint, blurMix.value.coerceIn(0f, 1f))
                    drawRect(lerp(IslandSpec.Fill, cardFill, anim.cardness.value.coerceIn(0f, 1f)))
                }
        ) {
            val m = morph
            if (m != null) {
                // The old content: stretched with the shape, melting away.
                ContentLayer(m.fromPlan.main, Alignment.TopCenter, Modifier.graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    val k = (anim.w.value / m.fromW.coerceAtLeast(1f)).coerceIn(0.25f, 3f)
                    scaleX = k
                    scaleY = k
                    alpha = (1f - anim.fade.value).coerceIn(0f, 1f)
                    val p = morphProgress(m, anim)
                    val b = blurMax * 0.7f * p * sqrt(p)
                    renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                }) {
                    CompositionLocalProvider(LocalPillFrame provides m.fromPlan.frame) {
                        ModeContent(m.from, live, media, mockSeconds)
                    }
                }
            }
            val shownPlan = if (displayedMode == mode) livePlan else IslandLayout.plan(
                displayedMode, geometry, calibration, d,
                pillSlots(displayedMode, live, mockSeconds, textMeasurer, d),
                withBubble(displayedMode, bubble)
            )
            val rect = shownPlan.main
            val cardContent = displayedMode.isCard()
            ContentLayer(
                rect,
                if (m != null) Alignment.TopCenter else Alignment.Center,
                Modifier.graphicsLayer {
                    val mm = morph
                    if (mm != null) {
                        // The new content grows out of the old one with the shape, out of blur.
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        val k = (anim.w.value / mm.toW.coerceAtLeast(1f)).coerceIn(0.25f, 3f)
                        scaleX = k
                        scaleY = k
                        alpha = anim.fade.value.coerceIn(0f, 1f)
                        val rest = 1f - morphProgress(mm, anim)
                        val b = blurMax * rest * sqrt(rest)
                        renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                    } else {
                        transformOrigin = TransformOrigin.Center
                        // Cards: content grows with the shape, gently clamped. Pills: content keeps its size.
                        val fit = if (cardContent && rect.w > 1f)
                            (anim.w.value / rect.w).coerceIn(0.85f, 1.02f) else 1f
                        alpha = anim.contentAlpha.value
                        val s = anim.contentScale.value * fit
                        scaleX = s
                        scaleY = s
                        val b = anim.contentBlur.value
                        renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                    }
                }
            ) {
                CompositionLocalProvider(LocalPillFrame provides shownPlan.frame) {
                    ModeContent(displayedMode, live, media, mockSeconds)
                }
            }
        }
    }
}

/** 0..1 progress of the shape along a morph (by height when it changes, else width). */
private fun morphProgress(m: Morph, anim: IslandAnim): Float {
    val p = if (abs(m.endH - m.startH) > 1f) (anim.h.value - m.startH) / (m.endH - m.startH)
    else if (abs(m.endW - m.startW) > 1f) (anim.w.value - m.startW) / (m.endW - m.startW)
    else 1f
    return p.coerceIn(0f, 1f)
}

/** Content laid out once at its final size (no reflow per frame), positioned inside the shape. */
@Composable
private fun ContentLayer(
    rect: IslandRectPx,
    align: Alignment,
    layer: Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val w = with(density) { rect.w.toDp() }
    val h = with(density) { rect.h.toDp() }
    Box(
        Modifier
            .fillMaxSize()
            .wrapContentSize(align = align, unbounded = true)
            .requiredSize(w, h)
            .then(layer),
        content = content
    )
}

@Composable
private fun ModeContent(mode: IslandMode, live: IslandLive?, media: IslandMediaInfo?, mockSeconds: Int) {
    val mockClock = formatClock(mockSeconds)
    when (mode) {
        IslandMode.HIDDEN -> Box(Modifier.fillMaxSize())
        IslandMode.COMPACT -> CompactCallContent(live, mockClock)
        IslandMode.WIDE -> WideChargingContent()
        IslandMode.EXPANDED -> CardFrame { ExpandedCallContent(live, mockClock) }
        IslandMode.MUSIC -> MusicContent(media, mockElapsedSeconds = 44 + mockSeconds - 5)
        IslandMode.COMPACT_MUSIC -> CompactMusicContent(media)
        IslandMode.INCOMING_PILL -> IncomingPillContent()
        IslandMode.TIMER_PILL -> TimerPillContent(live, mockSeconds)
        IslandMode.TIMER_CARD -> CardFrame { TimerCardContent(live, mockSeconds) }
        IslandMode.REC_PILL -> RecPillContent(live, mockClock)
        IslandMode.REC_CARD -> CardFrame { RecCardContent(live, mockClock) }
    }
}

private fun formatClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

private val DigitsStyle = TextStyle(fontFeatureSettings = "tnum")

/** Time in the small pills: white, semibold, about the status-bar clock size (Xiaomi refs). */
private val PillTimeSize = 15.sp

/** "Incoming call" text size in the long incoming pill. */
private val IncomingTextSize = 12.5.sp

/** The live time a counter pill shows on the right of the camera (null for non-counter modes). */
private fun pillTimeText(mode: IslandMode, live: IslandLive?, mockSeconds: Int): String? {
    val now = System.currentTimeMillis()
    return when (mode) {
        IslandMode.COMPACT -> if (live == null) formatClock(mockSeconds) else live.timeText(now) ?: live.text.ifBlank { "Call" }
        IslandMode.TIMER_PILL -> timerText(live, now, mockSeconds, live?.kind == LiveKind.STOPWATCH)
        IslandMode.REC_PILL -> recText(live, now, formatClock(mockSeconds))
        else -> null
    }
}

/** Width of the left-hand icon / waveform of a counter pill. */
private fun pillLeftDp(mode: IslandMode): Float = if (mode == IslandMode.REC_PILL) 34f else 18f

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

/** Per-frame clock (uptime ms) for animated icons; frozen while [running] is false. Read it in draw. */
@Composable
private fun rememberFrameClock(running: Boolean): State<Long> {
    val st = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(running) {
        if (running) {
            while (true) {
                withFrameMillis { st.longValue = SystemClock.uptimeMillis() }
            }
        }
    }
    return st
}

/** Soft pulse 0..1 (incoming-call icon). */
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

/**
 * Compact pill layout from the [PillFrame] the layout computed: a fixed black padding at both rounded
 * ends, the camera hole kept clear, and content pinned to the outer sides (left content starts after
 * the padding, right content ends before it). With a bubble, a counter pill puts both right of the camera.
 */
@Composable
private fun PillSides(
    left: @Composable BoxScope.() -> Unit,
    right: @Composable BoxScope.() -> Unit
) {
    val frame = LocalPillFrame.current
    Layout(
        content = {
            Box(contentAlignment = Alignment.CenterStart, content = left)
            Box(contentAlignment = Alignment.CenterEnd, content = right)
        },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight
        val pad = frame?.padPx ?: (12f * density)
        val gap = frame?.gapPx ?: (30f * density)
        val cam = frame?.camLocalPx ?: (w / 2f)
        val (lx, lw, rx, rw) = if (frame?.rightOfCamera == true) {
            val start = cam + gap / 2f
            val avail = (w - pad - start).coerceAtLeast(0f)
            listOf(start, avail, start, avail)
        } else {
            listOf(pad, (cam - gap / 2f - pad).coerceAtLeast(0f), cam + gap / 2f, (w - pad - cam - gap / 2f).coerceAtLeast(0f))
        }
        val lp = measurables[0].measure(Constraints(0, lw.roundToInt(), 0, h))
        val rightMax = if (frame?.rightOfCamera == true) (rw - lp.width).coerceAtLeast(0f) else rw
        val rp = measurables[1].measure(Constraints(0, rightMax.roundToInt(), 0, h))
        layout(constraints.maxWidth, h) {
            lp.place(lx.roundToInt(), (h - lp.height) / 2)
            rp.place((rx + rw - rp.width).roundToInt(), (h - rp.height) / 2)
        }
    }
}

/** Expanded card frame: the content plus the small grey handle bar at the bottom centre. */
@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(bottom = 6.dp)) { content() }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 7.dp)
                .width(34.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(IslandSpec.HandleGrey)
        )
    }
}

// ---- Calls ------------------------------------------------------------------------------------

@Composable
private fun CompactCallContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val right = when {
        live == null -> mockTime
        else -> live.timeText(now) ?: live.text.ifBlank { "Call" }
    }
    PillSides(
        left = {
            Icon(Icons.Rounded.Call, null, tint = IslandSpec.CallGreen, modifier = Modifier.size(18.dp))
        },
        right = {
            Text(
                text = right,
                color = IslandSpec.TextPrimary,
                fontSize = PillTimeSize,
                fontWeight = FontWeight.SemiBold,
                style = DigitsStyle,
                maxLines = 1,
                softWrap = false,
                overflow = if (right.any { it.isDigit() }) TextOverflow.Clip else TextOverflow.Ellipsis
            )
        }
    )
}

/** Incoming call, first stage: long pill, green phone with a green glow + "Incoming call". */
@Composable
private fun IncomingPillContent() {
    val pulse = rememberPulse(650)
    Box(Modifier.fillMaxSize()) {
        // Green glow bleeding in from the left edge.
        Box(
            Modifier
                .fillMaxHeight()
                .width(84.dp)
                .graphicsLayer { alpha = 0.55f + 0.45f * pulse }
                .background(
                    Brush.horizontalGradient(
                        listOf(IslandSpec.CallGreen.copy(alpha = 0.55f), Color.Transparent)
                    )
                )
        )
        PillSides(
            left = {
                Icon(
                    Icons.Rounded.Call, null, tint = IslandSpec.CallGreen,
                    modifier = Modifier.size(19.dp).graphicsLayer {
                        rotationZ = (pulse - 0.5f) * 22f
                    }
                )
            },
            right = {
                Text(
                    text = "Incoming call",
                    color = IslandSpec.TextPrimary,
                    fontSize = IncomingTextSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                maxLines = 1,
                softWrap = false
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

/** Tappable area whose bounds are reported for tap routing. */
@Composable
private fun ButtonBox(button: IslandButton, sizeDp: Int, content: @Composable BoxScope.() -> Unit) {
    val touchPad = with(LocalDensity.current) { 6.dp.toPx() }
    Box(
        modifier = Modifier.size(sizeDp.dp).reportButton(button, touchPad),
        contentAlignment = Alignment.Center,
        content = content
    )
}

/** Round filled icon button. */
@Composable
private fun RoundButton(
    button: IslandButton,
    sizeDp: Int,
    fill: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color = IslandSpec.TextPrimary
) {
    ButtonBox(button, sizeDp) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size((sizeDp * 0.48f).dp))
        }
    }
}

@Composable
private fun ExpandedCallContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val name = live?.title?.ifBlank { null } ?: if (live == null) "Xiaomi" else "Call"
    val time = if (live == null) mockTime else live.timeText(now) ?: live.text
    val speakerUnsupported by IslandCallControl.speakerUnsupported.collectAsState()
    val showSpeaker = live == null || live.action(ActionRole.SPEAKER) != null ||
            (live.kind == LiveKind.CALL && live.sbnKey !in speakerUnsupported)
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 22.dp, end = 14.dp),
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
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = time, color = IslandSpec.TextSecondary, fontSize = 14.sp, style = DigitsStyle, maxLines = 1)
        }
        if (showSpeaker) {
            RoundButton(IslandButton.SPEAKER, 48, IslandSpec.ButtonGrey, Icons.AutoMirrored.Rounded.VolumeUp)
            Spacer(Modifier.width(12.dp))
        }
        RoundButton(IslandButton.HANG_UP, 48, IslandSpec.HangUpRed, Icons.Rounded.CallEnd)
    }
}

// ---- Timer / stopwatch --------------------------------------------------------------------------

private const val MOCK_TIMER_TOTAL_S = 300L

private fun timerText(live: IslandLive?, now: Long, mockSeconds: Int, stopwatch: Boolean): String =
    if (live == null) formatDuration(if (stopwatch) mockSeconds.toLong() else (MOCK_TIMER_TOTAL_S - mockSeconds).coerceAtLeast(0L))
    else live.timeText(now) ?: "00:00"

/** Animated blue hourglass: sand drains top -> bottom, then it flips. */
@Composable
private fun AnimatedHourglass(sizeDp: Dp, running: Boolean, color: Color = IslandSpec.TimerBlue) {
    val clock = rememberFrameClock(running)
    Canvas(Modifier.size(sizeDp)) {
        val s = size.minDimension
        val cycle = 3200L
        val drainMs = 2700f
        val t = (clock.value % cycle).toFloat()
        val drained: Float
        val angle: Float
        if (!running) {
            drained = 0.45f
            angle = 0f
        } else if (t < drainMs) {
            drained = t / drainMs
            angle = 0f
        } else {
            drained = 1f
            angle = 180f * ((t - drainMs) / (cycle - drainMs))
        }
        val ox = (size.width - s) / 2f
        val oy = (size.height - s) / 2f
        fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        rotate(angle, pivot = p(0.5f, 0.5f)) {
            // Frame bars
            drawRoundRect(color, topLeft = p(0.16f, 0.05f), size = Size(0.68f * s, 0.09f * s), cornerRadius = CornerRadius(0.04f * s))
            drawRoundRect(color, topLeft = p(0.16f, 0.86f), size = Size(0.68f * s, 0.09f * s), cornerRadius = CornerRadius(0.04f * s))
            // Glass outline
            val glass = Path().apply {
                moveTo(ox + 0.22f * s, oy + 0.14f * s)
                lineTo(ox + 0.78f * s, oy + 0.14f * s)
                lineTo(ox + 0.53f * s, oy + 0.5f * s)
                lineTo(ox + 0.78f * s, oy + 0.86f * s)
                lineTo(ox + 0.22f * s, oy + 0.86f * s)
                lineTo(ox + 0.47f * s, oy + 0.5f * s)
                close()
            }
            drawPath(glass, color.copy(alpha = 0.55f), style = Stroke(width = 0.06f * s))
            // Top sand (shrinking triangle near the neck)
            val a = 1f - drained
            if (a > 0.02f) {
                val top = 0.5f - 0.33f * a
                val hw = 0.26f * a
                drawPath(Path().apply {
                    moveTo(ox + (0.5f - hw) * s, oy + top * s)
                    lineTo(ox + (0.5f + hw) * s, oy + top * s)
                    lineTo(ox + 0.5f * s, oy + 0.5f * s)
                    close()
                }, color)
            }
            // Bottom pile
            val b = drained
            if (b > 0.02f) {
                val apex = 0.86f - 0.3f * b
                val hw = 0.27f * minOf(1f, 0.4f + b)
                drawPath(Path().apply {
                    moveTo(ox + (0.5f - hw) * s, oy + 0.86f * s)
                    lineTo(ox + (0.5f + hw) * s, oy + 0.86f * s)
                    lineTo(ox + 0.5f * s, oy + apex * s)
                    close()
                }, color)
            }
            // Falling stream
            if (running && angle == 0f && a > 0.02f) {
                drawLine(color, p(0.5f, 0.5f), p(0.5f, 0.86f - 0.3f * b), strokeWidth = 0.035f * s)
            }
        }
    }
}

/** Animated blue stopwatch: the hand sweeps once per second while running. */
@Composable
private fun AnimatedStopwatch(sizeDp: Dp, running: Boolean, elapsedMs: () -> Long) {
    val clock = rememberFrameClock(running)
    Canvas(Modifier.size(sizeDp)) {
        clock.value // redraw every frame while running
        val s = size.minDimension
        val ox = (size.width - s) / 2f
        val oy = (size.height - s) / 2f
        val c = Offset(ox + 0.5f * s, oy + 0.56f * s)
        val blue = IslandSpec.TimerBlue
        val dark = Color(0xFF0B1D45)
        // crown + side button
        drawRoundRect(blue, topLeft = Offset(ox + 0.4f * s, oy + 0.03f * s), size = Size(0.2f * s, 0.08f * s), cornerRadius = CornerRadius(0.03f * s))
        drawRect(blue, topLeft = Offset(ox + 0.465f * s, oy + 0.1f * s), size = Size(0.07f * s, 0.08f * s))
        rotate(42f, pivot = c) {
            drawRoundRect(blue, topLeft = Offset(c.x - 0.04f * s, c.y - 0.47f * s), size = Size(0.08f * s, 0.1f * s), cornerRadius = CornerRadius(0.02f * s))
        }
        drawCircle(blue, radius = 0.4f * s, center = c)
        val frac = ((elapsedMs() % 1000L).toFloat() / 1000f)
        rotate(frac * 360f, pivot = c) {
            drawLine(dark, c, Offset(c.x, c.y - 0.27f * s), strokeWidth = 0.08f * s, cap = StrokeCap.Round)
        }
        drawCircle(dark, radius = 0.06f * s, center = c)
    }
}

/** Pause/play inside a blue ring that depletes with the remaining time (timer). */
@Composable
private fun ProgressRingButton(button: IslandButton, sizeDp: Int, fraction: Float, paused: Boolean) {
    ButtonBox(button, sizeDp) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2f + 1f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(
                IslandSpec.TimerBlue.copy(alpha = 0.25f), 0f, 360f, false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke)
            )
            drawArc(
                IslandSpec.TimerBlue, -90f, 360f * fraction.coerceIn(0f, 1f), false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round)
            )
        }
        Icon(
            if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null,
            tint = IslandSpec.TimerBlue,
            modifier = Modifier.size((sizeDp * 0.46f).dp)
        )
    }
}

@Composable
private fun TimerPillContent(live: IslandLive?, mockSeconds: Int) {
    val now = rememberNow()
    val stopwatch = live?.kind == LiveKind.STOPWATCH
    val paused = live?.paused == true
    PillSides(
        left = {
            if (stopwatch) AnimatedStopwatch(18.dp, !paused) { live?.elapsedMs() ?: 0L }
            else AnimatedHourglass(18.dp, !paused)
        },
        right = {
            Text(
                text = timerText(live, now, mockSeconds, stopwatch),
                color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
                fontSize = PillTimeSize,
                fontWeight = FontWeight.SemiBold,
                style = DigitsStyle,
                maxLines = 1,
                softWrap = false
            )
        }
    )
}

@Composable
private fun TimerCardContent(live: IslandLive?, mockSeconds: Int) {
    val now = rememberNow(200L)
    val stopwatch = live?.kind == LiveKind.STOPWATCH
    val paused = live?.paused == true
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (stopwatch) {
            AnimatedStopwatch(40.dp, !paused) { live?.elapsedMs() ?: (mockSeconds * 1000L) }
        } else {
            AnimatedHourglass(40.dp, !paused)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = timerText(live, now, mockSeconds, stopwatch),
                color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                style = DigitsStyle,
                maxLines = 1,
                softWrap = false
            )
            Text(if (stopwatch) "Stopwatch" else "Timer", color = IslandSpec.TextSecondary, fontSize = 14.sp, maxLines = 1)
        }
        if (stopwatch) {
            // Lap (running) / reset (paused), then pause / resume — grey circles like HyperOS 3.
            RoundButton(
                IslandButton.SECONDARY, 50, IslandSpec.ButtonGrey,
                if (paused) Icons.Rounded.Refresh else Icons.Rounded.Flag
            )
            Spacer(Modifier.width(12.dp))
            RoundButton(
                IslandButton.PRIMARY, 50, IslandSpec.ButtonGrey,
                if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause
            )
        } else {
            val fraction = if (live == null) {
                ((MOCK_TIMER_TOTAL_S - mockSeconds).coerceAtLeast(0L).toFloat() / MOCK_TIMER_TOTAL_S)
            } else live.remainingFraction(now) ?: 1f
            ProgressRingButton(IslandButton.PRIMARY, 52, fraction, paused)
            Spacer(Modifier.width(12.dp))
            RoundButton(IslandButton.SECONDARY, 50, IslandSpec.ButtonGrey, Icons.Rounded.Close)
        }
    }
}

// ---- Recorder -----------------------------------------------------------------------------------

private fun hash01(i: Long): Float {
    val v = sin(i.toDouble() * 12.9898) * 43758.5453
    return (v - floor(v)).toFloat()
}

/**
 * Live recording waveform: white bars (already recorded) scroll left past a red playhead;
 * to the right of it only small grey stubs. Frozen while paused.
 */
@Composable
private fun LiveWaveform(widthDp: Dp, heightDp: Dp, running: Boolean, playhead: Float = 0.62f) {
    val clock = rememberFrameClock(running)
    Canvas(Modifier.width(widthDp).height(heightDp)) {
        val step = 4.dp.toPx()
        val barW = 2.dp.toPx()
        val count = (size.width / step).toInt() + 2
        val phase = clock.value / 110.0 // one bar every 110 ms
        val whole = floor(phase).toLong()
        val frac = (phase - whole).toFloat()
        val headX = size.width * playhead
        val headSlot = (headX / step).toInt()
        for (i in 0 until count) {
            val x = (i - frac) * step
            if (x < -barW || x > size.width) continue
            val g = whole + (i - headSlot)
            val h: Float
            val col: Color
            if (x + barW / 2f <= headX) {
                val amp = 0.22f + 0.78f * hash01(g)
                // a gentle envelope so it looks like speech
                h = size.height * amp * (0.55f + 0.45f * hash01(g / 5))
                col = Color.White.copy(alpha = if (x < size.width * 0.15f) (x / (size.width * 0.15f)).coerceIn(0.15f, 1f) else 1f)
            } else {
                h = size.height * 0.2f
                col = Color.White.copy(alpha = 0.35f)
            }
            val top = (size.height - h) / 2f
            drawLine(col, Offset(x + barW / 2f, top), Offset(x + barW / 2f, top + h), strokeWidth = barW, cap = StrokeCap.Round)
        }
        drawLine(IslandSpec.RecRed, Offset(headX, 0f), Offset(headX, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun recText(live: IslandLive?, now: Long, mockTime: String): String =
    if (live == null) mockTime else live.timeText(now) ?: "00:00"

@Composable
private fun RecPillContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val paused = live?.paused == true
    PillSides(
        left = { LiveWaveform(34.dp, 16.dp, running = !paused) },
        right = {
            Text(
                text = recText(live, now, mockTime),
                color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
                fontSize = PillTimeSize,
                fontWeight = FontWeight.SemiBold,
                style = DigitsStyle,
                maxLines = 1,
                softWrap = false
            )
        }
    )
}

/** Stop: red rounded square inside a white ring. */
@Composable
private fun StopRingButton(button: IslandButton, sizeDp: Int) {
    ButtonBox(button, sizeDp) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
            drawCircle(Color.White, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            val sq = size.minDimension * 0.36f
            drawRoundRect(
                IslandSpec.RecRed,
                topLeft = Offset((size.width - sq) / 2f, (size.height - sq) / 2f),
                size = Size(sq, sq),
                cornerRadius = CornerRadius(sq * 0.22f)
            )
        }
    }
}

@Composable
private fun RecCardContent(live: IslandLive?, mockTime: String) {
    val now = rememberNow()
    val paused = live?.paused == true
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 20.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveWaveform(74.dp, 40.dp, running = !paused, playhead = 0.68f)
        Spacer(Modifier.width(14.dp))
        Text(
            text = recText(live, now, mockTime),
            color = if (paused) IslandSpec.TextSecondary else IslandSpec.TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            style = DigitsStyle,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        RoundButton(
            IslandButton.PRIMARY, 50, IslandSpec.ButtonGrey,
            if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause
        )
        Spacer(Modifier.width(12.dp))
        StopRingButton(IslandButton.SECONDARY, 52)
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
    PillSides(
        left = { AlbumArt(media?.art, sizeDp = 22f, radiusDp = 6f) },
        right = { SoundWave(playing = playing, color = accent) }
    )
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

// ---- Second-activity bubble ------------------------------------------------------------------

/** Content of the black circle left of the pill. */
@Composable
private fun BubbleContent(b: IslandBubble, media: IslandMediaInfo?, mockSeconds: Int) {
    val live = b.live
    when (b.kind) {
        BubbleKind.MUSIC -> {
            val art = media?.art
            if (art != null) {
                Image(
                    bitmap = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().padding(3.dp).clip(CircleShape)
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = media?.accent ?: IslandSpec.MusicGreen, modifier = Modifier.size(18.dp))
            }
        }
        BubbleKind.CALL -> Icon(Icons.Rounded.Call, null, tint = IslandSpec.CallGreen, modifier = Modifier.size(18.dp))
        BubbleKind.INCOMING -> {
            val pulse = rememberPulse(650)
            Icon(
                Icons.Rounded.Call, null, tint = IslandSpec.CallGreen,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = (pulse - 0.5f) * 22f }
            )
        }
        BubbleKind.TIMER -> {
            val now = rememberNow()
            val paused = live?.paused == true
            val fraction = bubbleTimerFraction(live, now, mockSeconds)
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(24.dp)) {
                    val stroke = 2.dp.toPx()
                    drawCircle(IslandSpec.TimerBlue.copy(alpha = 0.25f), radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
                    drawArc(
                        color = IslandSpec.TimerBlue,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                AnimatedHourglass(12.dp, !paused)
            }
        }
        BubbleKind.STOPWATCH -> {
            val paused = live?.paused == true
            AnimatedStopwatch(17.dp, !paused) { live?.elapsedMs() ?: (mockSeconds * 1000L) }
        }
        BubbleKind.RECORDER -> {
            val paused = live?.paused == true
            val pulse = rememberPulse(600)
            Box(
                Modifier
                    .size(10.dp)
                    .graphicsLayer {
                        val k = if (paused) 1f else 0.8f + 0.35f * pulse
                        scaleX = k
                        scaleY = k
                        alpha = if (paused) 0.5f else 0.7f + 0.3f * pulse
                    }
                    .clip(CircleShape)
                    .background(IslandSpec.RecRed)
            )
        }
    }
}

/** Remaining fraction (1 = full, 0 = done) of a timer, for the bubble's depleting ring. */
private fun bubbleTimerFraction(live: IslandLive?, now: Long, mockSeconds: Int): Float {
    if (live == null) {
        val left = (MOCK_TIMER_TOTAL_S - mockSeconds % MOCK_TIMER_TOTAL_S).toFloat()
        return (left / MOCK_TIMER_TOTAL_S).coerceIn(0f, 1f)
    }
    val total = live.totalMs ?: return 1f
    val left = live.remainingMs(now) ?: return 1f
    if (total <= 0L) return 1f
    return (left.toFloat() / total).coerceIn(0f, 1f)
}

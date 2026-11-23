package com.d4viddf.hyperbridge.island

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/** The states the self-drawn island can be in. */
enum class IslandMode {
    HIDDEN,

    /** Compact call pill: green phone icon left of the camera, timer right of it. */
    COMPACT,

    /** Wide charging pill (mock): "Quick charging" left, "69%" + bolt right. */
    WIDE,

    /** Expanded call card below the camera: name, timer, speaker, red hang-up. */
    EXPANDED,

    /** Expanded music card below the camera: art, title, artist, controls, progress. */
    MUSIC,

    /** Compact music pill: album art left of the camera, sound-wave bars right of it. */
    COMPACT_MUSIC,

    /** Incoming call long pill (first stage): green phone with glow + "Incoming call". */
    INCOMING_PILL,

    /** Compact timer / stopwatch pill: blue icon left, running time right. */
    TIMER_PILL,

    /** Expanded timer / stopwatch card: icon, big time + label, pause/resume + cancel/lap. */
    TIMER_CARD,

    /** Compact recorder pill: pulsing red dot left, elapsed time right. */
    REC_PILL,

    /** Expanded recorder card: red dot, time, pause/resume + stop. */
    REC_CARD
}

/** True for the big cards that sit below the camera row. */
fun IslandMode.isCard(): Boolean = this == IslandMode.EXPANDED || this == IslandMode.MUSIC ||
        this == IslandMode.TIMER_CARD || this == IslandMode.REC_CARD

/** Content kind a mode stands for (used when Island Test mock buttons drive the island). */
fun IslandMode.mockLiveKind(): LiveKind? = when (this) {
    IslandMode.COMPACT, IslandMode.EXPANDED -> LiveKind.CALL
    IslandMode.INCOMING_PILL -> LiveKind.INCOMING_CALL
    IslandMode.TIMER_PILL, IslandMode.TIMER_CARD -> LiveKind.TIMER
    IslandMode.REC_PILL, IslandMode.REC_CARD -> LiveKind.RECORDER
    else -> null
}

/** True for the music states (real or mock music). */
fun IslandMode.isMusic(): Boolean = this == IslandMode.MUSIC || this == IslandMode.COMPACT_MUSIC

/** Card -> its pill (pills map to themselves). */
fun IslandMode.compact(): IslandMode = when (this) {
    IslandMode.EXPANDED -> IslandMode.COMPACT
    IslandMode.MUSIC -> IslandMode.COMPACT_MUSIC
    IslandMode.TIMER_CARD -> IslandMode.TIMER_PILL
    IslandMode.REC_CARD -> IslandMode.REC_PILL
    else -> this
}

/** Pill -> its card (null when the pill has no card). */
fun IslandMode.card(): IslandMode? = when (this) {
    IslandMode.COMPACT -> IslandMode.EXPANDED
    IslandMode.COMPACT_MUSIC -> IslandMode.MUSIC
    IslandMode.TIMER_PILL -> IslandMode.TIMER_CARD
    IslandMode.REC_PILL -> IslandMode.REC_CARD
    else -> null
}

/** Camera / screen geometry, in physical pixels of the display. */
data class IslandGeometry(
    val screenWidthPx: Int,
    val statusBarHeightPx: Int,
    val cameraCenterXPx: Float,
    val cameraCenterYPx: Float,
    val cameraDiameterPx: Float,
    val fromCutout: Boolean
)

/**
 * User calibration. Offsets are in physical px; sizes in dp.
 * A negative size means "auto" (measured from the reference videos).
 * A negative radius means fully rounded.
 */
data class IslandCalibration(
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val pillHeightDp: Float = -1f,
    val compactWidthDp: Float = -1f,
    val wideWidthDp: Float = -1f,
    val radiusDp: Float = -1f,
    /** Black space between the pill's rounded ends and its content (icon / waveform / number). */
    val pillPadDp: Float = 12f,
    /** Incoming-call long pill width (negative = auto). */
    val incomingWidthDp: Float = -1f,
    /** Second-activity bubble diameter (negative = same as the pill height). */
    val bubbleSizeDp: Float = -1f,
    /** Gap between the bubble and the pill. */
    val bubbleGapDp: Float = 6f,
    /** Horizontal offset of the pill + bubble group (when centred). */
    val groupOffsetXPx: Float = 0f,
    /** Extra manual shift of the pill when its counter sits right of the camera. */
    val counterShiftPx: Float = 0f,
    /** With a bubble, a counter pill moves so its icon AND time sit fully right of the camera. */
    val counterRight: Boolean = true,
    /** Bubble on the right of the pill instead of the left. */
    val bubbleRight: Boolean = false,
    /** Per-type small pill overrides (missing / negative fields = inherit the global values above). */
    val pills: Map<PillType, PillDims> = emptyMap(),
    /** Per-type expanded card overrides (negative = auto). */
    val cards: Map<CardType, CardDims> = emptyMap()
) {
    fun pill(t: PillType): PillDims = pills[t] ?: PillDims()
    fun card(t: CardType): CardDims = cards[t] ?: CardDims()
}

/** The small pills that can be sized separately. */
enum class PillType(val label: String) {
    MUSIC("Music"), CALL("Call"), TIMER("Timer"), STOPWATCH("Stopwatch"), RECORDER("Recorder"), INCOMING("Incoming call");

    /** Default icon size (dp): icon / album art / waveform height. */
    val defaultIconDp: Float
        get() = when (this) {
            MUSIC -> 22f
            RECORDER -> 16f
            INCOMING -> 19f
            else -> 18f
        }

    /** Default text size (sp): time / "Incoming call"; for music = sound-wave height (dp). */
    val defaultTextSp: Float
        get() = when (this) {
            MUSIC -> 14f
            INCOMING -> 12.5f
            else -> 15f
        }
}

/** The expanded cards that can be sized separately. */
enum class CardType(val label: String) {
    MUSIC("Music"), CALL("Call"), TIMER("Timer"), STOPWATCH("Stopwatch"), RECORDER("Recorder");

    val heightRatio: Float
        get() = when (this) {
            MUSIC -> IslandLayout.MUSIC_HEIGHT_RATIO
            CALL -> IslandLayout.CALL_HEIGHT_RATIO
            else -> IslandLayout.TIMER_HEIGHT_RATIO
        }
}

/**
 * One small pill's own size. Negative = inherit (width / height / padding from the global calibration,
 * icon / text from the type's default). [radiusDp]: -2 = inherit, -1 = fully rounded, >= 0 explicit.
 * [offsetXPx] is added to the global horizontal offset.
 */
data class PillDims(
    val widthDp: Float = -1f,
    val heightDp: Float = -1f,
    val radiusDp: Float = -2f,
    val padDp: Float = -1f,
    val iconDp: Float = -1f,
    val textSp: Float = -1f,
    val offsetXPx: Float = 0f
)

/** One expanded card's own size (negative = auto from the reference videos). */
data class CardDims(val widthDp: Float = -1f, val heightDp: Float = -1f, val radiusDp: Float = -1f)

/** Card background: real blur strength, black tint over it, or plain solid black. */
data class IslandLook(
    /** Blur radius behind cards, dp. */
    val blurDp: Float = 28f,
    /** Opacity of the black tint drawn over the blur (0..1). */
    val tint: Float = 0.55f,
    /** Plain black card background, no blur at all. */
    val solid: Boolean = false,
    /** While a card is open, the bubble glows softly in its own colour. */
    val bubbleGlow: Boolean = true,
    /** Blur follows the card per frame by moving its view (smooth); false = resize the window (fallback). */
    val blurFollowView: Boolean = true,
    /** Outgoing call pill (ringing on the other side): soft breathing glow + light sweep + rings. */
    val outgoingGlow: Boolean = true,
    /** Strength of that glow, 0..1. */
    val outgoingGlowAmount: Float = 0.7f
)

/**
 * Feel of the pill <-> card morph, tunable live from Island Test.
 * bounce = spring dampingRatio (lower = bouncier), speed = spring stiffness.
 */
data class IslandMotion(
    /** Spring dampingRatio for the whole shape (lower = bouncier). */
    val bounce: Float = 0.62f,
    /** Spring stiffness for the whole shape (higher = faster). */
    val speed: Float = 223f,
    /** Jelly squash on expand / collapse. */
    val jelly: Boolean = true,
    /** Jelly squash amount (fraction of size). */
    val jellyAmount: Float = 0.05f,
    /** Max blur of content that is appearing / melting away, in dp. */
    val blurDp: Float = 14f
)

/** Which island contents hide the status-bar notification icons (all OFF by default). */
data class IconHidePrefs(
    val call: Boolean = false,
    val timer: Boolean = false,
    val recorder: Boolean = false,
    /** Hide whenever two activities run at once (pill + bubble), whatever they are. */
    val multi: Boolean = false,
    /** During any call: hide the clock too (the call chip sits in the clock area, may go with it). */
    val clockCalls: Boolean = false,
    /** During any call: hide the whole status bar (clock + system icons + notification icons). */
    val allCalls: Boolean = false,
    /** During any call: also send this extra disable flag found in `cmd statusbar help` (chip-like). */
    val chipCalls: Boolean = false,
    /** The chip-like flag name found on this phone ("" = none found). */
    val chipFlag: String = ""
) {
    /** The `send-disable-flag` flags wanted while a call is live (empty = nothing extra). */
    fun callFlags(): Set<String> = buildSet {
        if (allCalls) { add("clock"); add("system-icons"); add("notification-icons") }
        if (clockCalls) add("clock")
        if (chipCalls && chipFlag.isNotBlank()) add(chipFlag)
    }

    fun wants(kind: LiveKind?): Boolean = when (kind) {
        LiveKind.CALL, LiveKind.INCOMING_CALL -> call
        LiveKind.TIMER, LiveKind.STOPWATCH -> timer
        LiveKind.RECORDER -> recorder
        null -> false
    }
}

/** A rounded rect described by its horizontal centre, top edge, size and radius (all px). */
data class IslandRectPx(val cx: Float, val top: Float, val w: Float, val h: Float, val r: Float) {
    val left: Float get() = cx - w / 2f
}

/** Tappable controls on the cards; their on-screen bounds are reported by the overlay. */
enum class IslandButton { PREV, PLAY_PAUSE, NEXT, ANSWER, DECLINE, HANG_UP, SPEAKER, PRIMARY, SECONDARY }

/** Widths (px) of a pill's left content (icon / waveform / art) and right content (time / text). */
data class PillSlots(val leftPx: Float, val rightPx: Float)

/**
 * Where the pill content goes: [camLocalPx] = camera centre measured from the pill's left edge,
 * [padPx] = black space at both rounded ends, [gapPx] = room kept free around the camera hole.
 * [rightOfCamera] = both contents sit right of the camera (counter pill next to a bubble).
 */
data class PillFrame(
    val widthPx: Float,
    val camLocalPx: Float,
    val padPx: Float,
    val gapPx: Float,
    val rightOfCamera: Boolean,
    /** Icon / art / waveform size for this pill type (dp). */
    val iconDp: Float = 18f,
    /** Text / time size for this pill type (sp; music: wave height dp). */
    val textSp: Float = 15f
)

/** What a second-activity bubble shows. */
enum class BubbleKind { MUSIC, CALL, INCOMING, TIMER, STOPWATCH, RECORDER }

fun LiveKind?.bubbleKind(): BubbleKind = when (this) {
    null -> BubbleKind.MUSIC
    LiveKind.CALL -> BubbleKind.CALL
    LiveKind.INCOMING_CALL -> BubbleKind.INCOMING
    LiveKind.TIMER -> BubbleKind.TIMER
    LiveKind.STOPWATCH -> BubbleKind.STOPWATCH
    LiveKind.RECORDER -> BubbleKind.RECORDER
}

/** The second activity (id null = Island Test mock). */
data class IslandBubble(val id: String?, val kind: BubbleKind, val live: IslandLive?, val owner: String?)

/** Real cross-window blur behind cards: [real] = available now; [label] explains. */
data class BlurState(val real: Boolean, val label: String)

/** The island's target shapes: the main pill / card, its content frame, and the optional bubble. */
data class IslandPlan(val main: IslandRectPx, val frame: PillFrame?, val bubble: IslandRectPx?)

/**
 * Measured from the HyperOS 3 reference videos (see hyperisland-ref/SPEC.md).
 * Ratios are relative to the screen width so the island scales per device.
 */
object IslandLayout {
    const val PILL_HEIGHT_RATIO = 0.083f      // compact pill height / screen width
    const val COMPACT_WIDTH_RATIO = 0.347f    // compact (call / timer) pill width
    const val WIDE_WIDTH_RATIO = 0.53f        // wide (charging) pill width
    const val CARD_MARGIN_RATIO = 0.044f      // side margin of expanded cards
    const val CARD_RADIUS_RATIO = 0.055f      // corner radius of expanded cards
    const val CALL_HEIGHT_RATIO = 0.205f      // expanded call card height
    const val TIMER_HEIGHT_RATIO = 0.195f     // timer / stopwatch / recorder card height
    const val MUSIC_HEIGHT_RATIO = 0.42f      // expanded music card height

    /** Tallest card the Layout screen allows (fraction of the screen width); the windows are sized for it. */
    const val MAX_CARD_HEIGHT_RATIO = 0.62f

    /** Space between the icon and the time when both sit right of the camera. */
    private const val RIGHT_SPACING_DP = 7f

    fun autoPillHeightPx(g: IslandGeometry, density: Float): Float =
        max(g.screenWidthPx * PILL_HEIGHT_RATIO, g.cameraDiameterPx + 8f * density)

    fun pillHeightPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.pillHeightDp > 0f) c.pillHeightDp * density else autoPillHeightPx(g, density)

    fun compactWidthPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.compactWidthDp > 0f) c.compactWidthDp * density else g.screenWidthPx * COMPACT_WIDTH_RATIO

    fun wideWidthPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.wideWidthDp > 0f) c.wideWidthDp * density else g.screenWidthPx * WIDE_WIDTH_RATIO

    fun autoIncomingWidthPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        min(g.screenWidthPx * 0.66f, wideWidthPx(g, c, density) * 1.15f)

    fun incomingWidthPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.incomingWidthDp > 0f) c.incomingWidthDp * density else autoIncomingWidthPx(g, c, density)

    /** Which small pill type a mode shows ([stopwatch] splits the timer pill). */
    fun pillTypeOf(mode: IslandMode, stopwatch: Boolean): PillType? = when (mode.compact()) {
        IslandMode.COMPACT -> PillType.CALL
        IslandMode.COMPACT_MUSIC -> PillType.MUSIC
        IslandMode.INCOMING_PILL -> PillType.INCOMING
        IslandMode.TIMER_PILL -> if (stopwatch) PillType.STOPWATCH else PillType.TIMER
        IslandMode.REC_PILL -> PillType.RECORDER
        else -> null
    }

    fun cardTypeOf(mode: IslandMode, stopwatch: Boolean): CardType? = when (mode) {
        IslandMode.EXPANDED -> CardType.CALL
        IslandMode.MUSIC -> CardType.MUSIC
        IslandMode.TIMER_CARD -> if (stopwatch) CardType.STOPWATCH else CardType.TIMER
        IslandMode.REC_CARD -> CardType.RECORDER
        else -> null
    }

    /** A pill type's resolved size (every inherited field filled in), in px / dp / sp. */
    data class PillResolved(
        val widthPx: Float,
        val heightPx: Float,
        val radiusPx: Float,
        val padPx: Float,
        val iconDp: Float,
        val textSp: Float,
        val offsetXPx: Float,
        /** True when the user set a width for this type (the long incoming pill keeps it exactly). */
        val widthSet: Boolean
    )

    fun pillResolved(t: PillType, g: IslandGeometry, c: IslandCalibration, density: Float): PillResolved {
        val p = c.pill(t)
        val h = if (p.heightDp > 0f) p.heightDp * density else pillHeightPx(g, c, density)
        val baseW = if (t == PillType.INCOMING) incomingWidthPx(g, c, density) else compactWidthPx(g, c, density)
        val w = if (p.widthDp > 0f) p.widthDp * density else baseW
        val rDp = if (p.radiusDp > -1.5f) p.radiusDp else c.radiusDp
        val r = if (rDp >= 0f) min(rDp * density, h / 2f) else h / 2f
        val padDp = if (p.padDp >= 0f) p.padDp else c.pillPadDp
        return PillResolved(
            widthPx = w,
            heightPx = h,
            radiusPx = r,
            padPx = padDp.coerceIn(0f, 40f) * density,
            iconDp = if (p.iconDp > 0f) p.iconDp else t.defaultIconDp,
            textSp = if (p.textSp > 0f) p.textSp else t.defaultTextSp,
            offsetXPx = p.offsetXPx,
            widthSet = p.widthDp > 0f || (t == PillType.INCOMING && c.incomingWidthDp > 0f)
        )
    }

    /** Resolved card size in px. */
    fun cardRect(t: CardType, g: IslandGeometry, c: IslandCalibration, density: Float): IslandRectPx {
        val screenW = g.screenWidthPx.toFloat()
        val d = c.card(t)
        val margin = screenW * CARD_MARGIN_RATIO
        val top = max(g.statusBarHeightPx.toFloat(), g.cameraCenterYPx + g.cameraDiameterPx / 2f) + 6f * density
        val w = if (d.widthDp > 0f) min(d.widthDp * density, screenW) else screenW - margin * 2f
        val h = if (d.heightDp > 0f) min(d.heightDp * density, screenW * MAX_CARD_HEIGHT_RATIO) else screenW * t.heightRatio
        val r = if (d.radiusDp >= 0f) min(d.radiusDp * density, min(w, h) / 2f) else screenW * CARD_RADIUS_RATIO
        return IslandRectPx(cx = screenW / 2f + c.offsetXPx, top = top, w = w, h = h, r = r)
    }

    fun bubbleSizePx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.bubbleSizeDp > 0f) c.bubbleSizeDp * density else pillHeightPx(g, c, density)

    /** Room kept free around the camera hole inside a pill. */
    fun cameraGapPx(g: IslandGeometry, density: Float): Float = g.cameraDiameterPx + 10f * density

    fun isCounter(mode: IslandMode): Boolean =
        mode == IslandMode.COMPACT || mode == IslandMode.TIMER_PILL || mode == IslandMode.REC_PILL

    private fun radius(h: Float, c: IslandCalibration, density: Float): Float =
        if (c.radiusDp >= 0f) min(c.radiusDp * density, h / 2f) else h / 2f

    /**
     * Full plan for [mode]. [slots] = measured content widths (null = use the calibrated widths only),
     * [withBubble] = a second activity is shown as a circle beside the pill. For a card, the bubble keeps
     * the place it has next to that card's own pill, so it doesn't move when its partner expands.
     */
    fun plan(
        mode: IslandMode,
        g: IslandGeometry,
        c: IslandCalibration,
        density: Float,
        slots: PillSlots?,
        withBubble: Boolean,
        stopwatch: Boolean = false
    ): IslandPlan {
        if (mode == IslandMode.HIDDEN) return IslandPlan(camera(g, c, density), null, null)
        if (mode.isCard()) {
            val ct = cardTypeOf(mode, stopwatch) ?: CardType.CALL
            val bubble = if (withBubble) plan(mode.compact(), g, c, density, slots, true, stopwatch).bubble else null
            return IslandPlan(cardRect(ct, g, c, density), null, bubble)
        }
        val type = pillTypeOf(mode, stopwatch)
        val res = type?.let { pillResolved(it, g, c, density) }
        val h = res?.heightPx ?: pillHeightPx(g, c, density)
        val r = res?.radiusPx ?: radius(h, c, density)
        val pad = res?.padPx ?: (c.pillPadDp.coerceIn(0f, 40f) * density)
        val iconDp = res?.iconDp ?: 18f
        val textSp = res?.textSp ?: 15f
        val gap = cameraGapPx(g, density)
        val camX = g.cameraCenterXPx + c.offsetXPx
        val camY = g.cameraCenterYPx + c.offsetYPx
        val top = camY - h / 2f
        val shift = res?.offsetXPx ?: 0f
        val l = slots?.leftPx ?: 0f
        val rt = slots?.rightPx ?: 0f
        val base = when (mode) {
            IslandMode.WIDE -> wideWidthPx(g, c, density)
            else -> res?.widthPx ?: compactWidthPx(g, c, density)
        }
        val maxW = g.screenWidthPx * 0.9f
        // Centred on the camera: left content | camera | right content, `pad` at both ends.
        val centredNeed = 2f * (pad + max(l, rt)) + gap
        val bubble = withBubble && mode != IslandMode.WIDE

        fun frame(w: Float, camLocal: Float, right: Boolean) = PillFrame(w, camLocal, pad, gap, right, iconDp, textSp)

        if (!bubble) {
            val w = when {
                mode == IslandMode.WIDE -> base
                // The incoming width is the user's to choose; only the icon must still fit (text ellipsizes).
                mode == IslandMode.INCOMING_PILL && res?.widthSet == true -> max(base, 2f * (pad + l) + gap)
                else -> maxOf(base, centredNeed, 2f * (pad + l + gap / 2f + shift), 2f * (pad + rt + gap / 2f - shift))
            }.coerceAtMost(maxW)
            // A per-type horizontal shift moves the whole pill; the content keeps clear of the camera.
            val cx = camX + shift
            return IslandPlan(IslandRectPx(cx, top, w, h, r), frame(w, w / 2f - shift, false), null)
        }

        val b = bubbleSizePx(g, c, density)
        val bGap = c.bubbleGapDp.coerceIn(0f, 40f) * density
        val bubbleTop = camY - b / 2f
        val right = c.bubbleRight
        if (isCounter(mode) && c.counterRight) {
            // Counter next to a bubble: the pill's left end covers the camera, icon + time sit right of it.
            val w = max(pad + gap + l + RIGHT_SPACING_DP * density + rt + pad, 0f).coerceAtMost(maxW)
            val left = camX - gap / 2f - pad + c.counterShiftPx + shift
            val main = IslandRectPx(left + w / 2f, top, w, h, r)
            val bx = if (right) left + w + bGap + b / 2f else left - bGap - b / 2f
            val bub = IslandRectPx(bx, bubbleTop, b, b, b / 2f)
            return IslandPlan(main, frame(w, camX - left, true), bub)
        }
        // Bubble + pill centred together as one group; the pill still keeps its content clear of the camera.
        val off = c.groupOffsetXPx + shift
        val w = if (!right) maxOf(
            base,
            centredNeed,
            2f * (pad + l + gap / 2f + off) + b + bGap,
            2f * (pad + rt + gap / 2f - off) - b - bGap
        ) else maxOf(
            base,
            centredNeed,
            2f * (pad + l + gap / 2f + off) - b - bGap,
            2f * (pad + rt + gap / 2f - off) + b + bGap
        )
        val wc = w.coerceAtMost(maxW)
        val groupLeft = camX + off - (b + bGap + wc) / 2f
        val pillLeft = if (right) groupLeft else groupLeft + b + bGap
        val bubCx = if (right) groupLeft + wc + bGap + b / 2f else groupLeft + b / 2f
        val main = IslandRectPx(pillLeft + wc / 2f, top, wc, h, r)
        val bub = IslandRectPx(bubCx, bubbleTop, b, b, b / 2f)
        return IslandPlan(main, frame(wc, camX - pillLeft, false), bub)
    }

    /**
     * Invisible touch zone around the camera used to bring a swiped-away island back:
     * about the compact pill's width, and as tall as the status bar (or the pill, if taller).
     */
    fun restoreZone(g: IslandGeometry, c: IslandCalibration, density: Float): IslandRectPx {
        val h = pillHeightPx(g, c, density)
        val w = compactWidthPx(g, c, density)
        val camX = g.cameraCenterXPx + c.offsetXPx
        val camY = g.cameraCenterYPx + c.offsetYPx
        val bottom = max(g.statusBarHeightPx.toFloat(), camY + h / 2f)
        return IslandRectPx(camX, 0f, w, bottom, 0f)
    }

    /** The camera-sized black dot the island grows out of and shrinks back into. */
    fun camera(g: IslandGeometry, c: IslandCalibration, density: Float): IslandRectPx {
        val d = min(g.cameraDiameterPx, pillHeightPx(g, c, density))
        val camX = g.cameraCenterXPx + c.offsetXPx
        val camY = g.cameraCenterYPx + c.offsetYPx
        return IslandRectPx(camX, camY - d / 2f, d, d, d / 2f)
    }

    /** Short pill hugging the camera: the "shrink" step of a shrink-then-grow switch. */
    fun contracted(g: IslandGeometry, c: IslandCalibration, density: Float): IslandRectPx {
        val h = pillHeightPx(g, c, density) * 0.92f
        val w = max(h * 2.1f, g.cameraDiameterPx * 2.4f)
        val camX = g.cameraCenterXPx + c.offsetXPx
        val camY = g.cameraCenterYPx + c.offsetYPx
        return IslandRectPx(camX, camY - h / 2f, w, h, h / 2f)
    }

    /** Target without measured content / bubble (calibrated widths only). */
    fun target(mode: IslandMode, g: IslandGeometry, c: IslandCalibration, density: Float): IslandRectPx =
        plan(mode, g, c, density, null, false).main
}

/** Persists the calibration in SharedPreferences. */
object IslandCalibrationStore {
    private const val PREFS = "island_calibration"

    fun load(context: Context): IslandCalibration {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return IslandCalibration(
            offsetXPx = p.getFloat("offsetXPx", 0f),
            offsetYPx = p.getFloat("offsetYPx", 0f),
            pillHeightDp = p.getFloat("pillHeightDp", -1f),
            compactWidthDp = p.getFloat("compactWidthDp", -1f),
            wideWidthDp = p.getFloat("wideWidthDp", -1f),
            radiusDp = p.getFloat("radiusDp", -1f),
            pillPadDp = p.getFloat("pillPadDp", 12f),
            incomingWidthDp = p.getFloat("incomingWidthDp", -1f),
            bubbleSizeDp = p.getFloat("bubbleSizeDp", -1f),
            bubbleGapDp = p.getFloat("bubbleGapDp", 6f),
            groupOffsetXPx = p.getFloat("groupOffsetXPx", 0f),
            counterShiftPx = p.getFloat("counterShiftPx", 0f),
            counterRight = p.getBoolean("counterRight", true),
            bubbleRight = p.getBoolean("bubbleRight", false),
            pills = PillType.entries.mapNotNull { t ->
                val k = "pill.${t.name}."
                if (!p.getBoolean(k + "set", false)) return@mapNotNull null
                t to PillDims(
                    widthDp = p.getFloat(k + "w", -1f),
                    heightDp = p.getFloat(k + "h", -1f),
                    radiusDp = p.getFloat(k + "r", -2f),
                    padDp = p.getFloat(k + "pad", -1f),
                    iconDp = p.getFloat(k + "icon", -1f),
                    textSp = p.getFloat(k + "text", -1f),
                    offsetXPx = p.getFloat(k + "x", 0f)
                )
            }.toMap(),
            cards = CardType.entries.mapNotNull { t ->
                val k = "card.${t.name}."
                if (!p.getBoolean(k + "set", false)) return@mapNotNull null
                t to CardDims(
                    widthDp = p.getFloat(k + "w", -1f),
                    heightDp = p.getFloat(k + "h", -1f),
                    radiusDp = p.getFloat(k + "r", -1f)
                )
            }.toMap()
        )
    }

    fun save(context: Context, c: IslandCalibration) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("offsetXPx", c.offsetXPx)
            .putFloat("offsetYPx", c.offsetYPx)
            .putFloat("pillHeightDp", c.pillHeightDp)
            .putFloat("compactWidthDp", c.compactWidthDp)
            .putFloat("wideWidthDp", c.wideWidthDp)
            .putFloat("radiusDp", c.radiusDp)
            .putFloat("pillPadDp", c.pillPadDp)
            .putFloat("incomingWidthDp", c.incomingWidthDp)
            .putFloat("bubbleSizeDp", c.bubbleSizeDp)
            .putFloat("bubbleGapDp", c.bubbleGapDp)
            .putFloat("groupOffsetXPx", c.groupOffsetXPx)
            .putFloat("counterShiftPx", c.counterShiftPx)
            .putBoolean("counterRight", c.counterRight)
            .putBoolean("bubbleRight", c.bubbleRight)
        for (t in PillType.entries) {
            val k = "pill.${t.name}."
            val d = c.pills[t]
            e.putBoolean(k + "set", d != null)
            if (d != null) {
                e.putFloat(k + "w", d.widthDp).putFloat(k + "h", d.heightDp).putFloat(k + "r", d.radiusDp)
                    .putFloat(k + "pad", d.padDp).putFloat(k + "icon", d.iconDp).putFloat(k + "text", d.textSp)
                    .putFloat(k + "x", d.offsetXPx)
            }
        }
        for (t in CardType.entries) {
            val k = "card.${t.name}."
            val d = c.cards[t]
            e.putBoolean(k + "set", d != null)
            if (d != null) e.putFloat(k + "w", d.widthDp).putFloat(k + "h", d.heightDp).putFloat(k + "r", d.radiusDp)
        }
        e.apply()
    }

    fun loadLook(context: Context): IslandLook {
        val p = context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE)
        val d = IslandLook()
        return IslandLook(
            blurDp = p.getFloat("look.blurDp", d.blurDp),
            tint = p.getFloat("look.tint", d.tint),
            solid = p.getBoolean("look.solid", d.solid),
            bubbleGlow = p.getBoolean("look.bubbleGlow", d.bubbleGlow),
            blurFollowView = p.getBoolean("look.blurFollowView", d.blurFollowView),
            outgoingGlow = p.getBoolean("look.outgoingGlow", d.outgoingGlow),
            outgoingGlowAmount = p.getFloat("look.outgoingGlowAmount", d.outgoingGlowAmount)
        )
    }

    fun saveLook(context: Context, l: IslandLook) {
        context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("look.blurDp", l.blurDp)
            .putFloat("look.tint", l.tint)
            .putBoolean("look.solid", l.solid)
            .putBoolean("look.bubbleGlow", l.bubbleGlow)
            .putBoolean("look.blurFollowView", l.blurFollowView)
            .putBoolean("look.outgoingGlow", l.outgoingGlow)
            .putFloat("look.outgoingGlowAmount", l.outgoingGlowAmount)
            .apply()
    }

    // ---- Export / import (JSON) ----------------------------------------------------------------

    fun exportJson(c: IslandCalibration, m: IslandMotion, l: IslandLook): String {
        val o = org.json.JSONObject()
        o.put("format", "hyperbridge-island-layout")
        o.put("version", 1)
        val cal = org.json.JSONObject()
        cal.put("offsetXPx", c.offsetXPx.toDouble()); cal.put("offsetYPx", c.offsetYPx.toDouble())
        cal.put("pillHeightDp", c.pillHeightDp.toDouble()); cal.put("compactWidthDp", c.compactWidthDp.toDouble())
        cal.put("wideWidthDp", c.wideWidthDp.toDouble()); cal.put("radiusDp", c.radiusDp.toDouble())
        cal.put("pillPadDp", c.pillPadDp.toDouble()); cal.put("incomingWidthDp", c.incomingWidthDp.toDouble())
        cal.put("bubbleSizeDp", c.bubbleSizeDp.toDouble()); cal.put("bubbleGapDp", c.bubbleGapDp.toDouble())
        cal.put("groupOffsetXPx", c.groupOffsetXPx.toDouble()); cal.put("counterShiftPx", c.counterShiftPx.toDouble())
        cal.put("counterRight", c.counterRight); cal.put("bubbleRight", c.bubbleRight)
        val pills = org.json.JSONObject()
        for ((t, d) in c.pills) {
            pills.put(t.name, org.json.JSONObject().apply {
                put("w", d.widthDp.toDouble()); put("h", d.heightDp.toDouble()); put("r", d.radiusDp.toDouble())
                put("pad", d.padDp.toDouble()); put("icon", d.iconDp.toDouble()); put("text", d.textSp.toDouble())
                put("x", d.offsetXPx.toDouble())
            })
        }
        cal.put("pills", pills)
        val cards = org.json.JSONObject()
        for ((t, d) in c.cards) {
            cards.put(t.name, org.json.JSONObject().apply {
                put("w", d.widthDp.toDouble()); put("h", d.heightDp.toDouble()); put("r", d.radiusDp.toDouble())
            })
        }
        cal.put("cards", cards)
        o.put("calibration", cal)
        o.put("motion", org.json.JSONObject().apply {
            put("bounce", m.bounce.toDouble()); put("speed", m.speed.toDouble()); put("jelly", m.jelly)
            put("jellyAmount", m.jellyAmount.toDouble()); put("blurDp", m.blurDp.toDouble())
        })
        o.put("look", org.json.JSONObject().apply {
            put("blurDp", l.blurDp.toDouble()); put("tint", l.tint.toDouble()); put("solid", l.solid)
            put("bubbleGlow", l.bubbleGlow); put("blurFollowView", l.blurFollowView)
            put("outgoingGlow", l.outgoingGlow); put("outgoingGlowAmount", l.outgoingGlowAmount.toDouble())
        })
        return o.toString(2)
    }

    /** Parses an exported JSON; throws with a readable message when it isn't one. */
    fun importJson(text: String, cur: Triple<IslandCalibration, IslandMotion, IslandLook>): Triple<IslandCalibration, IslandMotion, IslandLook> {
        val o = org.json.JSONObject(text.trim())
        require(o.optString("format") == "hyperbridge-island-layout") { "not an island layout export" }
        fun org.json.JSONObject.f(k: String, d: Float) = if (has(k)) optDouble(k, d.toDouble()).toFloat() else d
        val (c0, m0, l0) = cur
        val cal = o.optJSONObject("calibration")
        val c = if (cal == null) c0 else {
            val d = IslandCalibration()
            val pj = cal.optJSONObject("pills")
            val cj = cal.optJSONObject("cards")
            IslandCalibration(
                offsetXPx = cal.f("offsetXPx", d.offsetXPx), offsetYPx = cal.f("offsetYPx", d.offsetYPx),
                pillHeightDp = cal.f("pillHeightDp", d.pillHeightDp), compactWidthDp = cal.f("compactWidthDp", d.compactWidthDp),
                wideWidthDp = cal.f("wideWidthDp", d.wideWidthDp), radiusDp = cal.f("radiusDp", d.radiusDp),
                pillPadDp = cal.f("pillPadDp", d.pillPadDp), incomingWidthDp = cal.f("incomingWidthDp", d.incomingWidthDp),
                bubbleSizeDp = cal.f("bubbleSizeDp", d.bubbleSizeDp), bubbleGapDp = cal.f("bubbleGapDp", d.bubbleGapDp),
                groupOffsetXPx = cal.f("groupOffsetXPx", d.groupOffsetXPx), counterShiftPx = cal.f("counterShiftPx", d.counterShiftPx),
                counterRight = cal.optBoolean("counterRight", d.counterRight),
                bubbleRight = cal.optBoolean("bubbleRight", d.bubbleRight),
                pills = PillType.entries.mapNotNull { t ->
                    val j = pj?.optJSONObject(t.name) ?: return@mapNotNull null
                    t to PillDims(j.f("w", -1f), j.f("h", -1f), j.f("r", -2f), j.f("pad", -1f), j.f("icon", -1f), j.f("text", -1f), j.f("x", 0f))
                }.toMap(),
                cards = CardType.entries.mapNotNull { t ->
                    val j = cj?.optJSONObject(t.name) ?: return@mapNotNull null
                    t to CardDims(j.f("w", -1f), j.f("h", -1f), j.f("r", -1f))
                }.toMap()
            )
        }
        val mj = o.optJSONObject("motion")
        val m = if (mj == null) m0 else IslandMotion(
            mj.f("bounce", m0.bounce), mj.f("speed", m0.speed), mj.optBoolean("jelly", m0.jelly),
            mj.f("jellyAmount", m0.jellyAmount), mj.f("blurDp", m0.blurDp)
        )
        val lj = o.optJSONObject("look")
        val l = if (lj == null) l0 else IslandLook(
            lj.f("blurDp", l0.blurDp), lj.f("tint", l0.tint), lj.optBoolean("solid", l0.solid),
            lj.optBoolean("bubbleGlow", l0.bubbleGlow), lj.optBoolean("blurFollowView", l0.blurFollowView),
            lj.optBoolean("outgoingGlow", l0.outgoingGlow), lj.f("outgoingGlowAmount", l0.outgoingGlowAmount)
        )
        return Triple(c, m, l)
    }

    // v2: one-spring liquid morph; the old Phase 3 keys (width lead, squeeze) are ignored.
    private const val MOTION_PREFS = "island_motion_v2"

    fun loadMotion(context: Context): IslandMotion {
        val p = context.getSharedPreferences(MOTION_PREFS, Context.MODE_PRIVATE)
        val d = IslandMotion()
        return IslandMotion(
            bounce = p.getFloat("bounce", d.bounce),
            speed = p.getFloat("speed", d.speed),
            jelly = p.getBoolean("jelly", d.jelly),
            jellyAmount = p.getFloat("jellyAmount", d.jellyAmount),
            blurDp = p.getFloat("blurDp", d.blurDp)
        )
    }

    fun saveMotion(context: Context, m: IslandMotion) {
        context.getSharedPreferences(MOTION_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("bounce", m.bounce)
            .putFloat("speed", m.speed)
            .putBoolean("jelly", m.jelly)
            .putFloat("jellyAmount", m.jellyAmount)
            .putFloat("blurDp", m.blurDp)
            .apply()
    }

    private const val OPTIONS_PREFS = "island_options"

    fun loadShowOnLock(context: Context): Boolean =
        context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).getBoolean("showOnLock", false)

    fun saveShowOnLock(context: Context, value: Boolean) {
        context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("showOnLock", value).apply()
    }

    /** Opt-in (default OFF): hide status-bar notification icons via Shizuku during these contents. */
    fun loadIconHide(context: Context): IconHidePrefs {
        val p = context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE)
        return IconHidePrefs(
            call = p.getBoolean("hideIconsCall", false),
            timer = p.getBoolean("hideIconsTimer", false),
            recorder = p.getBoolean("hideIconsRecorder", false),
            multi = p.getBoolean("hideIconsMulti", false),
            clockCalls = p.getBoolean("hideClockCalls", false),
            allCalls = p.getBoolean("hideAllCalls", false),
            chipCalls = p.getBoolean("hideChipCalls", false),
            chipFlag = p.getString("chipFlag", "") ?: ""
        )
    }

    fun saveIconHide(context: Context, v: IconHidePrefs) {
        context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("hideIconsCall", v.call)
            .putBoolean("hideIconsTimer", v.timer)
            .putBoolean("hideIconsRecorder", v.recorder)
            .putBoolean("hideIconsMulti", v.multi)
            .putBoolean("hideClockCalls", v.clockCalls)
            .putBoolean("hideAllCalls", v.allCalls)
            .putBoolean("hideChipCalls", v.chipCalls)
            .putString("chipFlag", v.chipFlag)
            .apply()
    }
}

/**
 * In-process bridge between the test screen, the media watcher, the notification reader,
 * the touch window and the overlay.
 *
 * Real content = "activities" (music session + live notifications), shown by priority:
 * incoming call > ongoing call > recorder > timer/stopwatch > music.
 * All methods run on the main thread.
 */
object IslandController {
    private const val MEDIA_GONE_HIDE_DELAY_MS = 1500L
    private const val MUSIC_ID_PREFIX = "music:"

    private val main = Handler(Looper.getMainLooper())

    private val _mode = MutableStateFlow(IslandMode.HIDDEN)
    val mode: StateFlow<IslandMode> = _mode.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _geometry = MutableStateFlow<IslandGeometry?>(null)
    val geometry: StateFlow<IslandGeometry?> = _geometry.asStateFlow()

    private val _calibration = MutableStateFlow(IslandCalibration())
    val calibration: StateFlow<IslandCalibration> = _calibration.asStateFlow()

    private val _motion = MutableStateFlow(IslandMotion())
    val motion: StateFlow<IslandMotion> = _motion.asStateFlow()

    /** Finger is down on the island (press squeeze). */
    private val _pressed = MutableStateFlow(false)
    val pressed: StateFlow<Boolean> = _pressed.asStateFlow()

    /** Compact state a swiped-away island can be brought back to (null = nothing to restore). */
    private val _restorable = MutableStateFlow<IslandMode?>(null)
    val restorable: StateFlow<IslandMode?> = _restorable.asStateFlow()

    /** Real media from any app (null = nothing playing / paused). */
    private val _media = MutableStateFlow<IslandMediaInfo?>(null)
    val media: StateFlow<IslandMediaInfo?> = _media.asStateFlow()

    /** Whether the media watcher could read sessions (notification access granted). */
    private val _mediaAccess = MutableStateFlow(false)
    val mediaAccess: StateFlow<Boolean> = _mediaAccess.asStateFlow()

    /** Calls / timers / stopwatches / recorders from notifications. */
    private val _live = MutableStateFlow<List<IslandLive>>(emptyList())
    val live: StateFlow<List<IslandLive>> = _live.asStateFlow()

    /** The live activity being shown (null for music or mock states). */
    private val _shownLive = MutableStateFlow<IslandLive?>(null)
    val shownLive: StateFlow<IslandLive?> = _shownLive.asStateFlow()

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _showOnLock = MutableStateFlow(false)
    val showOnLock: StateFlow<Boolean> = _showOnLock.asStateFlow()

    /** Per-content opt-in switches: hide status-bar notification icons via Shizuku. */
    private val _iconHide = MutableStateFlow(IconHidePrefs())
    val iconHide: StateFlow<IconHidePrefs> = _iconHide.asStateFlow()

    /** Target shapes on screen (published by the overlay): main pill / card and the bubble. */
    private val _layoutMain = MutableStateFlow<IslandRectPx?>(null)
    val layoutMain: StateFlow<IslandRectPx?> = _layoutMain.asStateFlow()
    private val _layoutBubble = MutableStateFlow<IslandRectPx?>(null)
    val layoutBubble: StateFlow<IslandRectPx?> = _layoutBubble.asStateFlow()

    internal fun setLayout(main: IslandRectPx?, bubble: IslandRectPx?) {
        if (_layoutMain.value != main) _layoutMain.value = main
        if (_layoutBubble.value != bubble) _layoutBubble.value = bubble
    }

    /** Second running activity shown as a circle beside the pill (null = none). */
    private val _bubble = MutableStateFlow<IslandBubble?>(null)
    val bubble: StateFlow<IslandBubble?> = _bubble.asStateFlow()

    /** Real blur behind expanded cards: availability + why (set by the service). */
    private val _blurState = MutableStateFlow(BlurState(false, "not checked yet"))
    val blurState: StateFlow<BlurState> = _blurState.asStateFlow()

    internal fun setBlurState(s: BlurState) {
        _blurState.value = s
    }

    /**
     * Where the blur behind the card is right now (null = no blur): published EVERY animation frame by
     * the overlay while a card is growing / open / shrinking, so the blur follows the shape from frame 1.
     */
    private val _blurFrame = MutableStateFlow<IslandRectPx?>(null)
    val blurFrame: StateFlow<IslandRectPx?> = _blurFrame.asStateFlow()

    internal fun setBlurFrame(r: IslandRectPx?) {
        if (_blurFrame.value != r) _blurFrame.value = r
    }

    /** Card background look (blur strength, tint, solid black, bubble glow). */
    private val _look = MutableStateFlow(IslandLook())
    val look: StateFlow<IslandLook> = _look.asStateFlow()

    fun setLook(l: IslandLook) {
        _look.value = l
    }

    /** Island Test / Layout screen: the mock timer pill / card is a stopwatch. */
    private val _mockStopwatch = MutableStateFlow(false)

    /** Island Test: the mock call pill is an outgoing call (name + glow instead of the timer). */
    private val _mockOutgoing = MutableStateFlow(false)
    val mockOutgoing: StateFlow<Boolean> = _mockOutgoing.asStateFlow()

    /** Bumped when Island Test ran a status-bar command by hand, so the service re-sends its flags. */
    private val _iconRecheck = MutableStateFlow(0)
    val iconRecheck: StateFlow<Int> = _iconRecheck.asStateFlow()

    fun recheckIcons() {
        _iconRecheck.value = _iconRecheck.value + 1
    }

    /** The Layout screen is previewing: small pill -> small pill switches resize directly on the island spring. */
    private val _layoutPreview = MutableStateFlow(false)
    val layoutPreview: StateFlow<Boolean> = _layoutPreview.asStateFlow()

    fun setLayoutPreview(on: Boolean) {
        _layoutPreview.value = on
    }
    val mockStopwatch: StateFlow<Boolean> = _mockStopwatch.asStateFlow()

    /**
     * Bumped when the main content and the bubble trade places (tap on the bubble). The overlay then
     * runs the liquid swap: the bubble grows into the main shape, the main shrinks into the bubble slot.
     */
    data class Swap(
        val seq: Int,
        val oldMain: IslandMode,
        val oldMainStopwatch: Boolean,
        val oldBubble: IslandBubble,
        /** The mode the island switches to with this swap (the overlay waits for it before animating). */
        val newMain: IslandMode
    )

    private val _swap = MutableStateFlow<Swap?>(null)
    val swap: StateFlow<Swap?> = _swap.asStateFlow()
    private var swapSeq = 0

    /** Result of the last automatic status-bar command (for Island Test). */
    private val _iconHideStatus = MutableStateFlow("idle")
    val iconHideStatus: StateFlow<String> = _iconHideStatus.asStateFlow()

    fun setIconHide(value: IconHidePrefs) {
        _iconHide.value = value
    }

    internal fun setIconHideStatus(text: String) {
        _iconHideStatus.value = text
    }

    /** Activity id currently on screen (null = hidden or a mock test state). */
    private var shownId: String? = null

    /** Ids the user swiped / hid away; they don't pop up again until they end (or are swiped back). */
    private val dismissed = HashSet<String>()

    /** Ids that existed when the user swiped something away: they stay quiet until something changes. */
    private val quiet = HashSet<String>()

    /** Ids hidden because their own app is in front (or the phone is locked); they come back after. */
    private val suppressed = HashSet<String>()

    /** Swiped-away id that the camera restore zone brings back. */
    private var restorableId: String? = null

    /** Package currently in the foreground (from accessibility window events). */
    private var foregroundPackage: String? = null

    /** After a long-press open, treat the owner app as foreground briefly until it shows up. */
    private var openingOwner: String? = null
    private var openingOwnerUntil = 0L

    /** Last reported on-screen bounds of card controls (window == screen coords). */
    private val buttonRects = HashMap<IslandButton, Rect>()

    private val hideWhenMediaGone = Runnable { evaluate() }
    private val reevaluateRunnable = Runnable { evaluate() }

    // ---- Activities ------------------------------------------------------------------------

    private data class Act(val id: String, val kind: LiveKind?, val owner: String, val canPopUp: Boolean, val live: IslandLive?) {
        val priority: Int get() = kind?.priority ?: 10
    }

    private var mediaGoneAt = 0L
    private var lastMedia: IslandMediaInfo? = null

    private fun activities(): List<Act> {
        val list = ArrayList<Act>()
        for (l in _live.value) list += Act(l.id, l.kind, l.packageName, true, l)
        val m = _media.value
        if (m != null) {
            list += Act(MUSIC_ID_PREFIX + m.sessionKey, null, m.packageName, m.playing, null)
        } else {
            // Keep the music that just stopped alive for a short grace period.
            val last = lastMedia
            if (last != null && SystemClock.uptimeMillis() - mediaGoneAt < MEDIA_GONE_HIDE_DELAY_MS) {
                list += Act(MUSIC_ID_PREFIX + last.sessionKey, null, last.packageName, false, null)
            }
        }
        // Highest priority first; newest first within a kind.
        return list.sortedWith(compareByDescending<Act> { it.priority }.thenByDescending { it.live?.postTime ?: 0L })
    }

    private fun compactModeOf(a: Act): IslandMode = when (a.kind) {
        null -> IslandMode.COMPACT_MUSIC
        LiveKind.CALL -> IslandMode.COMPACT
        LiveKind.INCOMING_CALL -> IslandMode.INCOMING_PILL
        LiveKind.RECORDER -> IslandMode.REC_PILL
        LiveKind.TIMER, LiveKind.STOPWATCH -> IslandMode.TIMER_PILL
    }

    private fun modeBelongsTo(m: IslandMode, a: Act): Boolean = when (a.kind) {
        null -> m.isMusic()
        LiveKind.CALL -> m == IslandMode.COMPACT || m == IslandMode.EXPANDED
        LiveKind.INCOMING_CALL -> m == IslandMode.INCOMING_PILL
        LiveKind.RECORDER -> m == IslandMode.REC_PILL || m == IslandMode.REC_CARD
        LiveKind.TIMER, LiveKind.STOPWATCH -> m == IslandMode.TIMER_PILL || m == IslandMode.TIMER_CARD
    }

    private fun effectiveForeground(): String? {
        val now = SystemClock.uptimeMillis()
        return if (openingOwner != null && now < openingOwnerUntil) openingOwner else foregroundPackage
    }

    private fun blockedByLock(): Boolean = _locked.value && !_showOnLock.value

    /** Changes the mode of the shown content (pill <-> card), keeping what is shown. */
    private fun go(m: IslandMode) {
        if (_mode.value != m) buttonRects.clear()
        _mode.value = m
        updateBubble()
    }

    private fun setMode(m: IslandMode, id: String?, acts: List<Act>) {
        if (_mode.value != m) buttonRects.clear()
        shownId = id
        _shownLive.value = id?.let { i -> acts.firstOrNull { it.id == i }?.live }
        _mode.value = m
    }

    /** Re-decides what the island shows (main + bubble) after any change. */
    private fun evaluate() {
        evaluateMain()
        updateBubble()
    }

    /** Ids the user swiped away from the bubble: not shown as a bubble again until they end. */
    private val bubbleDismissed = HashSet<String>()

    /** Activity the user chose as main by tapping the bubble: priority won't take it away (except a ringing call). */
    private var swapHold: String? = null

    /** Island Test: mock second activity. */
    private var mockBubble: BubbleKind? = null

    private fun updateBubble() {
        val m = _mode.value
        // The bubble stays while its partner is expanded into a card.
        if (m == IslandMode.HIDDEN || m == IslandMode.WIDE) {
            _bubble.value = null
            return
        }
        if (shownId == null) {
            _bubble.value = mockBubble?.let { IslandBubble(null, it, null, null) }
            return
        }
        val acts = activities()
        val fg = effectiveForeground()
        val lockBlock = blockedByLock()
        val pick = acts.firstOrNull {
            it.id != shownId && it.id !in dismissed && it.id !in bubbleDismissed && it.owner != fg && !lockBlock &&
                    (it.kind != null || it.canPopUp)
        }
        _bubble.value = pick?.let { IslandBubble(it.id, it.kind.bubbleKind(), it.live, it.owner) }
    }

    private fun evaluateMain() {
        val acts = activities()
        val ids = acts.map { it.id }.toSet()
        dismissed.retainAll(ids)
        bubbleDismissed.retainAll(ids)
        if (swapHold != null && swapHold !in ids) swapHold = null
        quiet.retainAll(ids)
        suppressed.retainAll(ids)
        if (restorableId != null && restorableId !in ids) {
            restorableId = null
            _restorable.value = null
            quiet.clear()
        }
        val fg = effectiveForeground()
        val lockBlock = blockedByLock()

        fun visible(a: Act) = a.id !in dismissed && a.owner != fg && !lockBlock
        val current = shownId?.let { id -> acts.firstOrNull { it.id == id } }

        if (_mode.value != IslandMode.HIDDEN && shownId == null) {
            // Mock test state: only a NEW real activity takes over.
            val fresh = acts.firstOrNull { visible(it) && it.canPopUp && it.id !in quiet && it.id !in seen }
            seen = ids
            if (fresh != null) show(fresh, acts)
            return
        }

        if (current != null && _mode.value != IslandMode.HIDDEN) {
            if (!visible(current)) {
                // Its own app came to the front (or the phone locked): shrink into the camera.
                if (current.id !in dismissed) suppressed += current.id
                setMode(IslandMode.HIDDEN, null, acts)
                seen = ids
                evaluateMain()
                return
            }
            val top = acts.firstOrNull { visible(it) && it.id != current.id }
            val held = swapHold == current.id && top?.kind != LiveKind.INCOMING_CALL
            if (top != null && top.priority > current.priority && top.id !in quiet && !held) {
                show(top, acts)
            } else {
                // Same activity: keep pill / card, refresh data. Incoming -> answered switches to the call pill.
                if (!modeBelongsTo(_mode.value, current)) setMode(compactModeOf(current), current.id, acts)
                else _shownLive.value = current.live
            }
            seen = ids
            return
        }

        // Hidden (or the shown activity ended).
        val pick = acts.firstOrNull {
            visible(it) && (it.id in suppressed || (it.canPopUp && it.id !in quiet))
        }
        seen = ids
        if (pick != null) show(pick, acts)
        else if (_mode.value != IslandMode.HIDDEN) setMode(IslandMode.HIDDEN, null, acts)
        else shownId = null
    }

    private var seen: Set<String> = emptySet()

    private fun show(a: Act, acts: List<Act>) {
        suppressed -= a.id
        if (restorableId == a.id) {
            restorableId = null
            _restorable.value = null
        }
        setMode(compactModeOf(a), a.id, acts)
    }

    // ---- Test buttons ----------------------------------------------------------------------

    /** Test buttons: show a mock state ([stopwatch] = the mock timer is a stopwatch). */
    fun show(mode: IslandMode, stopwatch: Boolean = false, outgoing: Boolean = false) {
        if (mode == IslandMode.HIDDEN) {
            hide()
            return
        }
        restorableId = null
        _restorable.value = null
        mockBubble = null
        _mockStopwatch.value = stopwatch
        _mockOutgoing.value = outgoing
        seen = activities().map { it.id }.toSet()
        setMode(mode, null, emptyList())
        updateBubble()
    }

    /** Island Test: two activities at once (main pill + bubble). */
    fun showTwo(main: IslandMode, second: BubbleKind, stopwatch: Boolean = false) {
        show(main, stopwatch)
        mockBubble = second
        updateBubble()
    }

    /** Hide button: shrink into the camera, nothing left to bring back. */
    fun hide() {
        shownId?.let { dismissed += it }
        restorableId = null
        _restorable.value = null
        mockBubble = null
        setMode(IslandMode.HIDDEN, null, emptyList())
        updateBubble()
    }

    /** Animation tuning "Replay": pill <-> its card of whatever is up (mock call by default). */
    fun replayMorph() {
        val m = _mode.value
        when {
            m.isCard() -> go(m.compact())
            m.card() != null -> go(m.card()!!)
            else -> show(IslandMode.COMPACT)
        }
    }

    fun setCalibration(calibration: IslandCalibration) {
        _calibration.value = calibration
    }

    fun setMotion(motion: IslandMotion) {
        _motion.value = motion
    }

    fun setShowOnLock(value: Boolean) {
        _showOnLock.value = value
        evaluate()
    }

    internal fun setLocked(locked: Boolean) {
        if (_locked.value == locked) return
        _locked.value = locked
        if (locked && !_showOnLock.value && _mode.value != IslandMode.HIDDEN && shownId == null) {
            // Mock state while locking with "show on lock screen" off: just hide it.
            setMode(IslandMode.HIDDEN, null, emptyList())
        }
        evaluate()
    }

    // ---- Gestures (from the small touch window) ---------------------------------------------

    fun onPress(down: Boolean) {
        _pressed.value = down
    }

    /** True when screen point (x, y) is on the bubble (plus a little touch slack). */
    private fun onBubble(x: Float, y: Float): Boolean {
        if (x.isNaN() || _bubble.value == null) return false
        val b = _layoutBubble.value ?: return false
        val inBubble = x >= b.left && x <= b.left + b.w && y >= b.top && y <= b.top + b.h
        if (inBubble) return true
        // Touch slack around the bubble, but the pill / card wins where the two overlap.
        val m = _layoutMain.value
        if (m != null && x >= m.left && x <= m.left + m.w && y >= m.top && y <= m.top + m.h) return false
        val slack = b.w * 0.15f
        return x >= b.left - slack && x <= b.left + b.w + slack && y <= b.top + b.h + slack
    }

    /**
     * Tap on the bubble: it becomes the main content and the main becomes the bubble. With a card open,
     * the bubble's content opens straight into ITS card (and the old card shrinks into the bubble).
     */
    private fun swapWithBubble() {
        val b = _bubble.value ?: return
        val m = _mode.value
        val wasCard = m.isCard()
        fun target(pill: IslandMode): IslandMode = if (wasCard) pill.card() ?: pill else pill
        val oldSw = _mockStopwatch.value || _shownLive.value?.kind == LiveKind.STOPWATCH
        if (b.id == null) {
            // Mock: swap the two mock contents.
            val newMain = target(pillOf(b.kind))
            // Published first, then the mode: the overlay waits until the mode matches newMain.
            _swap.value = Swap(++swapSeq, m, oldSw, b, newMain)
            val newBubble = bubbleOfMock(m)
            val sw = b.kind == BubbleKind.STOPWATCH
            setMode(newMain, null, emptyList())
            mockBubble = newBubble
            _mockStopwatch.value = sw
            updateBubble()
            return
        }
        val acts = activities()
        val a = acts.firstOrNull { it.id == b.id } ?: return
        val newMain = target(compactModeOf(a))
        _swap.value = Swap(++swapSeq, m, oldSw, b, newMain)
        swapHold = a.id
        suppressed -= a.id
        if (restorableId == a.id) {
            restorableId = null
            _restorable.value = null
        }
        setMode(newMain, a.id, acts)
        updateBubble()
    }

    private fun pillOf(k: BubbleKind): IslandMode = when (k) {
        BubbleKind.MUSIC -> IslandMode.COMPACT_MUSIC
        BubbleKind.CALL -> IslandMode.COMPACT
        BubbleKind.INCOMING -> IslandMode.INCOMING_PILL
        BubbleKind.TIMER, BubbleKind.STOPWATCH -> IslandMode.TIMER_PILL
        BubbleKind.RECORDER -> IslandMode.REC_PILL
    }

    private fun bubbleOfMock(m: IslandMode): BubbleKind? = when (m.compact()) {
        IslandMode.COMPACT -> BubbleKind.CALL
        IslandMode.INCOMING_PILL -> BubbleKind.INCOMING
        IslandMode.TIMER_PILL -> if (_mockStopwatch.value) BubbleKind.STOPWATCH else BubbleKind.TIMER
        IslandMode.REC_PILL -> BubbleKind.RECORDER
        IslandMode.COMPACT_MUSIC -> BubbleKind.MUSIC
        else -> null
    }

    /** Horizontal swipe starting at (x, y): on the bubble hides only the bubble; else the usual rules. */
    fun onSwipe(x: Float = Float.NaN, y: Float = Float.NaN) {
        _pressed.value = false
        if (onBubble(x, y)) {
            val b = _bubble.value ?: return
            if (b.id == null) mockBubble = null else bubbleDismissed += b.id
            updateBubble()
            return
        }
        val m = _mode.value
        when {
            m.isCard() -> go(m.compact())
            m != IslandMode.HIDDEN -> {
                val id = shownId
                val acts = activities()
                if (id != null) {
                    dismissed += id
                    restorableId = id
                    quiet.clear()
                    quiet += acts.map { it.id }.filter { it != id }
                }
                _restorable.value = m
                setMode(IslandMode.HIDDEN, null, acts)
            }
            else -> {
                val back = _restorable.value ?: return
                val id = restorableId
                _restorable.value = null
                restorableId = null
                quiet.clear()
                val acts = activities()
                if (id != null) {
                    dismissed -= id
                    val a = acts.firstOrNull { it.id == id }
                    if (a != null) setMode(compactModeOf(a), id, acts)
                } else {
                    setMode(back, null, acts)
                }
            }
        }
    }

    /** Tap on the island at screen position (x, y). */
    fun onTap(x: Float, y: Float) {
        _pressed.value = false
        if (onBubble(x, y)) {
            swapWithBubble()
            return
        }
        val m = _mode.value
        if (m.isCard() && !x.isNaN()) {
            // With a bubble the touch area also spans the row beside it: a tap there is "outside" the card.
            val r = _layoutMain.value
            if (r != null && (x < r.left || x > r.left + r.w || y < r.top - 4f || y > r.top + r.h)) {
                onOutsideTouch()
                return
            }
        }
        if (m == IslandMode.INCOMING_PILL) {
            // The phone's own incoming-call screen is the "expanded" view.
            val live = _shownLive.value
            if (live != null && !_locked.value) openCallScreen(live)
            return
        }
        if (!m.isCard()) {
            m.card()?.let { go(it) }
            return
        }
        val hit = buttonRects.entries.firstOrNull { (_, r) -> r.contains(Offset(x, y)) }?.key
        val live = _shownLive.value
        if (m == IslandMode.MUSIC) {
            if (hit != null) {
                if (_media.value != null) IslandMedia.press(hit)
            } else if (_media.value != null && !_locked.value) {
                openOwnerApp()
            }
            return
        }
        if (live == null) return // mock card
        if (hit != null) {
            performLiveButton(hit, live)
        } else if (!_locked.value) {
            openOwnerApp()
        }
    }

    /**
     * A card button: fire the notification's own action; for calls fall back to Telecom / Shizuku;
     * open the app only when every route is gone (and say so).
     */
    private fun performLiveButton(hit: IslandButton, live: IslandLive) {
        val ctx = IslandMedia.appContext
        val isCall = live.kind == LiveKind.CALL || live.kind == LiveKind.INCOMING_CALL
        val action = when (hit) {
            IslandButton.ANSWER -> live.action(ActionRole.ANSWER)
            IslandButton.DECLINE -> live.action(ActionRole.DECLINE, ActionRole.HANG_UP)
            IslandButton.HANG_UP -> live.action(ActionRole.HANG_UP, ActionRole.DECLINE)
            IslandButton.SPEAKER -> live.action(ActionRole.SPEAKER)
            IslandButton.PRIMARY ->
                if (live.paused) live.action(ActionRole.RESUME, ActionRole.PAUSE)
                else live.action(ActionRole.PAUSE, ActionRole.RESUME)
            IslandButton.SECONDARY -> when (live.kind) {
                LiveKind.STOPWATCH ->
                    if (!live.paused) live.action(ActionRole.LAP)
                    else live.action(ActionRole.RESET, ActionRole.STOP, ActionRole.CANCEL)
                LiveKind.TIMER -> live.action(ActionRole.CANCEL, ActionRole.STOP, ActionRole.RESET)
                LiveKind.RECORDER -> live.action(ActionRole.STOP)
                else -> live.action(ActionRole.STOP, ActionRole.HANG_UP)
            }
            else -> null
        }
        if (action?.intent != null && sendPendingIntent(action.intent)) {
            IslandCallControl.status("${hit.name}: sent action \"${action.title}\" (${action.role}, by ${action.matchedBy})")
            return
        }
        val why = if (action == null) "no matching action" else "action \"${action.title}\" failed to send"
        if (isCall && ctx != null) {
            when (hit) {
                IslandButton.ANSWER -> { IslandCallControl.status("ANSWER: $why → fallback"); IslandCallControl.answer(ctx, live.sbnKey); return }
                IslandButton.DECLINE, IslandButton.HANG_UP -> {
                    IslandCallControl.status("${hit.name}: $why → fallback"); IslandCallControl.end(ctx, live.sbnKey); return
                }
                IslandButton.SPEAKER -> { IslandCallControl.toggleSpeaker(ctx, live.sbnKey); return }
                else -> {}
            }
        }
        val acts = live.actions.joinToString { "\"" + it.title + "\"=" + it.role }
        IslandCallControl.status("${hit.name} on ${live.kind}: $why → opened app. actions: [$acts]")
        if (!_locked.value) openOwnerApp()
    }

    /** Incoming pill tap: the phone's full-screen call UI, else the notification, else the dialer. */
    private fun openCallScreen(live: IslandLive) {
        if (sendPendingIntent(live.fullScreenIntent)) return
        if (sendPendingIntent(live.contentIntent)) return
        val ctx = IslandMedia.appContext ?: return
        try {
            val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
            @Suppress("MissingPermission")
            tm?.showInCallScreen(false)
            return
        } catch (_: Exception) {
        }
        IslandNotifications.openOwner(ctx, live)
    }

    /** Long-press on a pill or card: open the app that owns the content; hide while it's open. */
    fun onLongPress(x: Float = Float.NaN, y: Float = Float.NaN): Boolean {
        _pressed.value = false
        if (_locked.value) return false // can't open apps over the keyguard; ignore
        if (onBubble(x, y)) return openBubbleApp()
        if (_mode.value == IslandMode.HIDDEN || shownId == null) return false
        return openOwnerApp()
    }

    private fun openOwnerApp(): Boolean {
        val id = shownId ?: return false
        val acts = activities()
        val a = acts.firstOrNull { it.id == id } ?: return false
        val ok = if (a.kind == null) IslandMedia.openApp()
        else a.live?.let { IslandNotifications.openOwner(IslandMedia.appContext, it) } ?: false
        if (!ok) return false
        openingOwner = a.owner
        openingOwnerUntil = SystemClock.uptimeMillis() + 1200L
        main.removeCallbacks(reevaluateRunnable)
        main.postDelayed(reevaluateRunnable, 1250L)
        evaluate() // owner counts as foreground now -> shrinks into the camera
        return true
    }

    private fun openBubbleApp(): Boolean {
        val b = _bubble.value ?: return false
        val id = b.id ?: return false
        val a = activities().firstOrNull { it.id == id } ?: return false
        val ok = if (a.kind == null) IslandMedia.openApp()
        else a.live?.let { IslandNotifications.openOwner(IslandMedia.appContext, it) } ?: false
        if (!ok) return false
        openingOwner = a.owner
        openingOwnerUntil = SystemClock.uptimeMillis() + 1200L
        main.removeCallbacks(reevaluateRunnable)
        main.postDelayed(reevaluateRunnable, 1250L)
        evaluate()
        return true
    }

    /** Foreground app changed (accessibility TYPE_WINDOW_STATE_CHANGED, filtered by the service). */
    fun onForegroundPackage(pkg: String) {
        val now = SystemClock.uptimeMillis()
        val opening = openingOwner
        if (opening != null && now < openingOwnerUntil && pkg != opening) return
        if (pkg == opening) {
            openingOwner = null
            openingOwnerUntil = 0L
        }
        if (foregroundPackage == pkg) return
        foregroundPackage = pkg
        evaluate()
    }

    /** Pull down on a compact pill: expand it (like a tap). */
    fun onPullDown(x: Float = Float.NaN, y: Float = Float.NaN) {
        _pressed.value = false
        if (onBubble(x, y)) return
        val m = _mode.value
        m.card()?.let { go(it) }
    }

    /** A touch anywhere outside the island: an expanded card collapses back to its pill. */
    fun onOutsideTouch() {
        val m = _mode.value
        if (m.isCard()) go(m.compact())
    }

    internal fun setButtonRect(button: IslandButton, rect: Rect, touchPaddingPx: Float) {
        buttonRects[button] = rect.inflate(touchPaddingPx)
    }

    // ---- Sources -----------------------------------------------------------------------------

    internal fun setMediaAccess(granted: Boolean) {
        _mediaAccess.value = granted
    }

    /** Called on the main thread by the media watcher whenever the chosen session changes. */
    internal fun setMedia(info: IslandMediaInfo?) {
        val old = _media.value
        _media.value = info
        if (info != null) {
            main.removeCallbacks(hideWhenMediaGone)
            lastMedia = info
            // A paused session that starts playing again may pop up again (unless swiped away).
            if (old != null && old.sessionKey == info.sessionKey && !old.playing && info.playing) {
                quiet -= MUSIC_ID_PREFIX + info.sessionKey
            }
        } else if (old != null) {
            mediaGoneAt = SystemClock.uptimeMillis()
            main.removeCallbacks(hideWhenMediaGone)
            main.postDelayed(hideWhenMediaGone, MEDIA_GONE_HIDE_DELAY_MS + 20L)
        }
        evaluate()
    }

    /** Called on the main thread by IslandNotifications. */
    internal fun setLive(list: List<IslandLive>) {
        _live.value = list
        evaluate()
    }

    internal fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    internal fun setGeometry(geometry: IslandGeometry?) {
        _geometry.value = geometry
    }
}

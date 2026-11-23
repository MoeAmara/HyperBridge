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
    val counterRight: Boolean = true
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
data class IconHidePrefs(val call: Boolean = false, val timer: Boolean = false, val recorder: Boolean = false) {
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
    val rightOfCamera: Boolean
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

    fun bubbleSizePx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.bubbleSizeDp > 0f) c.bubbleSizeDp * density else pillHeightPx(g, c, density)

    /** Room kept free around the camera hole inside a pill. */
    fun cameraGapPx(g: IslandGeometry, density: Float): Float = g.cameraDiameterPx + 10f * density

    fun isCounter(mode: IslandMode): Boolean =
        mode == IslandMode.COMPACT || mode == IslandMode.TIMER_PILL || mode == IslandMode.REC_PILL

    private fun radius(h: Float, c: IslandCalibration, density: Float): Float =
        if (c.radiusDp >= 0f) min(c.radiusDp * density, h / 2f) else h / 2f

    /** Expanded cards keep their Phase 1 size and vertical position; only the horizontal offset applies. */
    private fun card(g: IslandGeometry, c: IslandCalibration, density: Float, heightRatio: Float): IslandRectPx {
        val screenW = g.screenWidthPx.toFloat()
        val margin = screenW * CARD_MARGIN_RATIO
        val top = max(g.statusBarHeightPx.toFloat(), g.cameraCenterYPx + g.cameraDiameterPx / 2f) + 6f * density
        return IslandRectPx(
            cx = screenW / 2f + c.offsetXPx,
            top = top,
            w = screenW - margin * 2f,
            h = screenW * heightRatio,
            r = screenW * CARD_RADIUS_RATIO
        )
    }

    /**
     * Full plan for [mode]. [slots] = measured content widths (null = use the calibrated widths only),
     * [withBubble] = a second activity is shown as a circle left of the pill.
     */
    fun plan(
        mode: IslandMode,
        g: IslandGeometry,
        c: IslandCalibration,
        density: Float,
        slots: PillSlots?,
        withBubble: Boolean
    ): IslandPlan {
        when (mode) {
            IslandMode.HIDDEN -> return IslandPlan(camera(g, c, density), null, null)
            IslandMode.EXPANDED -> return IslandPlan(card(g, c, density, CALL_HEIGHT_RATIO), null, null)
            IslandMode.TIMER_CARD, IslandMode.REC_CARD ->
                return IslandPlan(card(g, c, density, TIMER_HEIGHT_RATIO), null, null)
            IslandMode.MUSIC -> return IslandPlan(card(g, c, density, MUSIC_HEIGHT_RATIO), null, null)
            else -> {}
        }
        val h = pillHeightPx(g, c, density)
        val r = radius(h, c, density)
        val pad = c.pillPadDp.coerceIn(0f, 40f) * density
        val gap = cameraGapPx(g, density)
        val camX = g.cameraCenterXPx + c.offsetXPx
        val camY = g.cameraCenterYPx + c.offsetYPx
        val top = camY - h / 2f
        val l = slots?.leftPx ?: 0f
        val rt = slots?.rightPx ?: 0f
        val base = when (mode) {
            IslandMode.WIDE -> wideWidthPx(g, c, density)
            IslandMode.INCOMING_PILL -> incomingWidthPx(g, c, density)
            else -> compactWidthPx(g, c, density)
        }
        val maxW = g.screenWidthPx * 0.9f
        // Centred on the camera: left content | camera | right content, `pad` at both ends.
        val centredNeed = 2f * (pad + max(l, rt)) + gap
        val bubble = withBubble && mode != IslandMode.WIDE

        if (!bubble) {
            val w = when {
                mode == IslandMode.WIDE -> base
                // The incoming width is the user's to choose; only the icon must still fit (text ellipsizes).
                mode == IslandMode.INCOMING_PILL && c.incomingWidthDp > 0f -> max(base, 2f * (pad + l) + gap)
                else -> max(base, centredNeed)
            }.coerceAtMost(maxW)
            return IslandPlan(
                IslandRectPx(camX, top, w, h, r),
                PillFrame(w, w / 2f, pad, gap, false),
                null
            )
        }

        val b = bubbleSizePx(g, c, density)
        val bGap = c.bubbleGapDp.coerceIn(0f, 40f) * density
        val bubbleTop = camY - b / 2f
        if (isCounter(mode) && c.counterRight) {
            // Counter next to a bubble: the pill's left end covers the camera, icon + time sit right of it.
            val w = (pad + gap + l + RIGHT_SPACING_DP * density + rt + pad).coerceAtMost(maxW)
            val left = camX - gap / 2f - pad + c.counterShiftPx
            val main = IslandRectPx(left + w / 2f, top, w, h, r)
            val bub = IslandRectPx(left - bGap - b / 2f, bubbleTop, b, b, b / 2f)
            return IslandPlan(main, PillFrame(w, camX - left, pad, gap, true), bub)
        }
        // Bubble + pill centred together as one group; the pill still keeps its content clear of the camera.
        val off = c.groupOffsetXPx
        val w = maxOf(
            base,
            centredNeed,
            2f * (pad + l + gap / 2f + off) + b + bGap,
            2f * (pad + rt + gap / 2f - off) - b - bGap
        ).coerceAtMost(maxW)
        val groupLeft = camX + off - (b + bGap + w) / 2f
        val pillLeft = groupLeft + b + bGap
        val main = IslandRectPx(pillLeft + w / 2f, top, w, h, r)
        val bub = IslandRectPx(groupLeft + b / 2f, bubbleTop, b, b, b / 2f)
        return IslandPlan(main, PillFrame(w, camX - pillLeft, pad, gap, false), bub)
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
            counterRight = p.getBoolean("counterRight", true)
        )
    }

    fun save(context: Context, c: IslandCalibration) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
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
            .apply()
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
            recorder = p.getBoolean("hideIconsRecorder", false)
        )
    }

    fun saveIconHide(context: Context, v: IconHidePrefs) {
        context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("hideIconsCall", v.call)
            .putBoolean("hideIconsTimer", v.timer)
            .putBoolean("hideIconsRecorder", v.recorder)
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

    /** Settled expanded-card bounds the service puts its blur window behind (null = no blur window). */
    private val _blurRect = MutableStateFlow<IslandRectPx?>(null)
    val blurRect: StateFlow<IslandRectPx?> = _blurRect.asStateFlow()

    internal fun setBlurRect(r: IslandRectPx?) {
        if (_blurRect.value != r) _blurRect.value = r
    }

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
        if (m == IslandMode.HIDDEN || m.isCard() || m == IslandMode.WIDE) {
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

    /** Test buttons: show a mock state. */
    fun show(mode: IslandMode) {
        if (mode == IslandMode.HIDDEN) {
            hide()
            return
        }
        restorableId = null
        _restorable.value = null
        mockBubble = null
        seen = activities().map { it.id }.toSet()
        setMode(mode, null, emptyList())
        updateBubble()
    }

    /** Island Test: two activities at once (main pill + bubble). */
    fun showTwo(main: IslandMode, second: BubbleKind) {
        show(main)
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
        val slack = b.w * 0.15f
        return x >= b.left - slack && x <= b.left + b.w + slack && y <= b.top + b.h + slack * 2f
    }

    /** Tap on the bubble: it becomes the main pill and the main becomes the bubble. */
    private fun swapWithBubble() {
        val b = _bubble.value ?: return
        val m = _mode.value
        if (b.id == null) {
            // Mock: swap the two mock contents.
            val newMain = pillOf(b.kind)
            val newBubble = bubbleOfMock(m)
            setMode(newMain, null, emptyList())
            mockBubble = newBubble
            updateBubble()
            return
        }
        val acts = activities()
        val a = acts.firstOrNull { it.id == b.id } ?: return
        swapHold = a.id
        show(a, acts)
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
        IslandMode.TIMER_PILL -> BubbleKind.TIMER
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

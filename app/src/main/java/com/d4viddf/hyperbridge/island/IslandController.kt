package com.d4viddf.hyperbridge.island

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * Test build: the states the self-drawn island can be in.
 * All content is mock content; real data arrives in later phases.
 */
enum class IslandMode {
    HIDDEN,

    /** Compact call pill: green phone icon left of the camera, timer right of it. */
    COMPACT,

    /** Wide charging pill: "Quick charging" left, "69%" + bolt right. */
    WIDE,

    /** Expanded call card below the camera: name, timer, speaker, red hang-up. */
    EXPANDED,

    /** Expanded music card below the camera: art, title, artist, controls, progress. */
    MUSIC
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
    val radiusDp: Float = -1f
)

/** A rounded rect described by its horizontal centre, top edge, size and radius (all px). */
data class IslandRectPx(val cx: Float, val top: Float, val w: Float, val h: Float, val r: Float) {
    val left: Float get() = cx - w / 2f
}

/** Swipe gestures coming from the small touch window, in px. */
sealed interface IslandDrag {
    data class Move(val totalDx: Float) : IslandDrag
    data class End(val totalDx: Float, val velocityX: Float) : IslandDrag
}

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
    const val CALL_HEIGHT_RATIO = 0.22f       // expanded call card height
    const val MUSIC_HEIGHT_RATIO = 0.42f      // expanded music card height

    fun autoPillHeightPx(g: IslandGeometry, density: Float): Float =
        max(g.screenWidthPx * PILL_HEIGHT_RATIO, g.cameraDiameterPx + 8f * density)

    fun pillHeightPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.pillHeightDp > 0f) c.pillHeightDp * density else autoPillHeightPx(g, density)

    fun compactWidthPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.compactWidthDp > 0f) c.compactWidthDp * density else g.screenWidthPx * COMPACT_WIDTH_RATIO

    fun wideWidthPx(g: IslandGeometry, c: IslandCalibration, density: Float): Float =
        if (c.wideWidthDp > 0f) c.wideWidthDp * density else g.screenWidthPx * WIDE_WIDTH_RATIO

    private fun pill(g: IslandGeometry, c: IslandCalibration, density: Float, width: Float): IslandRectPx {
        val h = pillHeightPx(g, c, density)
        val r = if (c.radiusDp >= 0f) min(c.radiusDp * density, h / 2f) else h / 2f
        val camX = g.cameraCenterXPx + c.offsetXPx
        val camY = g.cameraCenterYPx + c.offsetYPx
        return IslandRectPx(camX, camY - h / 2f, width, h, r)
    }

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

    fun target(mode: IslandMode, g: IslandGeometry, c: IslandCalibration, density: Float): IslandRectPx =
        when (mode) {
            IslandMode.HIDDEN -> camera(g, c, density)
            IslandMode.COMPACT -> pill(g, c, density, compactWidthPx(g, c, density))
            IslandMode.WIDE -> pill(g, c, density, wideWidthPx(g, c, density))
            IslandMode.EXPANDED -> card(g, c, density, CALL_HEIGHT_RATIO)
            IslandMode.MUSIC -> card(g, c, density, MUSIC_HEIGHT_RATIO)
        }
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
            radiusDp = p.getFloat("radiusDp", -1f)
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
            .apply()
    }
}

/** In-process bridge between the test screen, the touch window and the overlay. */
object IslandController {
    private val _mode = MutableStateFlow(IslandMode.HIDDEN)
    val mode: StateFlow<IslandMode> = _mode.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _geometry = MutableStateFlow<IslandGeometry?>(null)
    val geometry: StateFlow<IslandGeometry?> = _geometry.asStateFlow()

    private val _calibration = MutableStateFlow(IslandCalibration())
    val calibration: StateFlow<IslandCalibration> = _calibration.asStateFlow()

    private val _drag = MutableSharedFlow<IslandDrag>(extraBufferCapacity = 64)
    val drag: SharedFlow<IslandDrag> = _drag.asSharedFlow()

    fun show(mode: IslandMode) {
        _mode.value = mode
    }

    fun setCalibration(calibration: IslandCalibration) {
        _calibration.value = calibration
    }

    internal fun emitDrag(event: IslandDrag) {
        _drag.tryEmit(event)
    }

    internal fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    internal fun setGeometry(geometry: IslandGeometry?) {
        _geometry.value = geometry
    }
}

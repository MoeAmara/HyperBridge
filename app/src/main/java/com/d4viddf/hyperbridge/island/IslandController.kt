package com.d4viddf.hyperbridge.island

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    MUSIC,

    /** Compact music pill: album art left of the camera, sound-wave bars right of it. */
    COMPACT_MUSIC
}

/** True for the big cards that sit below the camera row. */
fun IslandMode.isCard(): Boolean = this == IslandMode.EXPANDED || this == IslandMode.MUSIC

/** True for the music states (real or mock music). */
fun IslandMode.isMusic(): Boolean = this == IslandMode.MUSIC || this == IslandMode.COMPACT_MUSIC

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

/** Tappable controls on the music card; their on-screen bounds are reported by the overlay. */
enum class IslandButton { PREV, PLAY_PAUSE, NEXT }

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

    /**
     * Invisible touch zone around the camera used to bring a swiped-away island back:
     * about the compact pill's width, and as tall as the status bar (or the pill, if taller).
     */
    fun restoreZone(g: IslandGeometry, c: IslandCalibration, density: Float): IslandRectPx {
        val p = pill(g, c, density, compactWidthPx(g, c, density))
        val bottom = max(g.statusBarHeightPx.toFloat(), p.top + p.h)
        return IslandRectPx(p.cx, 0f, p.w, bottom, 0f)
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
            IslandMode.COMPACT, IslandMode.COMPACT_MUSIC -> pill(g, c, density, compactWidthPx(g, c, density))
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

/** In-process bridge between the test screen, the media watcher, the touch window and the overlay. */
object IslandController {
    private const val MEDIA_GONE_HIDE_DELAY_MS = 1500L

    private val main = Handler(Looper.getMainLooper())

    private val _mode = MutableStateFlow(IslandMode.HIDDEN)
    val mode: StateFlow<IslandMode> = _mode.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _geometry = MutableStateFlow<IslandGeometry?>(null)
    val geometry: StateFlow<IslandGeometry?> = _geometry.asStateFlow()

    private val _calibration = MutableStateFlow(IslandCalibration())
    val calibration: StateFlow<IslandCalibration> = _calibration.asStateFlow()

    /** Compact state a swiped-away island can be brought back to (null = nothing to restore). */
    private val _restorable = MutableStateFlow<IslandMode?>(null)
    val restorable: StateFlow<IslandMode?> = _restorable.asStateFlow()

    /** Real media from any app (null = nothing playing / paused). */
    private val _media = MutableStateFlow<IslandMediaInfo?>(null)
    val media: StateFlow<IslandMediaInfo?> = _media.asStateFlow()

    /** Whether the media watcher could read sessions (notification access granted). */
    private val _mediaAccess = MutableStateFlow(false)
    val mediaAccess: StateFlow<Boolean> = _mediaAccess.asStateFlow()

    /** Session the user swiped / hid away; it does not pop up again until it ends. */
    private var dismissedSessionKey: String? = null

    /** Package currently in the foreground (from accessibility window events). */
    private var foregroundPackage: String? = null

    /**
     * Compact state hidden because the content's own app is in the foreground (HyperOS hides
     * the island for the app you're in). Grows back out of the camera when the user leaves it.
     */
    private var suppressedMode: IslandMode? = null

    /** After a long-press open, ignore other packages briefly until the owner app shows up. */
    private var openingOwnerUntil = 0L

    /** Last reported on-screen bounds of the music card controls (window == screen coords). */
    private val buttonRects = HashMap<IslandButton, Rect>()

    private val hideWhenMediaGone = Runnable {
        if (_media.value == null) {
            if (_mode.value.isMusic()) _mode.value = IslandMode.HIDDEN
            if (_restorable.value == IslandMode.COMPACT_MUSIC) _restorable.value = null
            if (suppressedMode?.isMusic() == true) suppressedMode = null
            dismissedSessionKey = null
        }
    }

    /** Test buttons: show a mock state. */
    fun show(mode: IslandMode) {
        if (mode == IslandMode.HIDDEN) {
            hide()
            return
        }
        _restorable.value = null
        suppressedMode = null
        _mode.value = mode
    }

    /** Hide button: shrink into the camera, nothing left to bring back. */
    fun hide() {
        if (_mode.value.isMusic()) dismissedSessionKey = _media.value?.sessionKey
        _restorable.value = null
        suppressedMode = null
        _mode.value = IslandMode.HIDDEN
    }

    fun setCalibration(calibration: IslandCalibration) {
        _calibration.value = calibration
    }

    // ---- Gestures (from the small touch window) ----

    /** Horizontal swipe: card -> its compact pill; pill -> into the camera; hidden -> bring back. */
    fun onSwipe() {
        when (val m = _mode.value) {
            IslandMode.EXPANDED -> _mode.value = IslandMode.COMPACT
            IslandMode.MUSIC -> _mode.value = IslandMode.COMPACT_MUSIC
            IslandMode.COMPACT, IslandMode.WIDE, IslandMode.COMPACT_MUSIC -> {
                if (m == IslandMode.COMPACT_MUSIC) dismissedSessionKey = _media.value?.sessionKey
                _restorable.value = m
                _mode.value = IslandMode.HIDDEN
            }
            IslandMode.HIDDEN -> {
                val back = _restorable.value ?: return
                if (back == IslandMode.COMPACT_MUSIC) dismissedSessionKey = null
                _restorable.value = null
                _mode.value = back
            }
        }
    }

    /** Tap on the island at screen position (x, y). */
    fun onTap(x: Float, y: Float) {
        when (_mode.value) {
            IslandMode.COMPACT -> _mode.value = IslandMode.EXPANDED
            IslandMode.COMPACT_MUSIC -> _mode.value = IslandMode.MUSIC
            IslandMode.MUSIC -> {
                val hit = buttonRects.entries.firstOrNull { (_, r) -> r.contains(androidx.compose.ui.geometry.Offset(x, y)) }?.key
                if (hit != null) {
                    if (_media.value != null) IslandMedia.press(hit)
                } else if (_media.value != null) {
                    openOwnerApp()
                }
            }
            else -> Unit
        }
    }

    /** Long-press on a pill or card: open the app that owns the content; hide while it's open. */
    fun onLongPress(): Boolean = when (_mode.value) {
        IslandMode.COMPACT_MUSIC, IslandMode.MUSIC -> _media.value != null && openOwnerApp()
        else -> false
    }

    private fun openOwnerApp(): Boolean {
        val owner = _media.value?.packageName ?: return false
        if (!IslandMedia.openApp()) return false
        openingOwnerUntil = android.os.SystemClock.uptimeMillis() + 1200L
        suppressFor(owner)
        return true
    }

    /** Hide (shrink into the camera) because [owner]'s app is in front; remember what to bring back. */
    private fun suppressFor(owner: String) {
        val m = _mode.value
        if (m == IslandMode.HIDDEN) return
        if (ownerOf(m) != owner) return
        suppressedMode = compactOf(m)
        _restorable.value = null
        _mode.value = IslandMode.HIDDEN
    }

    private fun ownerOf(m: IslandMode): String? = if (m.isMusic()) _media.value?.packageName else null

    private fun compactOf(m: IslandMode): IslandMode = when (m) {
        IslandMode.MUSIC -> IslandMode.COMPACT_MUSIC
        IslandMode.EXPANDED -> IslandMode.COMPACT
        else -> m
    }

    /** Foreground app changed (accessibility TYPE_WINDOW_STATE_CHANGED, filtered by the service). */
    fun onForegroundPackage(pkg: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val owner = _media.value?.packageName
        if (now < openingOwnerUntil && pkg != owner) return
        if (pkg == owner) openingOwnerUntil = 0L
        if (foregroundPackage == pkg) return
        foregroundPackage = pkg
        if (owner != null && pkg == owner) {
            suppressFor(owner)
            return
        }
        // Left the owner app: grow back out of the camera if the content is still alive.
        val back = suppressedMode ?: return
        suppressedMode = null
        if (_mode.value != IslandMode.HIDDEN) return
        if (back.isMusic() && _media.value == null) return
        _mode.value = back
    }

    /** Pull down on a compact pill: expand it (like a tap). */
    fun onPullDown() {
        when (_mode.value) {
            IslandMode.COMPACT -> _mode.value = IslandMode.EXPANDED
            IslandMode.COMPACT_MUSIC -> _mode.value = IslandMode.MUSIC
            else -> Unit
        }
    }

    /** A touch anywhere outside the island: an expanded card collapses back to its pill. */
    fun onOutsideTouch() {
        when (_mode.value) {
            IslandMode.EXPANDED -> _mode.value = IslandMode.COMPACT
            IslandMode.MUSIC -> _mode.value = IslandMode.COMPACT_MUSIC
            else -> Unit
        }
    }

    internal fun setButtonRect(button: IslandButton, rect: Rect, touchPaddingPx: Float) {
        buttonRects[button] = rect.inflate(touchPaddingPx)
    }

    // ---- Media ----

    internal fun setMediaAccess(granted: Boolean) {
        _mediaAccess.value = granted
    }

    /** Called on the main thread by the media watcher whenever the chosen session changes. */
    internal fun setMedia(info: IslandMediaInfo?) {
        val old = _media.value
        _media.value = info
        if (info != null) {
            main.removeCallbacks(hideWhenMediaGone)
            if (old != null && old.sessionKey != info.sessionKey && dismissedSessionKey == old.sessionKey) {
                dismissedSessionKey = null
            }
            if (info.playing && _mode.value == IslandMode.HIDDEN && dismissedSessionKey != info.sessionKey) {
                if (foregroundPackage == info.packageName) {
                    // Started from inside its own app: stay hidden until the user leaves it.
                    if (_restorable.value == null) suppressedMode = IslandMode.COMPACT_MUSIC
                } else if (suppressedMode == null) {
                    // Music started: grow the compact music pill out of the camera.
                    _restorable.value = null
                    _mode.value = IslandMode.COMPACT_MUSIC
                }
            } else if (_mode.value.isMusic() && foregroundPackage == info.packageName &&
                old?.packageName != info.packageName) {
                // The owner changed to the app that's already in front.
                suppressFor(info.packageName)
            }
        } else if (old != null) {
            // Stopped / session gone: shrink into the camera after a short grace period.
            main.removeCallbacks(hideWhenMediaGone)
            main.postDelayed(hideWhenMediaGone, MEDIA_GONE_HIDE_DELAY_MS)
        }
    }

    internal fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    internal fun setGeometry(geometry: IslandGeometry?) {
        _geometry.value = geometry
    }
}

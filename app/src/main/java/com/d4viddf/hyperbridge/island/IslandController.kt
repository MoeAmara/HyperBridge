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

    /** Incoming call card: caller, green answer + red decline. */
    INCOMING,

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
        this == IslandMode.INCOMING || this == IslandMode.TIMER_CARD || this == IslandMode.REC_CARD

/** True for the music states (real or mock music). */
fun IslandMode.isMusic(): Boolean = this == IslandMode.MUSIC || this == IslandMode.COMPACT_MUSIC

/** Card -> its pill (pills map to themselves). */
fun IslandMode.compact(): IslandMode = when (this) {
    IslandMode.EXPANDED, IslandMode.INCOMING -> IslandMode.COMPACT
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
    val radiusDp: Float = -1f
)

/**
 * Feel of the pill <-> card morph, tunable live from Island Test.
 * bounce = spring dampingRatio (lower = bouncier), speed = spring stiffness.
 */
data class IslandMotion(
    val bounce: Float = 0.70f,
    val speed: Float = 420f,
    val widthLeadMs: Float = 40f,
    val blurDp: Float = 12f,
    val squeeze: Float = 0.97f
)

/** A rounded rect described by its horizontal centre, top edge, size and radius (all px). */
data class IslandRectPx(val cx: Float, val top: Float, val w: Float, val h: Float, val r: Float) {
    val left: Float get() = cx - w / 2f
}

/** Tappable controls on the cards; their on-screen bounds are reported by the overlay. */
enum class IslandButton { PREV, PLAY_PAUSE, NEXT, ANSWER, DECLINE, HANG_UP, SPEAKER, PRIMARY, SECONDARY }

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
    const val INCOMING_HEIGHT_RATIO = 0.22f   // incoming call card height
    const val TIMER_HEIGHT_RATIO = 0.215f     // timer / stopwatch / recorder card height
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
            IslandMode.COMPACT, IslandMode.COMPACT_MUSIC, IslandMode.TIMER_PILL, IslandMode.REC_PILL ->
                pill(g, c, density, compactWidthPx(g, c, density))
            IslandMode.WIDE -> pill(g, c, density, wideWidthPx(g, c, density))
            IslandMode.EXPANDED -> card(g, c, density, CALL_HEIGHT_RATIO)
            IslandMode.INCOMING -> card(g, c, density, INCOMING_HEIGHT_RATIO)
            IslandMode.TIMER_CARD, IslandMode.REC_CARD -> card(g, c, density, TIMER_HEIGHT_RATIO)
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

    private const val MOTION_PREFS = "island_motion"

    fun loadMotion(context: Context): IslandMotion {
        val p = context.getSharedPreferences(MOTION_PREFS, Context.MODE_PRIVATE)
        val d = IslandMotion()
        return IslandMotion(
            bounce = p.getFloat("bounce", d.bounce),
            speed = p.getFloat("speed", d.speed),
            widthLeadMs = p.getFloat("widthLeadMs", d.widthLeadMs),
            blurDp = p.getFloat("blurDp", d.blurDp),
            squeeze = p.getFloat("squeeze", d.squeeze)
        )
    }

    fun saveMotion(context: Context, m: IslandMotion) {
        context.getSharedPreferences(MOTION_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("bounce", m.bounce)
            .putFloat("speed", m.speed)
            .putFloat("widthLeadMs", m.widthLeadMs)
            .putFloat("blurDp", m.blurDp)
            .putFloat("squeeze", m.squeeze)
            .apply()
    }

    private const val OPTIONS_PREFS = "island_options"

    fun loadShowOnLock(context: Context): Boolean =
        context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).getBoolean("showOnLock", false)

    fun saveShowOnLock(context: Context, value: Boolean) {
        context.getSharedPreferences(OPTIONS_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("showOnLock", value).apply()
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
        LiveKind.INCOMING_CALL -> IslandMode.INCOMING
        LiveKind.RECORDER -> IslandMode.REC_PILL
        LiveKind.TIMER, LiveKind.STOPWATCH -> IslandMode.TIMER_PILL
    }

    private fun modeBelongsTo(m: IslandMode, a: Act): Boolean = when (a.kind) {
        null -> m.isMusic()
        LiveKind.CALL -> m == IslandMode.COMPACT || m == IslandMode.EXPANDED
        LiveKind.INCOMING_CALL -> m == IslandMode.INCOMING || m == IslandMode.COMPACT
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
    }

    private fun setMode(m: IslandMode, id: String?, acts: List<Act>) {
        if (_mode.value != m) buttonRects.clear()
        shownId = id
        _shownLive.value = id?.let { i -> acts.firstOrNull { it.id == i }?.live }
        _mode.value = m
    }

    /** Re-decides what the island shows after any change. */
    private fun evaluate() {
        val acts = activities()
        val ids = acts.map { it.id }.toSet()
        dismissed.retainAll(ids)
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
                evaluate()
                return
            }
            val top = acts.firstOrNull { visible(it) && it.id != current.id }
            if (top != null && top.priority > current.priority && top.id !in quiet) {
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
        seen = activities().map { it.id }.toSet()
        setMode(mode, null, emptyList())
    }

    /** Hide button: shrink into the camera, nothing left to bring back. */
    fun hide() {
        shownId?.let { dismissed += it }
        restorableId = null
        _restorable.value = null
        setMode(IslandMode.HIDDEN, null, emptyList())
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

    /** Horizontal swipe: card -> its compact pill; pill -> into the camera; hidden -> bring back. */
    fun onSwipe() {
        _pressed.value = false
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
                    if (a != null) setMode(compactModeOf(a).let { if (it == IslandMode.INCOMING) IslandMode.COMPACT else it }, id, acts)
                } else {
                    setMode(back, null, acts)
                }
            }
        }
    }

    /** Tap on the island at screen position (x, y). */
    fun onTap(x: Float, y: Float) {
        _pressed.value = false
        val m = _mode.value
        if (!m.isCard()) {
            if (m == IslandMode.COMPACT && _shownLive.value?.kind == LiveKind.INCOMING_CALL) go(IslandMode.INCOMING)
            else m.card()?.let { go(it) }
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
            val action = when (hit) {
                IslandButton.ANSWER -> live.action(ActionRole.ANSWER)
                IslandButton.DECLINE -> live.action(ActionRole.DECLINE, ActionRole.HANG_UP)
                IslandButton.HANG_UP -> live.action(ActionRole.HANG_UP, ActionRole.DECLINE)
                IslandButton.SPEAKER -> live.action(ActionRole.SPEAKER)
                IslandButton.PRIMARY -> live.action(ActionRole.PAUSE, ActionRole.RESUME)
                IslandButton.SECONDARY -> live.action(ActionRole.STOP, ActionRole.LAP)
                else -> null
            }
            if (action?.intent != null) sendPendingIntent(action.intent)
            else if (!_locked.value) openOwnerApp()
        } else if (!_locked.value) {
            openOwnerApp()
        }
    }

    /** Long-press on a pill or card: open the app that owns the content; hide while it's open. */
    fun onLongPress(): Boolean {
        _pressed.value = false
        if (_locked.value) return false // can't open apps over the keyguard; ignore
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
    fun onPullDown() {
        _pressed.value = false
        val m = _mode.value
        if (m == IslandMode.COMPACT && _shownLive.value?.kind == LiveKind.INCOMING_CALL) go(IslandMode.INCOMING)
        else m.card()?.let { go(it) }
    }

    /** A touch anywhere outside the island: an expanded card collapses back to its pill. */
    fun onOutsideTouch() {
        val m = _mode.value
        if (m.isCard() && m != IslandMode.INCOMING) go(m.compact())
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

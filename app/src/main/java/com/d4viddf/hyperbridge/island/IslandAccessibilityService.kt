package com.d4viddf.hyperbridge.island

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws our own island as TYPE_ACCESSIBILITY_OVERLAY windows aligned to the
 * display cutout (the punch-hole camera).
 *
 * Two windows:
 *  - a large, fixed, NON-touchable draw window (the morphing island is drawn here;
 *    it is never relaid out per frame, so animation stays smooth);
 *  - a small transparent touch window sized to the island's target bounds (swipe, tap,
 *    pull-down, outside-touch to collapse). It is resized only when the mode / calibration
 *    changes. While hidden it shrinks to a restore zone around the camera only if there is
 *    something to bring back; otherwise it is non-touchable, so nothing else eats touches.
 */
class IslandAccessibilityService : AccessibilityService(), LifecycleOwner, ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlay: ComposeView? = null
    private var touchView: View? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private var touchJob: Job? = null
    private var mediaJob: Job? = null
    private var mediaWatcher: IslandMediaWatcher? = null
    private var lockReceiver: BroadcastReceiver? = null

    private fun refreshLocked() {
        val km = getSystemService(KeyguardManager::class.java)
        IslandController.setLocked(km?.isKeyguardLocked == true)
    }

    private fun registerLockReceiver() {
        if (lockReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> IslandController.setLocked(false)
                    else -> {
                        refreshLocked()
                        // The keyguard may engage a moment after the screen turns off / on.
                        overlay?.postDelayed({ refreshLocked() }, 600L)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            registerReceiver(r, f)
            lockReceiver = r
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register lock receiver", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WindowManager::class.java)
        IslandController.setCalibration(IslandCalibrationStore.load(this))
        IslandController.setMotion(IslandCalibrationStore.loadMotion(this))
        IslandController.setShowOnLock(IslandCalibrationStore.loadShowOnLock(this))
        IslandMedia.appContext = applicationContext
        IslandController.setServiceRunning(true)
        addOverlay()
        addTouchWindow()
        registerLockReceiver()
        refreshLocked()
        touchJob?.cancel()
        touchJob = lifecycleScope.launch {
            combine(
                IslandController.mode,
                IslandController.geometry,
                IslandController.calibration,
                IslandController.restorable
            ) { m, g, c, r -> TouchState(m, g, c, r) }
                .collect { updateTouchWindow(it) }
        }
        // Real music: watch media sessions; retry until notification access is granted.
        val watcher = mediaWatcher ?: IslandMediaWatcher(this).also { mediaWatcher = it }
        mediaJob?.cancel()
        mediaJob = lifecycleScope.launch {
            while (isActive && !watcher.tryStart()) {
                delay(3000)
            }
        }
    }

    private data class TouchState(
        val mode: IslandMode,
        val geometry: IslandGeometry?,
        val calibration: IslandCalibration,
        val restorable: IslandMode?
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track the foreground app so the island hides while you're inside the app that owns it.
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.isEmpty() || pkg in IGNORED_PACKAGES || pkg == currentImePackage()) return
        IslandController.onForegroundPackage(pkg)
    }

    private fun currentImePackage(): String? = try {
        android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
        )?.substringBefore('/')
    } catch (_: Exception) {
        null
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = overlay ?: return
        val geometry = computeGeometry() ?: return
        IslandController.setGeometry(geometry)
        try {
            windowManager?.updateViewLayout(view, buildParams(geometry))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update island window", e)
        }
    }

    override fun onDestroy() {
        // Never leave status bar icons hidden by the Shizuku test.
        if (IslandStatusBar.modified) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                IslandStatusBar.run(IslandStatusBar.CMD_RESTORE)
            }
        }
        lockReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        lockReceiver = null
        touchJob?.cancel()
        touchJob = null
        mediaJob?.cancel()
        mediaJob = null
        mediaWatcher?.stop()
        mediaWatcher = null
        removeTouchWindow()
        removeOverlay()
        IslandController.setServiceRunning(false)
        IslandController.setGeometry(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    private fun addOverlay() {
        if (overlay != null) return
        val geometry = computeGeometry() ?: return
        IslandController.setGeometry(geometry)

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandAccessibilityService)
            setViewTreeViewModelStoreOwner(this@IslandAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@IslandAccessibilityService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val mode by IslandController.mode.collectAsState()
                val geo by IslandController.geometry.collectAsState()
                val cal by IslandController.calibration.collectAsState()
                val media by IslandController.media.collectAsState()
                geo?.let { IslandOverlay(geometry = it, mode = mode, calibration = cal, media = media) }
            }
        }

        try {
            windowManager?.addView(view, buildParams(geometry))
            overlay = view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add island window", e)
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove island window", e)
        }
        overlay = null
    }

    private fun addTouchWindow() {
        if (touchView != null) return
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "HyperBridgeIslandTouch"
        }
        val view = IslandTouchView()
        try {
            windowManager?.addView(view, params)
            touchView = view
            touchParams = params
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add island touch window", e)
        }
    }

    private fun removeTouchWindow() {
        val view = touchView ?: return
        try {
            windowManager?.removeViewImmediate(view)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove island touch window", e)
        }
        touchView = null
        touchParams = null
    }

    /** Resizes the touch window to the island's TARGET bounds (not per animation frame). */
    private fun updateTouchWindow(state: TouchState) {
        val view = touchView ?: return
        val params = touchParams ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val geometry = state.geometry
        val density = resources.displayMetrics.density
        val rect: IslandRectPx? = when {
            geometry == null -> null
            state.mode != IslandMode.HIDDEN ->
                IslandLayout.target(state.mode, geometry, state.calibration, density)
            state.restorable != null ->
                IslandLayout.restoreZone(geometry, state.calibration, density)
            else -> null
        }
        if (rect == null) {
            params.flags = params.flags or notTouchable
            params.x = 0
            params.y = 0
            params.width = 1
            params.height = 1
        } else {
            params.flags = params.flags and notTouchable.inv()
            params.x = rect.left.roundToInt()
            params.y = rect.top.roundToInt()
            params.width = rect.w.roundToInt().coerceAtLeast(1)
            params.height = rect.h.roundToInt().coerceAtLeast(1)
        }
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update island touch window", e)
        }
    }

    /**
     * Transparent view on top of the island:
     *  - horizontal swipe (no finger-follow) -> IslandController.onSwipe(), fired once per gesture;
     *  - pull down -> expand; tap -> expand / card buttons;
     *  - any touch outside the island -> collapse an expanded card.
     */
    private inner class IslandTouchView : View(this@IslandAccessibilityService) {
        private val LONG_PRESS_MS = 400L
        private val density = resources.displayMetrics.density
        private val swipeSlop = 24f * density
        private val flingMinDistance = 8f * density
        private val flingVelocity = 600f * density
        private val tapSlop = ViewConfiguration.get(this@IslandAccessibilityService).scaledTouchSlop.toFloat()
        private var downX = 0f
        private var downY = 0f
        private var fired = false
        private var tracker: VelocityTracker? = null
        private val longPress = Runnable {
            if (!fired && IslandController.onLongPress()) {
                // Consumed: the release must not also count as a tap / swipe.
                fired = true
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    IslandController.onOutsideTouch()
                    return false
                }
                MotionEvent.ACTION_DOWN -> {
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    downX = event.rawX
                    downY = event.rawY
                    fired = false
                    removeCallbacks(longPress)
                    postDelayed(longPress, LONG_PRESS_MS)
                    if (IslandController.mode.value != IslandMode.HIDDEN) IslandController.onPress(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    if (!fired) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(dx) > tapSlop || abs(dy) > tapSlop) {
                            removeCallbacks(longPress)
                            IslandController.onPress(false)
                        }
                        if (abs(dx) > swipeSlop && abs(dx) > abs(dy) * 1.2f) {
                            fired = true
                            IslandController.onSwipe()
                        } else if (dy > swipeSlop && dy > abs(dx) * 1.2f) {
                            fired = true
                            IslandController.onPullDown()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    IslandController.onPress(false)
                    val t = tracker
                    var vx = 0f
                    if (t != null) {
                        t.addMovement(event)
                        t.computeCurrentVelocity(1000)
                        vx = t.xVelocity
                    }
                    t?.recycle()
                    tracker = null
                    if (!fired) {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (abs(vx) > flingVelocity && abs(dx) > flingMinDistance && abs(dx) > abs(dy)) {
                            IslandController.onSwipe()
                        } else if (abs(dx) <= tapSlop && abs(dy) <= tapSlop) {
                            IslandController.onTap(event.rawX, event.rawY)
                        }
                    }
                    fired = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    IslandController.onPress(false)
                    tracker?.recycle()
                    tracker = null
                    fired = false
                }
            }
            return true
        }
    }

    private fun buildParams(geometry: IslandGeometry): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        // Tallest content is the music card (~0.42 x screen width) below the status bar,
        // plus room for the vertical calibration offset.
        val heightPx = (geometry.statusBarHeightPx +
                geometry.screenWidthPx * IslandLayout.MUSIC_HEIGHT_RATIO +
                72f * density).toInt()

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "HyperBridgeIsland"
        }
    }

    private fun computeGeometry(): IslandGeometry? {
        val wm = windowManager ?: return null
        return try {
            val metrics = wm.maximumWindowMetrics
            val bounds = metrics.bounds
            val insets = metrics.windowInsets
            val statusBar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
            val density = resources.displayMetrics.density
            val cutoutRect = insets.displayCutout?.boundingRectTop

            if (cutoutRect != null && !cutoutRect.isEmpty) {
                IslandGeometry(
                    screenWidthPx = bounds.width(),
                    statusBarHeightPx = statusBar,
                    cameraCenterXPx = cutoutRect.exactCenterX(),
                    cameraCenterYPx = cutoutRect.exactCenterY(),
                    cameraDiameterPx = minOf(cutoutRect.width(), cutoutRect.height()).toFloat(),
                    fromCutout = true
                )
            } else {
                // Fallback: centred at the top, inside the status bar.
                val sb = if (statusBar > 0) statusBar else (28f * density).toInt()
                IslandGeometry(
                    screenWidthPx = bounds.width(),
                    statusBarHeightPx = sb,
                    cameraCenterXPx = bounds.width() / 2f,
                    cameraCenterYPx = sb / 2f,
                    cameraDiameterPx = sb * 0.5f,
                    fromCutout = false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute island geometry", e)
            null
        }
    }

    companion object {
        private const val TAG = "IslandService"

        /** Window events from these never count as "the user switched apps". */
        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.miui.securitycenter.overlay",
            "com.lbe.security.miui"
        )
    }
}

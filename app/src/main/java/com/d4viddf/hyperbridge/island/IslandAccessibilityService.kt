package com.d4viddf.hyperbridge.island

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Draws our own island as TYPE_ACCESSIBILITY_OVERLAY windows aligned to the
 * display cutout (the punch-hole camera).
 *
 * Two windows:
 *  - a large, fixed, NON-touchable draw window (the morphing island is drawn here;
 *    it is never relaid out per frame, so animation stays smooth);
 *  - a small transparent touch window sized to the island's target bounds, used only
 *    for swipe-to-dismiss. It is resized only when the mode / calibration changes and
 *    is non-touchable while the island is hidden, so nothing outside the pill eats touches.
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
        IslandController.setServiceRunning(true)
        addOverlay()
        addTouchWindow()
        touchJob?.cancel()
        touchJob = lifecycleScope.launch {
            combine(
                IslandController.mode,
                IslandController.geometry,
                IslandController.calibration
            ) { m, g, c -> Triple(m, g, c) }
                .collect { (m, g, c) -> updateTouchWindow(m, g, c) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The test build does not react to accessibility events.
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
        touchJob?.cancel()
        touchJob = null
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
                geo?.let { IslandOverlay(geometry = it, mode = mode, calibration = cal) }
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
    private fun updateTouchWindow(mode: IslandMode, geometry: IslandGeometry?, calibration: IslandCalibration) {
        val view = touchView ?: return
        val params = touchParams ?: return
        val notTouchable = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (mode == IslandMode.HIDDEN || geometry == null) {
            params.flags = params.flags or notTouchable
            params.x = 0
            params.y = 0
            params.width = 1
            params.height = 1
        } else {
            val t = IslandLayout.target(mode, geometry, calibration, resources.displayMetrics.density)
            params.flags = params.flags and notTouchable.inv()
            params.x = t.left.roundToInt()
            params.y = t.top.roundToInt()
            params.width = t.w.roundToInt().coerceAtLeast(1)
            params.height = t.h.roundToInt().coerceAtLeast(1)
        }
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update island touch window", e)
        }
    }

    /** Transparent view that turns horizontal drags on the pill into swipe events. */
    private inner class IslandTouchView : View(this@IslandAccessibilityService) {
        private var downX = 0f
        private var tracker: VelocityTracker? = null

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    downX = event.rawX
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    IslandController.emitDrag(IslandDrag.Move(event.rawX - downX))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val t = tracker
                    var velocity = 0f
                    if (t != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        t.addMovement(event)
                        t.computeCurrentVelocity(1000)
                        velocity = t.xVelocity
                    }
                    t?.recycle()
                    tracker = null
                    IslandController.emitDrag(IslandDrag.End(event.rawX - downX, velocity))
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
    }
}

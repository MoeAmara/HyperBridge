package com.d4viddf.hyperbridge.island

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
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
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Draws our own island as a TYPE_ACCESSIBILITY_OVERLAY window aligned to the
 * display cutout (the punch-hole camera). Accessibility overlays sit above the
 * status bar and are treated as trusted windows, so touches pass through.
 *
 * Phase 1: the window is NOT touchable at all; everything underneath keeps
 * working. Tapping the island itself comes in a later phase.
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
        IslandController.setServiceRunning(true)
        addOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 1 does not react to accessibility events.
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
                geo?.let { IslandOverlay(geometry = it, mode = mode) }
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

    private fun buildParams(geometry: IslandGeometry): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        // Tallest content is the music card (~0.42 x screen width) below the status bar.
        val heightPx = (geometry.statusBarHeightPx +
                geometry.screenWidthPx * IslandSpec.MUSIC_HEIGHT_RATIO +
                48f * density).toInt()

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

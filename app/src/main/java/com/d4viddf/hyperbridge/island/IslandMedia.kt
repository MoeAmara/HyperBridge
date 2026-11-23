package com.d4viddf.hyperbridge.island

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import com.d4viddf.hyperbridge.service.NotificationReaderService
import kotlin.math.max
import kotlin.math.roundToInt

/** Snapshot of the media session the island shows. */
data class IslandMediaInfo(
    val sessionKey: String,
    val packageName: String,
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val appIcon: ImageBitmap?,
    val accent: Color?,
    val playing: Boolean,
    val positionMs: Long,
    val positionUpdateRealtimeMs: Long,
    val speed: Float,
    val durationMs: Long
) {
    /** Current position, extrapolated from the last PlaybackState update. */
    fun currentPositionMs(nowRealtimeMs: Long = SystemClock.elapsedRealtime()): Long {
        var pos = positionMs
        if (playing && positionUpdateRealtimeMs > 0L) {
            pos += ((nowRealtimeMs - positionUpdateRealtimeMs) * speed).toLong()
        }
        if (durationMs > 0L) pos = pos.coerceAtMost(durationMs)
        return pos.coerceAtLeast(0L)
    }
}

/** Holds the controller of the session being shown, for the card's buttons. */
object IslandMedia {
    private const val TAG = "IslandMedia"

    @Volatile
    internal var controller: MediaController? = null

    @Volatile
    internal var appContext: Context? = null

    fun press(button: IslandButton) {
        val c = controller ?: return
        try {
            val t = c.transportControls
            when (button) {
                IslandButton.PREV -> t.skipToPrevious()
                IslandButton.NEXT -> t.skipToNext()
                IslandButton.PLAY_PAUSE -> {
                    if (isPlayingState(c.playbackState?.state)) t.pause() else t.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transport control failed", e)
        }
    }

    /** Opens the playing app (its session activity, or its launcher activity). */
    fun openApp(): Boolean {
        val c = controller ?: return false
        val pi = c.sessionActivity
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    pi.send(null, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                return true
            } catch (e: Exception) {
                Log.e(TAG, "sessionActivity failed", e)
            }
        }
        val ctx = appContext ?: return false
        return try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(c.packageName) ?: return false
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Launch intent failed", e)
            false
        }
    }
}

internal fun isPlayingState(state: Int?): Boolean = when (state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
    else -> false
}

/**
 * Watches the active media sessions of every app (needs HyperBridge's notification
 * listener to be enabled) and reports the one the island should show.
 * All callbacks run on the main thread.
 */
class IslandMediaWatcher(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, NotificationReaderService::class.java)

    private var started = false
    private val watched = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var currentToken: MediaSession.Token? = null

    // Bitmap work is cached per metadata object, so position updates stay cheap.
    private var cachedMetadata: MediaMetadata? = null
    private var cachedArt: ImageBitmap? = null
    private var cachedAccent: Color? = null
    private val iconCache = HashMap<String, ImageBitmap?>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        onSessions(list ?: emptyList())
    }

    /** Starts watching; returns false while notification access is missing. */
    fun tryStart(): Boolean {
        if (started) return true
        return try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            IslandMedia.appContext = context.applicationContext
            IslandController.setMediaAccess(true)
            onSessions(manager.getActiveSessions(listenerComponent))
            true
        } catch (e: SecurityException) {
            IslandController.setMediaAccess(false)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Could not start media watcher", e)
            IslandController.setMediaAccess(false)
            false
        }
    }

    fun stop() {
        if (started) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
        }
        started = false
        for ((c, cb) in watched.values) {
            try {
                c.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        watched.clear()
        currentToken = null
        IslandMedia.controller = null
        IslandController.setMedia(null)
    }

    private fun onSessions(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        val iterator = watched.entries.iterator()
        while (iterator.hasNext()) {
            val (token, pair) = iterator.next()
            if (token !in tokens) {
                try {
                    pair.first.unregisterCallback(pair.second)
                } catch (_: Exception) {
                }
                iterator.remove()
            }
        }
        for (c in list) {
            if (watched.containsKey(c.sessionToken)) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = reevaluate()
                override fun onMetadataChanged(metadata: MediaMetadata?) = reevaluate()
                override fun onSessionDestroyed() = reevaluate()
            }
            try {
                c.registerCallback(cb, handler)
                watched[c.sessionToken] = c to cb
            } catch (e: Exception) {
                Log.e(TAG, "registerCallback failed", e)
            }
        }
        reevaluate()
    }

    private fun reevaluate() {
        val controllers = watched.values.map { it.first }
        // 1) Any session that is playing wins. 2) Otherwise keep the shown one while it is paused.
        val chosen = controllers.firstOrNull { isPlayingState(it.playbackState?.state) }
            ?: controllers.firstOrNull {
                it.sessionToken == currentToken && it.playbackState?.state == PlaybackState.STATE_PAUSED
            }
        currentToken = chosen?.sessionToken
        IslandMedia.controller = chosen
        IslandController.setMedia(chosen?.let { buildInfo(it) })
    }

    private fun buildInfo(c: MediaController): IslandMediaInfo {
        val md = c.metadata
        val state = c.playbackState
        if (md !== cachedMetadata) {
            cachedMetadata = md
            val bmp = md?.let { loadArt(it) }
            cachedArt = bmp?.asImageBitmap()
            cachedAccent = bmp?.let { accentOf(it) }
        }
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: appLabel(c.packageName)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        return IslandMediaInfo(
            sessionKey = c.packageName + "#" + c.sessionToken.hashCode(),
            packageName = c.packageName,
            title = title,
            artist = artist,
            art = cachedArt,
            appIcon = iconCache.getOrPut(c.packageName) { loadIcon(c.packageName) },
            accent = cachedAccent,
            playing = isPlayingState(state?.state),
            positionMs = state?.position ?: 0L,
            positionUpdateRealtimeMs = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L
        )
    }

    private fun loadArt(md: MediaMetadata): Bitmap? {
        val direct = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (direct != null) return downscale(direct)
        val uriString = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: return null
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == ContentResolver.SCHEME_CONTENT || uri.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE) {
                context.contentResolver.loadThumbnail(uri, Size(ART_PX, ART_PX), null)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun downscale(src: Bitmap): Bitmap? = try {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val largest = max(soft.width, soft.height)
        if (largest <= ART_PX) {
            soft
        } else {
            val scale = ART_PX.toFloat() / largest
            Bitmap.createScaledBitmap(
                soft,
                (soft.width * scale).roundToInt().coerceAtLeast(1),
                (soft.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "Art downscale failed", e)
        null
    }

    private fun accentOf(bmp: Bitmap): Color? = try {
        val soft = if (bmp.config == Bitmap.Config.HARDWARE) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
        val p = Palette.from(soft).clearFilters().generate()
        val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch
        swatch?.let { Color(it.rgb) }
    } catch (_: Exception) {
        null
    }

    private fun loadIcon(pkg: String): ImageBitmap? = try {
        context.packageManager.getApplicationIcon(pkg).toBitmap(ICON_PX, ICON_PX).asImageBitmap()
    } catch (_: Exception) {
        null
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    companion object {
        private const val TAG = "IslandMediaWatcher"
        private const val ART_PX = 192
        private const val ICON_PX = 72
    }
}

package com.d4viddf.hyperbridge.island

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Real actions for the island's call buttons when the call notification carries no usable action:
 * 1) TelecomManager (needs the ANSWER_PHONE_CALLS runtime permission),
 * 2) Shizuku shell key events (KEYCODE_CALL / KEYCODE_ENDCALL).
 * Plus the experimental speaker toggle and the opt-in "hide system incoming-call popup" guard.
 */
object IslandCallControl {
    private const val TAG = "IslandCallControl"
    private const val PREFS = "island_options"
    private const val HU_KEY = "heads_up_notifications_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** Last button / fallback result, shown in Island Test. */
    private val _actionStatus = MutableStateFlow("idle")
    val actionStatus: StateFlow<String> = _actionStatus.asStateFlow()

    /** Call notification keys where the speaker fallback failed: hide the speaker button for them. */
    private val _speakerUnsupported = MutableStateFlow<Set<String>>(emptySet())
    val speakerUnsupported: StateFlow<Set<String>> = _speakerUnsupported.asStateFlow()

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    /** Opt-in (default OFF): hide the system incoming-call heads-up via Shizuku while ringing. */
    private val _hidePopup = MutableStateFlow(false)
    val hidePopup: StateFlow<Boolean> = _hidePopup.asStateFlow()

    private val _popupStatus = MutableStateFlow("idle")
    val popupStatus: StateFlow<String> = _popupStatus.asStateFlow()

    fun status(text: String) {
        Log.i(TAG, text)
        _actionStatus.value = text
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    fun toast(context: Context?, text: String) {
        val ctx = context ?: return
        main.post { try { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() } catch (_: Exception) {} }
    }

    // ---- Answer / end ----------------------------------------------------------------------

    /** Answers the ringing call: Telecom first, then Shizuku KEYCODE_CALL if it's still ringing. */
    fun answer(context: Context, sbnKey: String) {
        scope.launch {
            val telecom = telecomAnswer(context)
            status("answer: telecom → $telecom")
            delay(1200)
            if (!IslandNotifications.isLive(sbnKey, LiveKind.INCOMING_CALL)) {
                status("answer: done via telecom ($telecom)")
                return@launch
            }
            val sh = shell("input keyevent KEYCODE_CALL")
            status("answer: telecom → $telecom; still ringing → shizuku KEYCODE_CALL → ${sh.firstLine()}")
            delay(1200)
            if (IslandNotifications.isLive(sbnKey, LiveKind.INCOMING_CALL)) {
                status(_actionStatus.value + "\nanswer FAILED: call still ringing — no route worked")
                toast(context, "No action available")
            }
        }
    }

    /** Declines / hangs up: Telecom endCall first, then Shizuku KEYCODE_ENDCALL if the call is still there. */
    fun end(context: Context, sbnKey: String) {
        scope.launch {
            val telecom = telecomEnd(context)
            status("end: telecom → $telecom")
            delay(1200)
            if (!IslandNotifications.isLive(sbnKey, LiveKind.INCOMING_CALL, LiveKind.CALL)) {
                status("end: done via telecom ($telecom)")
                return@launch
            }
            val sh = shell("input keyevent KEYCODE_ENDCALL")
            status("end: telecom → $telecom; call still there → shizuku KEYCODE_ENDCALL → ${sh.firstLine()}")
            delay(1200)
            if (IslandNotifications.isLive(sbnKey, LiveKind.INCOMING_CALL, LiveKind.CALL)) {
                status(_actionStatus.value + "\nend FAILED: call still there — no route worked")
                toast(context, "No action available")
            }
        }
    }

    private fun telecomAnswer(context: Context): String {
        if (!hasPermission(context)) return "no permission (Grant call control)"
        return try {
            val tm = context.getSystemService(TelecomManager::class.java) ?: return "no TelecomManager"
            @Suppress("DEPRECATION")
            tm.acceptRingingCall()
            "called acceptRingingCall()"
        } catch (e: Exception) {
            "error ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun telecomEnd(context: Context): String {
        if (!hasPermission(context)) return "no permission (Grant call control)"
        return try {
            val tm = context.getSystemService(TelecomManager::class.java) ?: return "no TelecomManager"
            @Suppress("DEPRECATION")
            val ok = tm.endCall()
            "endCall()=$ok"
        } catch (e: Exception) {
            "error ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun shell(cmd: String): String =
        if (IslandStatusBar.state() != IslandStatusBar.ShizukuState.GRANTED) "skipped: ${IslandStatusBar.state().label}"
        else IslandStatusBar.run(cmd)

    private fun String.firstLine(): String = lineSequence().firstOrNull()?.trim().orEmpty()

    // ---- Speaker (experimental) ------------------------------------------------------------

    fun speakerPossible(live: IslandLive): Boolean =
        live.kind == LiveKind.CALL && live.sbnKey !in _speakerUnsupported.value

    /**
     * Experimental: routes communication audio to the built-in speaker. A third-party app may not
     * be allowed to change the routing of a cellular call; if it clearly fails, the button hides.
     */
    fun toggleSpeaker(context: Context, sbnKey: String) {
        try {
            val am = context.getSystemService(AudioManager::class.java) ?: run {
                markSpeakerUnsupported(sbnKey, "no AudioManager"); return
            }
            val current = am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            if (current) {
                am.clearCommunicationDevice()
                _speakerOn.value = false
                status("speaker: clearCommunicationDevice() — check if the call left the speaker")
            } else {
                val dev = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                val ok = dev != null && am.setCommunicationDevice(dev)
                if (!ok) {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                    @Suppress("DEPRECATION")
                    val on = am.isSpeakerphoneOn
                    if (!on) {
                        markSpeakerUnsupported(sbnKey, "setCommunicationDevice=false, setSpeakerphoneOn ignored")
                        return
                    }
                }
                _speakerOn.value = true
                status("speaker: set to built-in speaker (ok=$ok) — UNCERTAIN for cellular calls, check by ear")
            }
        } catch (e: Exception) {
            markSpeakerUnsupported(sbnKey, "error ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun markSpeakerUnsupported(sbnKey: String, why: String) {
        _speakerUnsupported.value = _speakerUnsupported.value + sbnKey
        status("speaker: not possible ($why) — button hidden for this call")
    }

    // ---- Hide system incoming-call popup (Shizuku, opt-in) ---------------------------------

    fun loadHidePopup(context: Context) {
        _hidePopup.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("hideCallPopup", false)
    }

    fun setHidePopup(context: Context, value: Boolean) {
        _hidePopup.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("hideCallPopup", value).apply()
    }

    /** Turns heads-up off (remembering the previous value) — only while an incoming call rings. */
    suspend fun suppressHeadsUp(context: Context) = withContext(NonCancellable) {
        if (IslandStatusBar.state() != IslandStatusBar.ShizukuState.GRANTED) {
            _popupStatus.value = "skipped: ${IslandStatusBar.state().label}"
            return@withContext
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("huChanged", false)) {
            val got = IslandStatusBar.run("settings get global $HU_KEY")
            val prev = got.lineSequence().drop(1).firstOrNull()?.trim().orEmpty()
            if (!got.startsWith("exit=0")) {
                _popupStatus.value = "read failed → ${got.firstLine()} (nothing changed)"
                return@withContext
            }
            // Remember BEFORE changing, so a crash can never leave heads-up off.
            p.edit().putBoolean("huChanged", true).putString("huPrev", prev).commit()
        }
        val put = IslandStatusBar.run("settings put global $HU_KEY 0")
        _popupStatus.value = "ringing → heads-up off → ${put.firstLine()} (previous=${p.getString("huPrev", "?")})"
        if (!put.startsWith("exit=0")) {
            p.edit().putBoolean("huChanged", false).commit()
        }
    }

    /** Restores heads-up to what it was, if we changed it. Safe to call any time. */
    suspend fun restoreHeadsUp(context: Context, why: String) = withContext(NonCancellable) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("huChanged", false)) return@withContext
        if (IslandStatusBar.state() != IslandStatusBar.ShizukuState.GRANTED) {
            _popupStatus.value = "RESTORE PENDING ($why): ${IslandStatusBar.state().label} — will retry when Shizuku is back"
            return@withContext
        }
        val prev = p.getString("huPrev", null)
        val value = if (prev == null || prev == "null" || prev.isBlank()) "1" else prev
        val put = IslandStatusBar.run("settings put global $HU_KEY $value")
        if (put.startsWith("exit=0")) p.edit().putBoolean("huChanged", false).remove("huPrev").commit()
        _popupStatus.value = "$why → heads-up restored to $value → ${put.firstLine()}"
    }

    fun headsUpChangedByUs(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("huChanged", false)
}

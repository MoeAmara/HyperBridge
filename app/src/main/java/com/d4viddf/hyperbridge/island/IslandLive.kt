package com.d4viddf.hyperbridge.island

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Kinds of live content read from ongoing notifications (music comes from media sessions). */
enum class LiveKind(val priority: Int) {
    INCOMING_CALL(50),
    CALL(40),
    RECORDER(30),
    TIMER(20),
    STOPWATCH(20)
}

/** What a notification action does, guessed from CallStyle extras or the action title. */
enum class ActionRole { ANSWER, DECLINE, HANG_UP, SPEAKER, MUTE, PAUSE, RESUME, STOP, LAP, OTHER }

data class LiveAction(val title: String, val role: ActionRole, val intent: PendingIntent?)

/** One live activity (call / timer / stopwatch / recorder) the island can show. */
data class IslandLive(
    /** Notification key + kind: an incoming call that becomes ongoing is a new activity. */
    val id: String,
    val sbnKey: String,
    val kind: LiveKind,
    val packageName: String,
    val title: String,
    val text: String,
    val avatar: ImageBitmap?,
    /** Chronometer base (wall clock ms, Notification.when) — null when no running clock. */
    val chronoBaseMs: Long?,
    val countDown: Boolean,
    /** Time shown in the notification text when there is no running chronometer (e.g. "04:56"). */
    val fixedTime: String?,
    val paused: Boolean,
    val actions: List<LiveAction>,
    val contentIntent: PendingIntent?,
    val postTime: Long,
    /** Timer: full length in ms (from the notification, else the largest remaining time seen). */
    val totalMs: Long? = null
) {
    fun action(vararg roles: ActionRole): LiveAction? =
        roles.firstNotNullOfOrNull { r -> actions.firstOrNull { it.role == r } }

    /** Display time "mm:ss" / "h:mm:ss", computed locally so it ticks smoothly. */
    fun timeText(nowMs: Long = System.currentTimeMillis()): String? {
        val base = chronoBaseMs
        if (base != null && !paused) {
            val ms = if (countDown) base - nowMs else nowMs - base
            return formatDuration(((ms.coerceAtLeast(0L) + if (countDown) 999L else 0L) / 1000L))
        }
        return fixedTime
    }

    /** Timer: remaining fraction 0..1 for the progress ring (null if unknown). */
    fun remainingFraction(nowMs: Long = System.currentTimeMillis()): Float? {
        val total = totalMs ?: return null
        if (total <= 0L) return null
        val remaining = remainingMs(nowMs) ?: return null
        return (remaining.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Timer: remaining ms (running clock or parsed fixed time). */
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long? {
        val base = chronoBaseMs
        if (base != null && !paused && countDown) return (base - nowMs).coerceAtLeast(0L)
        return fixedTime?.let { parseClockMs(it) }
    }

    /** Stopwatch / recorder: elapsed ms (running clock or parsed fixed time). */
    fun elapsedMs(nowMs: Long = System.currentTimeMillis()): Long? {
        val base = chronoBaseMs
        if (base != null && !paused && !countDown) return (nowMs - base).coerceAtLeast(0L)
        return fixedTime?.let { parseClockMs(it) }
    }
}

/** "mm:ss" / "h:mm:ss" -> ms. */
fun parseClockMs(t: String): Long? {
    val parts = t.split(':').map { it.toLongOrNull() ?: return null }
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]) * 1000L
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
        else -> null
    }
}

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** Sends a PendingIntent (action / content intent), allowing a background activity start. */
internal fun sendPendingIntent(pi: PendingIntent?): Boolean {
    if (pi == null) return false
    return try {
        if (Build.VERSION.SDK_INT >= 34) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pi.send(null, 0, null, null, null, null, opts.toBundle())
        } else {
            pi.send()
        }
        true
    } catch (e: Exception) {
        Log.e("IslandLive", "PendingIntent send failed", e)
        false
    }
}

/**
 * Reads calls, timers, stopwatches and recorders from the ongoing notifications seen by
 * HyperBridge's NotificationReaderService and hands them to IslandController.
 * Detection is semantic (CallStyle, category, chronometer flags) with package names as hints.
 */
object IslandNotifications {
    private const val TAG = "IslandNotifications"
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: NotificationListenerService? = null
        private set

    private val live = LinkedHashMap<String, IslandLive>() // by sbn key
    private val avatarCache = HashMap<String, ImageBitmap?>()

    /** Local clock for recorders / stopwatches whose notification carries no usable time. */
    private class LocalClock(var startMs: Long) {
        var pausedAt: Long? = null
    }
    private val clocks = HashMap<String, LocalClock>()

    /** Largest remaining time seen per timer (its total, when the notification doesn't say). */
    private val timerTotals = HashMap<String, Long>()

    private val CALL_PACKAGES = setOf(
        "com.android.incallui", "com.google.android.dialer", "com.android.server.telecom",
        "com.android.phone", "com.android.dialer", "com.samsung.android.incallui"
    )
    private val CLOCK_PACKAGES = setOf("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage")
    private val RECORDER_PACKAGES = setOf(
        "com.android.soundrecorder", "com.miui.soundrecorder", "com.google.android.apps.recorder",
        "com.miui.screenrecorder", "com.sec.android.app.voicenote"
    )

    fun onListenerConnected(service: NotificationListenerService) {
        listener = service
        val all = try { service.activeNotifications } catch (_: Exception) { null } ?: return
        main.post {
            live.clear()
            for (sbn in all) parse(service, sbn)?.let { live[sbn.key] = it }
            publish()
        }
    }

    fun onListenerDisconnected(service: NotificationListenerService) {
        if (listener === service) listener = null
        main.post {
            live.clear()
            publish()
        }
    }

    fun onPosted(service: NotificationListenerService, sbn: StatusBarNotification) {
        listener = service
        val parsed = try { parse(service, sbn) } catch (e: Exception) {
            Log.e(TAG, "parse failed", e); null
        }
        main.post {
            if (parsed != null) live[sbn.key] = parsed else live.remove(sbn.key)
            publish()
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        synchronized(clocks) {
            clocks.remove(sbn.key)
            timerTotals.remove(sbn.key)
        }
        main.post {
            if (live.remove(sbn.key) != null) publish()
        }
    }

    private fun publish() {
        IslandController.setLive(live.values.toList())
    }

    // ---------------------------------------------------------------------------------------

    private fun parse(context: Context, sbn: StatusBarNotification): IslandLive? {
        if (sbn.packageName == context.packageName) return null
        val n = sbn.notification ?: return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras ?: Bundle()
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("MediaStyle")) return null
        val pkg = sbn.packageName
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0 ||
                n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        val category = n.category ?: ""
        val channel = (n.channelId ?: "").lowercase()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val showChrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false)
        val callStyle = template.contains("CallStyle")
        val callType = extras.getInt("android.callType", 0) // 1 incoming, 2 ongoing, 3 screening
        val hasCallPerson = extras.containsKey(Notification.EXTRA_CALL_PERSON)
        val hay = "$title $text $channel".lowercase()

        val rawActions = n.actions?.toList() ?: emptyList()

        val kind: LiveKind = when {
            category == Notification.CATEGORY_MISSED_CALL -> null
            callStyle || category == Notification.CATEGORY_CALL || hasCallPerson ||
                    (pkg in CALL_PACKAGES && ongoing) -> {
                if (!ongoing && !callStyle && n.fullScreenIntent == null) null
                else {
                    val answerish = extras.containsKey("android.answerIntent") ||
                            rawActions.any { roleOf(it.title?.toString()) == ActionRole.ANSWER }
                    val incoming = callType == 1 || callType == 3 ||
                            (callType == 0 && answerish && !showChrono)
                    if (incoming) LiveKind.INCOMING_CALL else LiveKind.CALL
                }
            }
            pkg in RECORDER_PACKAGES && ongoing -> LiveKind.RECORDER
            ongoing && showChrono && (hay.contains("record") || hay.contains("تسجيل")) -> LiveKind.RECORDER
            category == "stopwatch" && ongoing -> LiveKind.STOPWATCH
            ongoing && (pkg in CLOCK_PACKAGES || category == Notification.CATEGORY_ALARM ||
                    category == Notification.CATEGORY_PROGRESS || showChrono) -> {
                val sw = hay.contains("stopwatch") || hay.contains("ساعة الإيقاف") || hay.contains("ساعه الايقاف") ||
                        hay.contains("chronom")
                val tm = hay.contains("timer") || hay.contains("مؤقت") || hay.contains("countdown") || countDown
                when {
                    !showChrono && timeIn("$title $text") == null && pkg !in CLOCK_PACKAGES -> null
                    sw -> LiveKind.STOPWATCH
                    tm -> LiveKind.TIMER
                    pkg in CLOCK_PACKAGES && showChrono -> if (countDown) LiveKind.TIMER else LiveKind.STOPWATCH
                    pkg in CLOCK_PACKAGES && timeIn("$title $text") != null -> LiveKind.TIMER
                    else -> null
                }
            }
            else -> null
        } ?: return null

        val actions = buildList {
            fun extraIntent(key: String): PendingIntent? = try {
                if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(key, PendingIntent::class.java)
                else @Suppress("DEPRECATION") (extras.getParcelable(key) as? PendingIntent)
            } catch (_: Exception) { null }
            val answer = extraIntent("android.answerIntent")
            val decline = extraIntent("android.declineIntent")
            val hangUp = extraIntent("android.hangUpIntent")
            for (a in rawActions) {
                val t = a.title?.toString() ?: ""
                var role = roleOf(t)
                if (a.actionIntent != null) {
                    if (a.actionIntent == answer) role = ActionRole.ANSWER
                    if (a.actionIntent == decline) role = ActionRole.DECLINE
                    if (a.actionIntent == hangUp) role = ActionRole.HANG_UP
                }
                add(LiveAction(t, role, a.actionIntent))
            }
            if (answer != null && none { it.role == ActionRole.ANSWER }) add(LiveAction("Answer", ActionRole.ANSWER, answer))
            if (decline != null && none { it.role == ActionRole.DECLINE }) add(LiveAction("Decline", ActionRole.DECLINE, decline))
            if (hangUp != null && none { it.role == ActionRole.HANG_UP }) add(LiveAction("Hang up", ActionRole.HANG_UP, hangUp))
        }

        val running = showChrono || n.usesChronometerCompat()
        val paused = actions.any { it.role == ActionRole.RESUME } && actions.none { it.role == ActionRole.PAUSE }
        var chronoBase = if (running && n.`when` > 0L && !paused) n.`when` else null
        val now = System.currentTimeMillis()
        val textForTime = listOf(
            title, text,
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "",
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: "",
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "",
            n.tickerText?.toString() ?: ""
        )
        var fixedTime: String? = textForTime.firstNotNullOfOrNull { timeIn(it) }
        // Chronometer but counting the wrong way for its kind: trust the kind.
        val effCountDown = if (kind == LiveKind.TIMER) true else if (kind == LiveKind.STOPWATCH || kind == LiveKind.RECORDER) false else countDown

        if (chronoBase == null && !paused && fixedTime != null &&
            (kind == LiveKind.RECORDER || kind == LiveKind.STOPWATCH || kind == LiveKind.TIMER)
        ) {
            // A time in the text ("00:25"): re-sync a local clock from it so it ticks between updates.
            val ms = parseClockMs(fixedTime)
            if (ms != null) {
                chronoBase = if (effCountDown) now + ms else now - ms
            }
        }
        if (chronoBase == null && fixedTime == null && (kind == LiveKind.RECORDER || kind == LiveKind.STOPWATCH)) {
            // No usable time at all (e.g. Xiaomi Sound Recorder): count locally from when we first saw it.
            synchronized(clocks) {
                val plausibleWhen = n.`when`.takeIf { it in (now - 12 * 3600_000L)..now }
                val c = clocks.getOrPut(sbn.key) { LocalClock(minOf(now, plausibleWhen ?: now)) }
                if (paused) {
                    if (c.pausedAt == null) c.pausedAt = now
                    fixedTime = formatDuration(((c.pausedAt!! - c.startMs).coerceAtLeast(0L)) / 1000L)
                } else {
                    c.pausedAt?.let { c.startMs += now - it }
                    c.pausedAt = null
                    chronoBase = c.startMs
                }
            }
        }
        var totalMs: Long? = null
        if (kind == LiveKind.TIMER) {
            val remaining = if (chronoBase != null) chronoBase - now else fixedTime?.let { parseClockMs(it) }
            val fromExtras = extras.getLong("android.progressMax", 0L).takeIf { it > 1000L }
            synchronized(clocks) {
                if (remaining != null && remaining > 0L) {
                    val prev = timerTotals[sbn.key] ?: 0L
                    if (remaining > prev + 1500L) timerTotals[sbn.key] = remaining // new / restarted timer
                    else if (prev == 0L) timerTotals[sbn.key] = remaining
                }
                totalMs = fromExtras ?: timerTotals[sbn.key]
            }
        }

        var displayTitle = title
        if (kind == LiveKind.CALL || kind == LiveKind.INCOMING_CALL) {
            val person: Person? = try {
                if (Build.VERSION.SDK_INT >= 33) extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
                else @Suppress("DEPRECATION") (extras.getParcelable(Notification.EXTRA_CALL_PERSON) as? Person)
            } catch (_: Exception) { null }
            person?.name?.toString()?.takeIf { it.isNotBlank() }?.let { displayTitle = it }
        }

        val avatar = if (kind == LiveKind.CALL || kind == LiveKind.INCOMING_CALL) {
            avatarCache.getOrPut(sbn.key + "#" + displayTitle) { loadAvatar(context, n, extras) }
        } else null

        return IslandLive(
            id = sbn.key + "#" + kind.name,
            sbnKey = sbn.key,
            kind = kind,
            packageName = pkg,
            title = displayTitle,
            text = text,
            avatar = avatar,
            chronoBaseMs = chronoBase,
            countDown = effCountDown,
            fixedTime = fixedTime,
            paused = paused,
            actions = actions,
            contentIntent = n.contentIntent,
            postTime = sbn.postTime,
            totalMs = totalMs
        )
    }

    private fun Notification.usesChronometerCompat(): Boolean =
        extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true

    private val TIME_RE = Regex("""(?<!\d)(\d{1,2}:\d{2}(?::\d{2})?)(?!\d)""")

    private fun timeIn(s: String): String? {
        // Arabic-Indic digits -> Latin so "٠٤:٥٦" is found too.
        val latin = buildString {
            for (ch in s) append(if (ch in '٠'..'٩') ('0' + (ch - '٠')) else ch)
        }
        return TIME_RE.find(latin)?.groupValues?.get(1)
    }

    private fun roleOf(title: String?): ActionRole {
        val t = (title ?: "").trim().lowercase()
        if (t.isEmpty()) return ActionRole.OTHER
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("answer", "accept", "إجابة", "اجابة", "رد", "قبول") && !has("reply", "message", "رسالة") -> ActionRole.ANSWER
            has("decline", "reject", "dismiss call", "رفض") -> ActionRole.DECLINE
            has("hang up", "hang-up", "end call", "إنهاء", "انهاء", "إغلاق الخط") -> ActionRole.HANG_UP
            has("speaker", "مكبر") -> ActionRole.SPEAKER
            has("mute", "كتم") -> ActionRole.MUTE
            has("pause", "إيقاف مؤقت", "ايقاف مؤقت") -> ActionRole.PAUSE
            has("resume", "continue", "start", "play", "استئناف", "متابعة", "تشغيل", "بدء") -> ActionRole.RESUME
            has("lap", "لفة", "دورة") -> ActionRole.LAP
            has("stop", "cancel", "reset", "delete", "finish", "done", "save", "إيقاف", "ايقاف", "إلغاء", "الغاء", "حفظ", "إعادة") -> ActionRole.STOP
            else -> ActionRole.OTHER
        }
    }

    private fun loadAvatar(context: Context, n: Notification, extras: Bundle): ImageBitmap? = try {
        val person: Person? = if (Build.VERSION.SDK_INT >= 33)
            extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        else @Suppress("DEPRECATION") (extras.getParcelable(Notification.EXTRA_CALL_PERSON) as? Person)
        val icon: Icon? = person?.icon ?: n.getLargeIcon()
        icon?.loadDrawable(context)?.toBitmap(96, 96)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }

    // ---- Inspector -------------------------------------------------------------------------

    /** Human-readable dump of current ongoing / categorised notifications, for debugging detection. */
    fun inspect(): String {
        val l = listener ?: return "Notification listener not connected (grant notification access)."
        val all = try { l.activeNotifications } catch (e: Exception) { return "Could not read: ${e.message}" }
            ?: return "No notifications."
        val sb = StringBuilder()
        var count = 0
        for (sbn in all) {
            val n = sbn.notification ?: continue
            val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
            val fgs = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
            if (!ongoing && !fgs && n.category == null) continue
            val e = n.extras ?: Bundle()
            count++
            sb.append("• ").append(sbn.packageName).append("  id=").append(sbn.id).append('\n')
            sb.append("  category=").append(n.category).append("  channel=").append(n.channelId).append('\n')
            sb.append("  template=").append(e.getString(Notification.EXTRA_TEMPLATE)).append('\n')
            sb.append("  ongoing=").append(ongoing).append(" fgs=").append(fgs)
                .append(" fullScreen=").append(n.fullScreenIntent != null).append('\n')
            sb.append("  showChronometer=").append(e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false))
                .append(" countDown=").append(e.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false))
                .append(" when=").append(n.`when`).append(" (now ").append(System.currentTimeMillis()).append(")\n")
            sb.append("  callType=").append(e.getInt("android.callType", 0)).append('\n')
            sb.append("  title=").append(e.getCharSequence(Notification.EXTRA_TITLE)).append('\n')
            sb.append("  text=").append(e.getCharSequence(Notification.EXTRA_TEXT)).append('\n')
            sb.append("  subText=").append(e.getCharSequence(Notification.EXTRA_SUB_TEXT)).append('\n')
            sb.append("  actions=").append(n.actions?.joinToString { "\"" + it.title + "\"" } ?: "none").append('\n')
            sb.append("  extras=").append(e.keySet().sorted().joinToString()).append('\n')
            val parsed = live[sbn.key]
            sb.append("  → island: ").append(parsed?.kind?.name ?: "ignored").append("\n\n")
        }
        if (count == 0) sb.append("No ongoing notifications right now.")
        return sb.toString()
    }

    /** Opens the app that owns a live activity (its content intent, else its launcher). */
    fun openOwner(context: Context?, item: IslandLive): Boolean {
        if (sendPendingIntent(item.contentIntent)) return true
        val ctx = context ?: listener ?: return false
        return try {
            val launch = ctx.packageManager.getLaunchIntentForPackage(item.packageName) ?: return false
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Launch failed", e)
            false
        }
    }
}

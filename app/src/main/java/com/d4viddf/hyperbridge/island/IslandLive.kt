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
    val postTime: Long
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
        val chronoBase = if (running && n.`when` > 0L && !paused) n.`when` else null

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
            countDown = countDown,
            fixedTime = timeIn("$title $text") ?: timeIn(extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""),
            paused = paused,
            actions = actions,
            contentIntent = n.contentIntent,
            postTime = sbn.postTime
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

package com.d4viddf.hyperbridge.island

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Opt-in (default OFF): while the island is visible, hide every status-bar slot except clock,
 * battery and ONE connectivity icon (Wi-Fi when connected, otherwise mobile) through
 * `settings put secure icon_blacklist`, via Shizuku. The original value is saved before the first
 * change and restored exactly (or deleted if it was unset) when the island hides.
 */
object IslandStatusMinimal {
    private const val PREFS = "island_options"
    private const val KEY = "icon_blacklist"

    /** AOSP slots plus Xiaomi/MIUI slot names seen in HyperOS builds (unknown names are ignored). */
    private val ALWAYS_HIDE = listOf(
        // AOSP
        "bluetooth", "volume", "zen", "alarm_clock", "location", "hotspot", "vpn", "airplane", "nfc",
        "headset", "rotate", "cast", "ethernet", "data_saver", "managed_profile", "speakerphone", "mute",
        "tty", "sensors_off", "screen_record", "microphone", "camera", "call_strength", "connected_display",
        // Xiaomi / MIUI (names not verified on this exact build)
        "wifi_ap", "no_sim", "phone", "sync", "quiet", "volte", "vowifi", "hd", "speech_hd", "slave_wifi",
        "gps", "ble_unlock_mode", "bluetooth_handsfree_battery", "missed_call", "call_record", "record",
        "mobile_hd", "nr", "wifi_calling",
        // Second SIM / extra mobile slots, network speed, VoLTE/HD variants (guessed names, unverified)
        "mobile_2", "mobile2", "mobile_slot2", "slave_mobile", "network_speed", "net_speed", "netspeed",
        "network_speed_view", "volte_2", "hd_2", "vowifi_2", "volte_no_service", "fake_mobile", "carrier"
    )

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    fun load(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("minimalStatus", false)
    }

    fun set(context: Context, value: Boolean) {
        _enabled.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("minimalStatus", value).apply()
    }

    fun list(wifiConnected: Boolean): String =
        (ALWAYS_HIDE + if (wifiConnected) "mobile" else "wifi").joinToString(",")

    fun changedByUs(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ibChanged", false)

    private fun String.firstLine(): String = lineSequence().firstOrNull()?.trim().orEmpty()

    suspend fun apply(context: Context, wifiConnected: Boolean) = withContext(NonCancellable) {
        if (IslandStatusBar.state() != IslandStatusBar.ShizukuState.GRANTED) {
            _status.value = "skipped: ${IslandStatusBar.state().label}"
            return@withContext
        }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val firstWrite = !p.getBoolean("ibChanged", false)
        if (firstWrite) {
            val got = IslandStatusBar.run("settings get secure $KEY")
            if (!got.startsWith("exit=0")) {
                _status.value = "read failed → ${got.firstLine()} (nothing changed)"
                return@withContext
            }
            val prev = got.lineSequence().drop(1).joinToString("\n").trim()
            // Save BEFORE changing so a crash can never leave the status bar stripped.
            p.edit().putBoolean("ibChanged", true).putString("ibPrev", prev).commit()
        }
        val value = list(wifiConnected)
        val put = IslandStatusBar.run("settings put secure $KEY '$value'")
        if (!put.startsWith("exit=0") && firstWrite) {
            // Nothing was written: don't keep a stale "changed" flag.
            p.edit().putBoolean("ibChanged", false).remove("ibPrev").commit()
        }
        _status.value = "minimal (" + (if (wifiConnected) "Wi-Fi kept, mobile hidden" else "mobile kept, Wi-Fi hidden") +
                ") → ${put.firstLine()}  original=\"${p.getString("ibPrev", "?")}\""
    }

    suspend fun restore(context: Context, why: String) = withContext(NonCancellable) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("ibChanged", false)) return@withContext
        if (IslandStatusBar.state() != IslandStatusBar.ShizukuState.GRANTED) {
            _status.value = "RESTORE PENDING ($why): ${IslandStatusBar.state().label} — will retry"
            return@withContext
        }
        val prev = p.getString("ibPrev", null)
        val cmd = if (prev == null || prev == "null" || prev.isBlank()) "settings delete secure $KEY"
        else "settings put secure $KEY '${prev.replace("'", "")}'"
        val out = IslandStatusBar.run(cmd)
        if (out.startsWith("exit=0")) p.edit().putBoolean("ibChanged", false).remove("ibPrev").commit()
        _status.value = "$why → restored (${if (cmd.contains("delete")) "deleted, was unset" else "\"$prev\""}) → ${out.firstLine()}"
    }
}

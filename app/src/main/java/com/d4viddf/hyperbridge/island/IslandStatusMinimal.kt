package com.d4viddf.hyperbridge.island

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Phase 3.2 had an opt-in "minimal status bar" (`settings secure icon_blacklist`), which HyperOS
 * ignores. Phase 4 removed it; only the restore remains, so a value left changed by 3.2 is put back
 * exactly (or deleted if it was unset) once, and the saved flag is cleared.
 */
object IslandStatusMinimal {
    private const val PREFS = "island_options"
    private const val KEY = "icon_blacklist"

    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status.asStateFlow()

    fun changedByUs(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ibChanged", false)

    private fun String.firstLine(): String = lineSequence().firstOrNull()?.trim().orEmpty()

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

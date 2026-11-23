package com.d4viddf.hyperbridge.island

import android.content.pm.PackageManager
import android.util.Log
import com.d4viddf.hyperbridge.BuildConfig
import com.d4viddf.hyperbridge.integration.shizuku.ShizukuUserServiceRecycler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Island test build: checks whether HyperOS 2 SystemUI honours the AOSP status bar
 * disable flags sent from the shell uid (via the app's existing Shizuku UserService).
 */
object IslandStatusBar {
    const val CMD_HIDE_NOTIFICATION_ICONS = "cmd statusbar send-disable-flag notification-icons"
    const val CMD_HIDE_CLOCK_AND_ICONS = "cmd statusbar send-disable-flag clock system-icons notification-icons"
    const val CMD_RESTORE = "cmd statusbar send-disable-flag none"

    private const val TAG = "IslandStatusBar"
    private const val PERMISSION_REQUEST_CODE = 4711

    /** True while we may have left disable flags set; the service restores on destroy. */
    @Volatile
    var modified: Boolean = false
        private set

    @Volatile
    private var suiInitDone = false

    enum class ShizukuState(val label: String) {
        NOT_RUNNING("Shizuku not running (or not installed)"),
        PERMISSION_NEEDED("Shizuku running — permission needed"),
        GRANTED("Shizuku permission granted")
    }

    private fun initSui() {
        if (suiInitDone) return
        suiInitDone = true
        try {
            rikka.sui.Sui.init(BuildConfig.APPLICATION_ID)
        } catch (_: Throwable) {
        }
    }

    fun state(): ShizukuState {
        initSui()
        return try {
            if (!Shizuku.pingBinder()) {
                ShizukuState.NOT_RUNNING
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuState.GRANTED
            } else {
                ShizukuState.PERMISSION_NEEDED
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku state check failed", e)
            ShizukuState.NOT_RUNNING
        }
    }

    fun requestPermission() {
        initSui()
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku permission request failed", e)
        }
    }

    /** Runs [command] as the shell uid. Returns "exit=N" + output, or an error line. */
    suspend fun run(command: String): String = withContext(Dispatchers.IO) {
        if (state() != ShizukuState.GRANTED) return@withContext "error: ${state().label}"
        try {
            val out: String = ShizukuUserServiceRecycler.executeWithService { it.runShellCommand(command) }
                ?: "error: no result"
            if (out.startsWith("exit=0") && command.startsWith("cmd statusbar")) {
                modified = command != CMD_RESTORE
            }
            out
        } catch (e: Throwable) {
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

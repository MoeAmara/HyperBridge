package com.d4viddf.hyperbridge.island

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.ui.theme.HyperBridgeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Test screen: drives the self-drawn island with mock states and lets the user calibrate it. */
class IslandTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IslandController.setCalibration(IslandCalibrationStore.load(this))
        IslandController.setMotion(IslandCalibrationStore.loadMotion(this))
        IslandController.setIconHide(IslandCalibrationStore.loadIconHide(this))
        enableEdgeToEdge()
        setContent {
            HyperBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IslandTestScreen(
                        onCalibrationChange = { c, preview -> applyCalibration(c, preview) },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onOpenAppInfo = { openAppInfo() },
                        onOpenAutostart = { openAutostart() },
                        onOpenNotificationAccess = {
                            startSafely(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    )
                }
            }
        }
    }

    private fun applyCalibration(c: IslandCalibration, preview: IslandMode?) {
        IslandController.setCalibration(c)
        IslandCalibrationStore.save(this, c)
        if (preview != null && IslandController.mode.value != preview) {
            IslandController.show(preview)
        }
    }

    private fun openAccessibilitySettings() {
        startSafely(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openAppInfo() {
        startSafely(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun openAutostart() {
        val miui = Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        )
        if (!startSafely(miui)) openAppInfo()
    }

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun IslandTestScreen(
    onCalibrationChange: (IslandCalibration, IslandMode?) -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAutostart: () -> Unit,
    onOpenNotificationAccess: () -> Unit
) {
    val running by IslandController.serviceRunning.collectAsState()
    val mode by IslandController.mode.collectAsState()
    val geometry by IslandController.geometry.collectAsState()
    val calibration by IslandController.calibration.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(140.dp)) // keep the buttons clear of the island area
        Text("Island Test", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            text = if (running) "Island service: ON" else "Island service: OFF — enable it first",
            color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium
        )
        geometry?.let {
            Text(
                text = "Camera: x=${it.cameraCenterXPx.toInt()} y=${it.cameraCenterYPx.toInt()} " +
                        "d=${it.cameraDiameterPx.toInt()} px · status bar ${it.statusBarHeightPx}px · " +
                        if (it.fromCutout) "from cutout" else "fallback (no cutout found)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text("Current: ${mode.name}", style = MaterialTheme.typography.bodyMedium)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateButton("Compact", Modifier.weight(1f)) { IslandController.show(IslandMode.COMPACT) }
            StateButton("Wide", Modifier.weight(1f)) { IslandController.show(IslandMode.WIDE) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateButton("Expanded", Modifier.weight(1f)) { IslandController.show(IslandMode.EXPANDED) }
            StateButton("Music", Modifier.weight(1f)) { IslandController.show(IslandMode.MUSIC) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateButton("Music pill", Modifier.weight(1f)) { IslandController.show(IslandMode.COMPACT_MUSIC) }
            StateButton("Incoming", Modifier.weight(1f)) { IslandController.show(IslandMode.INCOMING_PILL) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateButton("Timer", Modifier.weight(1f)) { IslandController.show(IslandMode.TIMER_PILL) }
            StateButton("Recorder", Modifier.weight(1f)) { IslandController.show(IslandMode.REC_PILL) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateButton("Two at once (Timer + Music)", Modifier.weight(1f)) {
                IslandController.showTwo(IslandMode.TIMER_PILL, BubbleKind.MUSIC)
            }
            StateButton("Two at once (Call + Recorder)", Modifier.weight(1f)) {
                IslandController.showTwo(IslandMode.COMPACT, BubbleKind.RECORDER)
            }
        }
        OutlinedButton(
            onClick = { IslandController.show(IslandMode.HIDDEN) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Hide") }
        val blur by IslandController.blurState.collectAsState()
        Text(
            "Blur: ${blur.label}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = if (blur.real) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Swipe left/right: a card shrinks to its pill, a pill hides into the camera. " +
                    "After a swipe-hide, swipe left/right on the camera to bring it back. " +
                    "Tap a pill to expand it; touch anywhere else to collapse a card. Incoming pill: tap opens the " +
                    "phone's call screen. Two at once: tap the bubble to swap, long-press opens its app, " +
                    "swipe on the bubble hides only the bubble.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        MotionSection()

        Spacer(Modifier.height(12.dp))
        LockScreenSection()

        Spacer(Modifier.height(12.dp))
        IconHideSection()

        Spacer(Modifier.height(12.dp))
        CallControlSection()

        Spacer(Modifier.height(12.dp))
        MusicSection(onOpenNotificationAccess)

        Spacer(Modifier.height(12.dp))
        LiveSection()

        Spacer(Modifier.height(12.dp))
        CalibrationSection(
            geometry = geometry,
            calibration = calibration,
            currentMode = mode,
            onChange = onCalibrationChange
        )

        Spacer(Modifier.height(12.dp))
        StatusBarTestSection()

        Spacer(Modifier.height(12.dp))
        Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
            Text("1. Accessibility settings")
        }
        OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
            Text("2. App info (restricted settings / battery)")
        }
        OutlinedButton(onClick = onOpenAutostart, modifier = Modifier.fillMaxWidth()) {
            Text("3. Autostart")
        }
    }
}

@Composable
private fun MusicSection(onOpenNotificationAccess: () -> Unit) {
    val access by IslandController.mediaAccess.collectAsState()
    val media by IslandController.media.collectAsState()
    Text("Music (real)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        text = if (access) "Notification access: granted" else "Notification access: NOT granted",
        color = if (access) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium
    )
    if (!access) {
        Text(
            "Needed to read what is playing. Enable HyperBridge (os2test) in the list; " +
                    "the island picks it up within a few seconds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onOpenNotificationAccess, modifier = Modifier.fillMaxWidth()) {
            Text("Grant notification access")
        }
    }
    val m = media
    Text(
        text = if (m == null) "Now playing: nothing"
        else "Now playing: ${m.title}${if (m.artist.isNotEmpty()) " — " + m.artist else ""} " +
                "(${m.packageName}, ${if (m.playing) "playing" else "paused"})",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CalibrationSection(
    geometry: IslandGeometry?,
    calibration: IslandCalibration,
    currentMode: IslandMode,
    onChange: (IslandCalibration, IslandMode?) -> Unit
) {
    val density = LocalDensity.current.density
    Text("Calibrate", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (geometry == null) {
        Text(
            "Turn the island service on to calibrate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Text(
        "Changes apply live and are saved. Expanded and Music cards keep their size; " +
                "only the horizontal offset moves them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Keep showing whichever pill is up; otherwise preview the compact pill.
    val pillPreview = when (currentMode) {
        IslandMode.WIDE -> IslandMode.WIDE
        IslandMode.COMPACT_MUSIC -> IslandMode.COMPACT_MUSIC
        else -> IslandMode.COMPACT
    }
    val c = calibration
    val pillHDp = IslandLayout.pillHeightPx(geometry, c, density) / density
    val compactDp = IslandLayout.compactWidthPx(geometry, c, density) / density
    val wideDp = IslandLayout.wideWidthPx(geometry, c, density) / density
    val radiusDp = if (c.radiusDp >= 0f) c.radiusDp else pillHDp / 2f

    CalRow("Horizontal offset", c.offsetXPx, -80f, 80f, 1f, "px") {
        onChange(c.copy(offsetXPx = it), pillPreview)
    }
    CalRow("Vertical offset", c.offsetYPx, -60f, 60f, 1f, "px") {
        onChange(c.copy(offsetYPx = it), pillPreview)
    }
    CalRow("Pill height", pillHDp, 16f, 64f, 0.5f, "dp") {
        onChange(c.copy(pillHeightDp = it), pillPreview)
    }
    CalRow("Compact width", compactDp, 60f, 300f, 0.5f, "dp") {
        onChange(c.copy(compactWidthDp = it), IslandMode.COMPACT)
    }
    CalRow("Wide width", wideDp, 100f, 380f, 0.5f, "dp") {
        onChange(c.copy(wideWidthDp = it), IslandMode.WIDE)
    }
    CalRow("Pill inner padding", c.pillPadDp, 4f, 24f, 0.5f, "dp") {
        onChange(c.copy(pillPadDp = it), if (currentMode.isCard() || currentMode == IslandMode.HIDDEN) IslandMode.REC_PILL else null)
    }
    val incomingDp = IslandLayout.incomingWidthPx(geometry, c, density) / density
    CalRow("Incoming pill width", incomingDp, 100f, 380f, 0.5f, "dp") {
        onChange(c.copy(incomingWidthDp = it), IslandMode.INCOMING_PILL)
    }
    CalRow(
        label = if (c.radiusDp < 0f) "Corner radius (fully rounded)" else "Corner radius",
        value = radiusDp,
        min = 0f,
        max = 32f,
        step = 0.5f,
        unit = "dp"
    ) {
        onChange(c.copy(radiusDp = it), pillPreview)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { onChange(c.copy(radiusDp = -1f), pillPreview) },
            modifier = Modifier.weight(1f)
        ) { Text("Fully rounded") }
        OutlinedButton(
            onClick = { onChange(IslandCalibration(), pillPreview) },
            modifier = Modifier.weight(1f)
        ) { Text("Reset to auto") }
    }

    // ---- Two activities (bubble) ----
    Spacer(Modifier.height(8.dp))
    Text("Two at once (bubble)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        "Changes preview with \"Two at once (Timer + Music)\" if no bubble is showing.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val bubbleUp = IslandController.bubble.collectAsState().value != null
    fun preview(next: IslandCalibration) {
        onChange(next, null)
        if (!bubbleUp) IslandController.showTwo(IslandMode.TIMER_PILL, BubbleKind.MUSIC)
    }
    val bubbleDp = IslandLayout.bubbleSizePx(geometry, c, density) / density
    CalRow("Bubble size", bubbleDp, 16f, 64f, 0.5f, "dp") { preview(c.copy(bubbleSizeDp = it)) }
    CalRow("Bubble gap", c.bubbleGapDp, 0f, 24f, 0.5f, "dp") { preview(c.copy(bubbleGapDp = it)) }
    CalRow("Group horizontal offset", c.groupOffsetXPx, -120f, 120f, 1f, "px") { preview(c.copy(groupOffsetXPx = it)) }
    CalRow("Counter shift (auto + manual)", c.counterShiftPx, -80f, 80f, 1f, "px") { preview(c.copy(counterShiftPx = it)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Counter right of camera", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = c.counterRight, onCheckedChange = { preview(c.copy(counterRight = it)) })
    }
    OutlinedButton(
        onClick = {
            val d = IslandCalibration()
            preview(
                c.copy(
                    bubbleSizeDp = d.bubbleSizeDp, bubbleGapDp = d.bubbleGapDp, groupOffsetXPx = d.groupOffsetXPx,
                    counterShiftPx = d.counterShiftPx, counterRight = d.counterRight
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Reset bubble") }
}

@Composable
private fun CalRow(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    unit: String,
    onValue: (Float) -> Unit
) {
    fun snap(v: Float): Float = ((v / step).roundToInt() * step).coerceIn(min, max)
    val shown = if (step < 0.1f) "%.2f".format(value) else if (step < 1f) "%.1f".format(value) else "%.0f".format(value)

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("$shown $unit", style = MaterialTheme.typography.labelLarge)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onValue(snap(value - step)) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(44.dp)
            ) { Text("−") }
            Slider(
                value = value.coerceIn(min, max),
                onValueChange = { onValue(snap(it)) },
                valueRange = min..max,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            OutlinedButton(
                onClick = { onValue(snap(value + step)) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(44.dp)
            ) { Text("+") }
        }
    }
}

@Composable
private fun StatusBarTestSection() {
    var shizuku by remember { mutableStateOf(IslandStatusBar.ShizukuState.NOT_RUNNING) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            shizuku = IslandStatusBar.state()
            delay(1500)
        }
    }

    fun runCommand(command: String) {
        if (busy) return
        busy = true
        output = "Running: $command"
        scope.launch {
            val result = IslandStatusBar.run(command)
            output = "$ $command\n$result"
            busy = false
            shizuku = IslandStatusBar.state()
        }
    }

    Text("Status bar test (Shizuku)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Checks whether HyperOS 2 respects the status bar disable flags. Icons are restored " +
                "automatically when the island service stops; a reboot also clears them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        shizuku.label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (shizuku == IslandStatusBar.ShizukuState.GRANTED) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
    )
    if (shizuku == IslandStatusBar.ShizukuState.PERMISSION_NEEDED) {
        OutlinedButton(onClick = { IslandStatusBar.requestPermission() }, modifier = Modifier.fillMaxWidth()) {
            Text("Grant Shizuku permission")
        }
    }
    val enabled = shizuku == IslandStatusBar.ShizukuState.GRANTED && !busy
    Button(
        onClick = { runCommand(IslandStatusBar.CMD_HIDE_NOTIFICATION_ICONS) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Hide notification icons") }
    Button(
        onClick = { runCommand(IslandStatusBar.CMD_HIDE_CLOCK_AND_ICONS) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Hide clock + all icons") }
    OutlinedButton(
        onClick = { runCommand(IslandStatusBar.CMD_RESTORE) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text("Restore") }
    if (output.isNotEmpty()) {
        Text(
            output,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StateButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors()
    ) { Text(label) }
}


@Composable
private fun MotionSection() {
    val context = LocalContext.current
    val m by IslandController.motion.collectAsState()
    fun set(n: IslandMotion) {
        IslandController.setMotion(n)
        IslandCalibrationStore.saveMotion(context, n)
    }
    Text("Animation tuning", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Pill ↔ card: one spring for the whole shape, content grows / melts with it. " +
                "Lower bounce = more wobble, higher speed = faster. Saved and applied live; tap Replay to preview.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = { IslandController.replayMorph() }, modifier = Modifier.fillMaxWidth()) { Text("Replay (pill ↔ card)") }
    CalRow("Bounce (damping)", m.bounce, 0.40f, 1.00f, 0.01f, "") { set(m.copy(bounce = it)) }
    CalRow("Speed (stiffness)", m.speed, 120f, 1200f, 1f, "") { set(m.copy(speed = it)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Jelly squash", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = m.jelly, onCheckedChange = { set(m.copy(jelly = it)) })
    }
    if (m.jelly) {
        CalRow("Jelly amount", m.jellyAmount, 0.00f, 0.12f, 0.01f, "") { set(m.copy(jellyAmount = it)) }
    }
    CalRow("Content blur", m.blurDp, 0f, 24f, 1f, "dp") { set(m.copy(blurDp = it)) }
    OutlinedButton(onClick = { set(IslandMotion()) }, modifier = Modifier.fillMaxWidth()) { Text("Reset to defaults") }
}

@Composable
private fun IconHideSection() {
    val context = LocalContext.current
    val prefs by IslandController.iconHide.collectAsState()
    val status by IslandController.iconHideStatus.collectAsState()
    var shizuku by remember { mutableStateOf(IslandStatusBar.ShizukuState.NOT_RUNNING) }
    LaunchedEffect(Unit) {
        while (true) {
            shizuku = IslandStatusBar.state()
            delay(1500)
        }
    }
    fun set(n: IconHidePrefs) {
        IslandController.setIconHide(n)
        IslandCalibrationStore.saveIconHide(context, n)
    }
    val granted = shizuku == IslandStatusBar.ShizukuState.GRANTED
    Text("Hide status bar icons during", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Optional, off by default. While the island shows a content whose switch is on, the notification icons " +
                "next to the clock are hidden via Shizuku and come back when it ends. The notifications themselves stay. " +
                "Mock buttons above (Compact / Incoming / Timer / Recorder) count too.",
        style = MaterialTheme.typography.bodySmall,
        color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    )
    if (shizuku == IslandStatusBar.ShizukuState.PERMISSION_NEEDED) {
        OutlinedButton(onClick = { IslandStatusBar.requestPermission() }, modifier = Modifier.fillMaxWidth()) {
            Text("Grant Shizuku permission")
        }
    }
    IconSwitchRow("Call", prefs.call, granted) { set(prefs.copy(call = it)) }
    IconSwitchRow("Timer & Stopwatch", prefs.timer, granted) { set(prefs.copy(timer = it)) }
    IconSwitchRow("Recorder", prefs.recorder, granted) { set(prefs.copy(recorder = it)) }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            if (testing) return@OutlinedButton
            testing = true
            scope.launch {
                val hide = IslandStatusBar.run(IslandStatusBar.CMD_HIDE_NOTIFICATION_ICONS)
                IslandController.setIconHideStatus("test hide → " + hide.trim().ifEmpty { "(no output)" })
                delay(3000)
                val restore = IslandStatusBar.run(IslandStatusBar.CMD_RESTORE)
                IslandController.setIconHideStatus(
                    "test hide → " + hide.trim().ifEmpty { "(no output)" } +
                            "\ntest restore → " + restore.trim().ifEmpty { "(no output)" }
                )
                testing = false
            }
        },
        enabled = granted && !testing,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (testing) "Testing… icons should be hidden for 3 s" else "Test now (hide 3 s, then restore)") }
    Text(
        "Shizuku: ${shizuku.label}\nLast command: $status\n" +
                "exit=0 but icons still visible = HyperOS ignores this flag. An error line = Shizuku/binding problem.",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = if (granted && !status.startsWith("error") && !status.contains("→ error"))
            MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
    )
}

@Composable
private fun CallControlSection() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(IslandCallControl.hasPermission(context)) }
    var shizuku by remember { mutableStateOf(IslandStatusBar.ShizukuState.NOT_RUNNING) }
    LaunchedEffect(Unit) {
        while (true) {
            granted = IslandCallControl.hasPermission(context)
            shizuku = IslandStatusBar.state()
            delay(1500)
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val actionStatus by IslandCallControl.actionStatus.collectAsState()

    Text("Buttons & calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        "Card buttons fire the notification's own action. Calls without a usable action use call control " +
                "(answer / end), then Shizuku key events. If the notification has no matching action, " +
                "the app opens quietly instead.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "Call control: " + if (granted) "granted" else "not granted",
        style = MaterialTheme.typography.bodyMedium,
        color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    )
    if (!granted) {
        OutlinedButton(
            onClick = { launcher.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Grant call control") }
    }
    Text(
        "Last button: $actionStatus",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = if (actionStatus.contains("FAILED"))
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "Shizuku: ${shizuku.label}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}


@Composable
private fun IconSwitchRow(label: String, checked: Boolean, granted: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        // Can always be turned OFF; turning ON needs Shizuku.
        Switch(checked = checked, enabled = granted || checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LockScreenSection() {
    val context = LocalContext.current
    val on by IslandController.showOnLock.collectAsState()
    val locked by IslandController.locked.collectAsState()
    Text("Lock screen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Show on lock screen", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = on, onCheckedChange = {
            IslandCalibrationStore.saveShowOnLock(context, it)
            IslandController.setShowOnLock(it)
        })
    }
    Text(
        "Off: the island shrinks into the camera while locked and comes back after unlock. " +
                "On: it stays on the lock screen (tap / controls work; long-press open is ignored while locked). " +
                "Keyguard now: ${if (locked) "locked" else "unlocked"}. Always-on display is not covered.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LiveSection() {
    val context = LocalContext.current
    val live by IslandController.live.collectAsState()
    var dump by remember { mutableStateOf("") }
    Text("Calls · timers · recorder (real)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        if (live.isEmpty()) "Detected now: nothing"
        else "Detected now:\n" + live.joinToString("\n") {
            "• ${it.kind.name} — ${it.title.ifBlank { it.packageName }} (${it.packageName})" +
                    (it.timeText()?.let { t -> " $t" } ?: "") + if (it.paused) " paused" else ""
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text("Notification inspector", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { dump = IslandNotifications.inspect() }, modifier = Modifier.weight(1f)) {
            Text("Refresh")
        }
        OutlinedButton(
            onClick = {
                if (dump.isEmpty()) dump = IslandNotifications.inspect()
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("Island inspector", dump))
            },
            modifier = Modifier.weight(1f)
        ) { Text("Copy") }
    }
    if (dump.isNotEmpty()) {
        Text(dump, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

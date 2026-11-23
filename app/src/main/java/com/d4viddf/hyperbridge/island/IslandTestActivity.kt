package com.d4viddf.hyperbridge.island

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
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
        IslandController.setLook(IslandCalibrationStore.loadLook(this))
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
    var showLayout by remember { mutableStateOf(false) }

    if (showLayout) {
        LayoutScreen(geometry, calibration, onCalibrationChange) { showLayout = false }
        return
    }

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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateButton("Stopwatch", Modifier.weight(1f)) { IslandController.show(IslandMode.TIMER_PILL, stopwatch = true) }
            StateButton("Two at once (Stopwatch + Call)", Modifier.weight(1f)) {
                IslandController.showTwo(IslandMode.TIMER_PILL, BubbleKind.CALL, stopwatch = true)
            }
        }
        OutlinedButton(
            onClick = { IslandController.show(IslandMode.HIDDEN) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Hide") }
        Button(onClick = { showLayout = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Layout — sizes per type, bubble side, export / import")
        }
        Text(
            "Swipe left/right: a card shrinks to its pill, a pill hides into the camera. " +
                    "After a swipe-hide, swipe left/right on the camera to bring it back. " +
                    "Tap a pill to expand it; touch anywhere else to collapse a card. Incoming pill: tap opens the " +
                    "phone's call screen. Two at once: tap the bubble to swap (with a card open, the bubble opens " +
                    "into its own card and the card shrinks into the bubble), long-press opens its app, " +
                    "swipe on the bubble hides only the bubble.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))
        CardLookSection()

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
        "Global values, applied live and saved. Each content type can override them in Layout " +
                "(button above) — incoming pill width, card sizes, bubble size / gap / side live there now.",
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
            onClick = { onChange(IslandCalibration(pills = c.pills, cards = c.cards, bubbleRight = c.bubbleRight), pillPreview) },
            modifier = Modifier.weight(1f)
        ) { Text("Reset to auto") }
    }

    // ---- Two activities (bubble) ----
    Spacer(Modifier.height(8.dp))
    Text("Two at once (counter)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                    counterShiftPx = d.counterShiftPx, counterRight = d.counterRight
                )
            )
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Reset counter") }
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
                "Mock buttons above (Compact / Incoming / Timer / Recorder / Two at once) count too. " +
                "\"Two activities at once\" hides them whenever a second content shows as the bubble, any type.",
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
    IconSwitchRow("Two activities at once", prefs.multi, granted) { set(prefs.copy(multi = it)) }
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

// ---- Layout screen (Phase 4.1): sizes per content type ----------------------------------------

/** One editable group on the Layout screen and the mock it previews on the real island. */
private sealed class LayoutSection(val key: String, val title: String) {
    class Pill(val type: PillType) : LayoutSection("pill." + type.name, "Small pill — " + type.label)
    class Card(val type: CardType) : LayoutSection("card." + type.name, "Expanded card — " + type.label)
    object Bubble : LayoutSection("bubble", "Bubble (two at once)")
}

private val LAYOUT_SECTIONS: List<LayoutSection> =
    PillType.entries.map { LayoutSection.Pill(it) } + LayoutSection.Bubble + CardType.entries.map { LayoutSection.Card(it) }

private fun pillPreviewMode(t: PillType): IslandMode = when (t) {
    PillType.MUSIC -> IslandMode.COMPACT_MUSIC
    PillType.CALL -> IslandMode.COMPACT
    PillType.TIMER, PillType.STOPWATCH -> IslandMode.TIMER_PILL
    PillType.RECORDER -> IslandMode.REC_PILL
    PillType.INCOMING -> IslandMode.INCOMING_PILL
}

private fun cardPreviewMode(t: CardType): IslandMode = when (t) {
    CardType.MUSIC -> IslandMode.MUSIC
    CardType.CALL -> IslandMode.EXPANDED
    CardType.TIMER, CardType.STOPWATCH -> IslandMode.TIMER_CARD
    CardType.RECORDER -> IslandMode.REC_CARD
}

/** Shows the mock of [s] on the real island (only when it isn't already up, so edits don't replay it). */
private fun previewSection(s: LayoutSection) {
    when (s) {
        is LayoutSection.Pill -> {
            val m = pillPreviewMode(s.type)
            val sw = s.type == PillType.STOPWATCH
            if (IslandController.mode.value != m || IslandController.mockStopwatch.value != sw ||
                IslandController.bubble.value != null
            ) IslandController.show(m, sw)
        }
        is LayoutSection.Card -> {
            val m = cardPreviewMode(s.type)
            val sw = s.type == CardType.STOPWATCH
            if (IslandController.mode.value != m || IslandController.mockStopwatch.value != sw ||
                IslandController.bubble.value != null
            ) IslandController.show(m, sw)
        }
        LayoutSection.Bubble -> {
            if (IslandController.bubble.value == null || IslandController.mode.value != IslandMode.TIMER_PILL) {
                IslandController.showTwo(IslandMode.TIMER_PILL, BubbleKind.MUSIC)
            }
        }
    }
}

@Composable
private fun LayoutScreen(
    geometry: IslandGeometry?,
    calibration: IslandCalibration,
    onCalibrationChange: (IslandCalibration, IslandMode?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    var focused by remember { mutableStateOf<String?>(null) }
    var importStatus by remember { mutableStateOf("") }
    BackHandler { onBack() }
    DisposableEffect(Unit) {
        IslandController.setLayoutPreview(true)
        onDispose {
            IslandController.setLayoutPreview(false)
            IslandController.show(IslandMode.HIDDEN)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Spacer(Modifier.height(140.dp)) // keep the controls clear of the island (and of tall cards)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.size(12.dp))
            Text("Layout", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "Sizes per content type. Open a section: the real island shows that content on the camera while " +
                    "you edit, and hides when you close it or leave this screen. Everything is saved and applied " +
                    "live. If a time gets longer than the width you set (e.g. 1:00:00), the pill widens just " +
                    "enough and keeps its padding.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (geometry == null) {
            Text(
                "Turn the island service on first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }

        for (s in LAYOUT_SECTIONS) {
            val open = focused == s.key
            Surface(
                tonalElevation = if (open) 3.dp else 1.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(onClick = {
                            if (open) {
                                focused = null
                                IslandController.show(IslandMode.HIDDEN)
                            } else {
                                focused = s.key
                                previewSection(s)
                            }
                        }) { Text(if (open) "Close" else "Edit") }
                    }
                    if (open) {
                        fun change(next: IslandCalibration) {
                            onCalibrationChange(next, null)
                            previewSection(s)
                        }
                        when (s) {
                            is LayoutSection.Pill -> PillEditor(s.type, geometry, calibration, density, ::change)
                            is LayoutSection.Card -> CardEditor(s.type, geometry, calibration, density, ::change)
                            LayoutSection.Bubble -> BubbleEditor(geometry, calibration, density, ::change)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("Export / Import", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Copies every layout, calibration, animation and card-look value as JSON to the clipboard; Import " +
                    "reads it back from the clipboard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val json = IslandCalibrationStore.exportJson(
                    IslandController.calibration.value, IslandController.motion.value, IslandController.look.value
                )
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("HyperBridge island layout", json))
                importStatus = "Copied (${json.length} characters)."
            }, modifier = Modifier.weight(1f)) { Text("Export (copy)") }
            OutlinedButton(onClick = {
                val cm = context.getSystemService(ClipboardManager::class.java)
                val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                importStatus = if (text.isNullOrBlank()) "Clipboard is empty." else try {
                    val (c, m, l) = IslandCalibrationStore.importJson(
                        text,
                        Triple(IslandController.calibration.value, IslandController.motion.value, IslandController.look.value)
                    )
                    onCalibrationChange(c, null)
                    IslandController.setMotion(m)
                    IslandCalibrationStore.saveMotion(context, m)
                    IslandController.setLook(l)
                    IslandCalibrationStore.saveLook(context, l)
                    "Imported."
                } catch (e: Exception) {
                    "Import failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }, modifier = Modifier.weight(1f)) { Text("Import (paste)") }
        }
        if (importStatus.isNotEmpty()) {
            Text(importStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(240.dp)) // so the last section can scroll above tall preview cards
    }
}

@Composable
private fun PillEditor(
    t: PillType,
    g: IslandGeometry,
    c: IslandCalibration,
    density: Float,
    onChange: (IslandCalibration) -> Unit
) {
    val res = IslandLayout.pillResolved(t, g, c, density)
    val cur = c.pill(t)
    fun set(d: PillDims) = onChange(c.copy(pills = c.pills + (t to d)))
    val screenDp = g.screenWidthPx / density
    val wDp = res.widthPx / density
    val hDp = res.heightPx / density
    val fullyRound = (if (cur.radiusDp > -1.5f) cur.radiusDp else c.radiusDp) < 0f
    CalRow("Width", wDp, 40f, (screenDp * 0.9f).coerceAtLeast(120f), 0.5f, "dp") { set(cur.copy(widthDp = it)) }
    CalRow("Height", hDp, 16f, 64f, 0.5f, "dp") { set(cur.copy(heightDp = it)) }
    CalRow(
        if (fullyRound) "Corner radius (fully rounded)" else "Corner radius",
        res.radiusPx / density, 0f, 32f, 0.5f, "dp"
    ) { set(cur.copy(radiusDp = it)) }
    CalRow("Side padding", res.padPx / density, 0f, 30f, 0.5f, "dp") { set(cur.copy(padDp = it)) }
    val iconLabel = when (t) {
        PillType.MUSIC -> "Album art size"
        PillType.RECORDER -> "Waveform height"
        else -> "Icon size"
    }
    CalRow(iconLabel, res.iconDp, 8f, 40f, 0.5f, "dp") { set(cur.copy(iconDp = it)) }
    if (t == PillType.MUSIC) {
        CalRow("Sound-wave height", res.textSp, 6f, 30f, 0.5f, "dp") { set(cur.copy(textSp = it)) }
    } else {
        CalRow(if (t == PillType.INCOMING) "Text size" else "Number size", res.textSp, 8f, 26f, 0.5f, "sp") {
            set(cur.copy(textSp = it))
        }
    }
    CalRow("Horizontal offset", res.offsetXPx, -120f, 120f, 1f, "px") { set(cur.copy(offsetXPx = it)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { set(cur.copy(radiusDp = -1f)) }, modifier = Modifier.weight(1f)) {
            Text("Fully rounded")
        }
        OutlinedButton(onClick = { onChange(c.copy(pills = c.pills - t)) }, modifier = Modifier.weight(1f)) {
            Text("Reset")
        }
    }
    if (t != PillType.INCOMING) {
        OutlinedButton(
            onClick = {
                // Copies this pill's shape (width, height, corners, padding, offset) to the other small pills;
                // each keeps its own icon / number size.
                val r = IslandLayout.pillResolved(t, g, c, density)
                val radius = if (fullyRound) -1f else r.radiusPx / density
                val next = c.pills.toMutableMap()
                for (o in PillType.entries) {
                    if (o == PillType.INCOMING) continue
                    val od = next[o] ?: PillDims()
                    next[o] = od.copy(
                        widthDp = r.widthPx / density,
                        heightDp = r.heightPx / density,
                        radiusDp = radius,
                        padDp = r.padPx / density,
                        offsetXPx = r.offsetXPx
                    )
                }
                onChange(c.copy(pills = next))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Copy to all small pills (shape only)") }
    }
}

@Composable
private fun CardEditor(
    t: CardType,
    g: IslandGeometry,
    c: IslandCalibration,
    density: Float,
    onChange: (IslandCalibration) -> Unit
) {
    val rect = IslandLayout.cardRect(t, g, c, density)
    val cur = c.card(t)
    fun set(d: CardDims) = onChange(c.copy(cards = c.cards + (t to d)))
    val screenDp = g.screenWidthPx / density
    CalRow("Width", rect.w / density, 120f, screenDp, 0.5f, "dp") { set(cur.copy(widthDp = it)) }
    CalRow("Height", rect.h / density, 40f, screenDp * IslandLayout.MAX_CARD_HEIGHT_RATIO, 0.5f, "dp") {
        set(cur.copy(heightDp = it))
    }
    CalRow("Corner radius", rect.r / density, 0f, 60f, 0.5f, "dp") { set(cur.copy(radiusDp = it)) }
    OutlinedButton(onClick = { onChange(c.copy(cards = c.cards - t)) }, modifier = Modifier.fillMaxWidth()) {
        Text("Reset")
    }
}

@Composable
private fun BubbleEditor(
    g: IslandGeometry,
    c: IslandCalibration,
    density: Float,
    onChange: (IslandCalibration) -> Unit
) {
    val bubbleDp = IslandLayout.bubbleSizePx(g, c, density) / density
    CalRow("Size", bubbleDp, 16f, 64f, 0.5f, "dp") { onChange(c.copy(bubbleSizeDp = it)) }
    CalRow("Gap", c.bubbleGapDp, 0f, 24f, 0.5f, "dp") { onChange(c.copy(bubbleGapDp = it)) }
    CalRow("Group horizontal offset", c.groupOffsetXPx, -120f, 120f, 1f, "px") { onChange(c.copy(groupOffsetXPx = it)) }
    Text("Bubble side", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!c.bubbleRight) {
            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Left ✓") }
            OutlinedButton(onClick = { onChange(c.copy(bubbleRight = true)) }, modifier = Modifier.weight(1f)) { Text("Right") }
        } else {
            OutlinedButton(onClick = { onChange(c.copy(bubbleRight = false)) }, modifier = Modifier.weight(1f)) { Text("Left") }
            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Right ✓") }
        }
    }
    Text(
        "\"Counter right of camera\" (Calibrate) still wins: with a call / timer / recorder pill the group may " +
                "not be centred.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedButton(
        onClick = {
            val d = IslandCalibration()
            onChange(c.copy(bubbleSizeDp = d.bubbleSizeDp, bubbleGapDp = d.bubbleGapDp, groupOffsetXPx = d.groupOffsetXPx, bubbleRight = d.bubbleRight))
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Reset") }
}

// ---- Card look (Phase 4.1): blur from the first frame -----------------------------------------

@Composable
private fun CardLookSection() {
    val context = LocalContext.current
    val l by IslandController.look.collectAsState()
    val blur by IslandController.blurState.collectAsState()
    fun set(n: IslandLook) {
        IslandController.setLook(n)
        IslandCalibrationStore.saveLook(context, n)
    }
    Text("Card background", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    val mode = when {
        l.solid -> "solid (plain black, no blur)"
        blur.real -> "real (" + (if (l.blurFollowView) "follows the card every frame" else "window resize fallback") + ")"
        else -> "fallback (dark translucent — " + blur.label.removePrefix("fallback — ") + ")"
    }
    Text(
        "Blur: $mode",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        color = if (blur.real && !l.solid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "The blur starts with the card on the first frame and follows its shape on the same spring. " +
                "Tap Expanded / Music above (or Replay) to check.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Solid black", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = l.solid, onCheckedChange = { set(l.copy(solid = it)) })
    }
    if (!l.solid) {
        CalRow("Blur radius", l.blurDp, 0f, 60f, 1f, "dp") { set(l.copy(blurDp = it)) }
        CalRow("Black tint over blur", l.tint * 100f, 0f, 100f, 1f, "%") { set(l.copy(tint = it / 100f)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Blur follow: per frame (smooth)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Off = resize the blur window each frame (steps, but a safe fallback if the smooth one " +
                            "misbehaves on this phone).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = l.blurFollowView, onCheckedChange = { set(l.copy(blurFollowView = it)) })
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Bubble glow", style = MaterialTheme.typography.bodyMedium)
            Text(
                "While a card is open, the other activity's bubble glows softly in its colour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = l.bubbleGlow, onCheckedChange = { set(l.copy(bubbleGlow = it)) })
    }
    OutlinedButton(onClick = { set(IslandLook()) }, modifier = Modifier.fillMaxWidth()) { Text("Reset card background") }
}

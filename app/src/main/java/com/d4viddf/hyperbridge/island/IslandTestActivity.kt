package com.d4viddf.hyperbridge.island

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        enableEdgeToEdge()
        setContent {
            HyperBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IslandTestScreen(
                        onCalibrationChange = { c, preview -> applyCalibration(c, preview) },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onOpenAppInfo = { openAppInfo() },
                        onOpenAutostart = { openAutostart() }
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
    onOpenAutostart: () -> Unit
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
        OutlinedButton(
            onClick = { IslandController.show(IslandMode.HIDDEN) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Hide") }
        Text(
            "Tip: swipe the island left or right to dismiss it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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
    val pillPreview = if (currentMode == IslandMode.WIDE) IslandMode.WIDE else IslandMode.COMPACT
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
    val shown = if (step < 1f) "%.1f".format(value) else "%.0f".format(value)

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

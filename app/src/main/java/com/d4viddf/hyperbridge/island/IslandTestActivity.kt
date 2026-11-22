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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.ui.theme.HyperBridgeTheme

/** Phase 1 test screen: drives the self-drawn island with mock states. */
class IslandTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    IslandTestScreen(
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onOpenAppInfo = { openAppInfo() },
                        onOpenAutostart = { openAutostart() }
                    )
                }
            }
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
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenAutostart: () -> Unit
) {
    val running by IslandController.serviceRunning.collectAsState()
    val mode by IslandController.mode.collectAsState()
    val geometry by IslandController.geometry.collectAsState()

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
private fun StateButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors()
    ) { Text(label) }
}

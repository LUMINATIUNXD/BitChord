package com.music.bitchord.ui.player

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.device.DeviceFamily
import com.music.bitchord.device.DeviceRegistry
import com.music.bitchord.device.GalaxyBudsManager
import com.music.bitchord.playback.AudioOutputStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
private fun findBuds(context: Context, family: DeviceFamily): BluetoothDevice? {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
    return adapter.bondedDevices.firstOrNull { DeviceRegistry.identify(it.name)?.family == family }
}

private val PanelShape = RoundedCornerShape(16.dp)

@Composable
private fun PanelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = if (selected) 0.95f else 0.75f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(Color.White.copy(alpha = if (selected) 0.10f else 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    )
}

/** Controles de los Buds dentro de la hoja de salida. Solo Buds2 / Buds3 Pro. */
@Composable
fun BudsOutputPanel() {
    val status by AudioOutputStatus.current.collectAsStateWithLifecycle()
    val profile = status.bluetoothTelemetry?.deviceProfile ?: return
    if (profile.family != DeviceFamily.GALAXY_BUDS2 && profile.family != DeviceFamily.GALAXY_BUDS3_PRO) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var noise by remember { mutableStateOf<Byte?>(null) }
    var preset by remember { mutableStateOf<Int?>(null) } // -1 = apagado
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(() -> Boolean)?>(null) }

    fun run(action: () -> Boolean, onOk: () -> Unit) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!GalaxyBudsManager.isConnectedToHardware) {
                    val d = findBuds(context, profile.family) ?: return@withContext false
                    if (!GalaxyBudsManager.connectToHardware(d)) return@withContext false
                }
                action()
            }
            if (ok) { onOk(); message = null }
            else message = "No se pudo conectar a los Buds. Cierra Galaxy Wearable e intenta de nuevo."
        }
    }

    var queued by remember { mutableStateOf<Pair<() -> Boolean, () -> Unit>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val q = queued
        if (granted && q != null) run(q.first, q.second) else if (!granted) message = "Falta el permiso de Bluetooth."
    }
    fun request(action: () -> Boolean, onOk: () -> Unit) {
        val allowed = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (allowed) run(action, onOk) else { queued = action to onOk; launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }
    }

    Spacer(Modifier.height(10.dp))
    Text(
        text = profile.displayName,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        fun mode(label: String, m: Byte) = request({ GalaxyBudsManager.setNoiseControlMode(m) }, { noise = m })
        PanelRow("Apagado", noise == GalaxyBudsManager.NoiseControl.OFF) { mode("", GalaxyBudsManager.NoiseControl.OFF) }
        PanelRow("Cancelación de ruido (ANC)", noise == GalaxyBudsManager.NoiseControl.ANC) { mode("", GalaxyBudsManager.NoiseControl.ANC) }
        PanelRow("Sonido ambiente", noise == GalaxyBudsManager.NoiseControl.AMBIENT) { mode("", GalaxyBudsManager.NoiseControl.AMBIENT) }
        if (profile.family == DeviceFamily.GALAXY_BUDS3_PRO) {
            PanelRow("Adaptativo (sin verificar)", noise == GalaxyBudsManager.NoiseControl.ADAPTIVE) { mode("", GalaxyBudsManager.NoiseControl.ADAPTIVE) }
        }

        Spacer(Modifier.height(4.dp))
        val presets = listOf(
            "Bass Boost" to GalaxyBudsManager.EqPreset.BASS_BOOST,
            "Soft" to GalaxyBudsManager.EqPreset.SOFT,
            "Dynamic" to GalaxyBudsManager.EqPreset.DYNAMIC,
            "Clear" to GalaxyBudsManager.EqPreset.CLEAR,
            "Treble Boost" to GalaxyBudsManager.EqPreset.TREBLE_BOOST,
        )
        PanelRow("Ecualizador de los Buds: apagado", preset == -1) {
            request({ GalaxyBudsManager.setEqualizerPreset(false, 0) }, { preset = -1 })
        }
        presets.forEach { (name, id) ->
            PanelRow(name, preset == id) { request({ GalaxyBudsManager.setEqualizerPreset(true, id) }, { preset = id }) }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(4.dp))
        }
    }
}
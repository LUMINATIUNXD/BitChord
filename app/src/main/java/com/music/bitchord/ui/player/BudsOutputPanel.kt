package com.music.bitchord.ui.player

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.device.DeviceRegistry
import com.music.bitchord.device.GalaxyBudsManager
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional controls inside the existing output drawer. No connection until requested. */
@SuppressLint("MissingPermission")
@Composable
internal fun BudsOutputPanel() {
    val status by AudioOutputStatus.current.collectAsStateWithLifecycle()
    if (status.routeKind != AudioRouting.Kind.BLUETOOTH) return
    val name = status.deviceName
    val profile = DeviceRegistry.identify(name) ?: return
    if (!profile.family.isGalaxyBuds) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val family = profile.family
    val session = remember(name) { GalaxyBudsManager.Session() }
    var expanded by remember(name) { mutableStateOf(false) }
    var busy by remember(name) { mutableStateOf(false) }
    var error by remember(name) { mutableStateOf<Int?>(null) }
    val connected by GalaxyBudsManager.connected.collectAsStateWithLifecycle()
    val noise by GalaxyBudsManager.noiseStatus.collectAsStateWithLifecycle()
    val confirmed = noise?.takeIf { it.family == family }?.mode

    // Closing the drawer releases this app's control socket for other managers.
    DisposableEffect(name) {
        onDispose { GalaxyBudsManager.close(session) }
    }

    fun run(action: () -> Boolean) {
        scope.launch {
            busy = true
            error = null
            try {
                val ok = withContext(Dispatchers.IO) {
                    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                        ?: return@withContext false
                    // Never choose an arbitrary same-model pair when the name is ambiguous.
                    val device = adapter.bondedDevices.filter { it.name == name }.singleOrNull()
                        ?: return@withContext false
                    GalaxyBudsManager.runConnected(device, family, session, action)
                }
                if (!ok) error = R.string.buds_failed
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = R.string.buds_failed
            } finally {
                busy = false
            }
        }
    }

    var pending by remember(name) { mutableStateOf<(() -> Boolean)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted && action != null) run(action)
        else if (!granted) error = R.string.buds_permission
    }
    fun request(action: () -> Boolean) {
        if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        ) run(action)
        else {
            pending = action
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        TextButton(onClick = { expanded = !expanded }, enabled = !busy) {
            Text(stringResource(R.string.buds_controls) + " · " + profile.displayName)
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { request { true } }, enabled = !busy) {
                    Text(stringResource(if (busy) R.string.buds_connecting else R.string.buds_connect))
                }
                if (connected) {
                    Text(stringResource(R.string.buds_connected), style = MaterialTheme.typography.bodySmall)
                    if (confirmed == null) {
                        Text(stringResource(R.string.buds_noise_unknown), style = MaterialTheme.typography.bodySmall)
                    }
                }
                val modes = buildList {
                    add(R.string.buds_off to GalaxyBudsManager.NoiseControl.OFF)
                    if (family.supportsAnc) add(R.string.buds_anc to GalaxyBudsManager.NoiseControl.ANC)
                    if (family.supportsAmbientMode) add(R.string.buds_ambient to GalaxyBudsManager.NoiseControl.AMBIENT)
                    if (family.supportsAdaptiveMode) add(R.string.buds_adaptive to GalaxyBudsManager.NoiseControl.ADAPTIVE)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { (label, mode) ->
                        FilterChip(
                            selected = confirmed == mode,
                            enabled = !busy,
                            onClick = { request { GalaxyBudsManager.setNoiseControlMode(mode, family) } },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                Text(stringResource(R.string.buds_equalizer), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val presets = listOf(
                        R.string.buds_eq_normal, R.string.buds_eq_bass, R.string.buds_eq_soft,
                        R.string.buds_eq_dynamic, R.string.buds_eq_clear, R.string.buds_eq_treble,
                    )
                    presets.forEachIndexed { index, label ->
                        // A write is not an acknowledgement; do not invent a confirmed EQ state.
                        TextButton(enabled = !busy, onClick = {
                            request { GalaxyBudsManager.setEqualizerPreset(index != 0, (index - 1).coerceAtLeast(0), family) }
                        }) { Text(stringResource(label)) }
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

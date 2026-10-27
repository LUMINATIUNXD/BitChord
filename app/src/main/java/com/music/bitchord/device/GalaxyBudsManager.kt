package com.music.bitchord.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Comandos SPP de ruido y ecualizador para la familia Galaxy Buds.
 * Protocolo tomado de GalaxyBudsClient (timschneeb, GPLv3):
 * SppMessage.Encode, Crc16.cs, MsgIds, NoiseControlModes, SetEqualizerEncoder.
 */
object GalaxyBudsManager {
    private const val TAG = "GalaxyBudsManager"

    private val SPP_LEGACY_UUID = UUID.fromString("00001102-0000-1000-8000-00805f9b34fd")
    private val SPP_STANDARD_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val SPP_MODERN_UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")

    private const val SOM = 0xFD
    private const val EOM = 0xDD
    private const val LEGACY_SOM = 0xFE
    private const val LEGACY_EOM = 0xEE
    private const val CONNECT_TIMEOUT_MS = 4_000L
    private const val MSG_NOISE_CONTROLS = 120
    private const val MSG_EQUALIZER = 134
    private const val MSG_SET_AMBIENT_MODE = 128
    private const val MSG_SET_NOISE_REDUCTION = 152
    private const val MSG_EXTENDED_STATUS_UPDATED = 97

    data class NoiseStatus(
        val family: DeviceFamily,
        val mode: Byte,
        val receivedAtMillis: Long,
    )

    private val _noiseStatus = MutableStateFlow<NoiseStatus?>(null)
    val noiseStatus: StateFlow<NoiseStatus?> = _noiseStatus.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** A drawer owns its socket; disposing it can cancel an in-flight connect. */
    class Session {
        internal val closed = AtomicBoolean(false)
    }
    private val stateLock = Any()
    private var ownerSession: Session? = null
    private var connectingSocket: BluetoothSocket? = null

    fun runConnected(device: BluetoothDevice, family: DeviceFamily, session: Session, action: () -> Boolean): Boolean =
        synchronized(connectionLock) {
            if (session.closed.get()) return@synchronized false
            synchronized(stateLock) {
                if (ownerSession !== session) disconnect()
                ownerSession = session
            }
            connectToHardware(device, family) && !session.closed.get() && action()
        }

    fun close(session: Session) {
        session.closed.set(true)
        synchronized(stateLock) {
            if (ownerSession === session) {
                try { connectingSocket?.close() } catch (_: Exception) {}
                connectingSocket = null
                disconnect()
                ownerSession = null
            }
        }
    }

    object NoiseControl {
        const val OFF: Byte = 0
        const val ANC: Byte = 1
        const val AMBIENT: Byte = 2
        /** Solo Buds3 y posteriores. */
        const val ADAPTIVE: Byte = 3
    }

    object EqPreset {
        const val BASS_BOOST = 0
        const val SOFT = 1
        const val DYNAMIC = 2
        const val CLEAR = 3
        const val TREBLE_BOOST = 4
    }

    @Volatile
    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private var lastDevice: BluetoothDevice? = null
    private var lastFamily: DeviceFamily? = null
    private val writeLock = Any()
    private val connectionLock = Any()

    val isConnectedToHardware: Boolean
        get() = _connected.value

    @SuppressLint("MissingPermission")
    fun isConnectedTo(device: BluetoothDevice, family: DeviceFamily): Boolean =
        isConnectedToHardware && lastDevice?.address == device.address && lastFamily == family

    /** Bloqueante: llamar desde un hilo de fondo, no desde el principal. */
    @SuppressLint("MissingPermission")
    private fun connectToHardware(device: BluetoothDevice, family: DeviceFamily): Boolean =
        synchronized(connectionLock) connect@ {
            if (isConnectedTo(device, family)) return@connect true
            val serviceUuid = serviceUuid(family) ?: return@connect false
            _noiseStatus.value = null
            lastDevice = device
            lastFamily = family
            disconnect()
            repeat(2) { attempt ->
                val connection = try {
                    device.createRfcommSocketToServiceRecord(serviceUuid)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not create SPP socket: ${e.message}")
                    if (attempt == 0) Thread.sleep(300)
                    return@repeat
                }
                val connecting = AtomicBoolean(true)
                synchronized(stateLock) {
                    if (ownerSession?.closed?.get() != false) {
                        connection.close()
                        return@connect false
                    }
                    connectingSocket = connection
                }
                val timeoutMonitor = Thread({
                    try {
                        Thread.sleep(CONNECT_TIMEOUT_MS)
                        if (connecting.compareAndSet(true, false)) {
                            Log.w(TAG, "SPP connection timed out")
                            connection.close()
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not close timed-out SPP socket: ${e.message}")
                    }
                }, "BudsSppConnectTimeout").apply { isDaemon = true; start() }
                try {
                    connection.connect()
                    if (!connecting.compareAndSet(true, false)) {
                        connection.close()
                        return@connect false
                    }
                    timeoutMonitor.interrupt()
                    synchronized(stateLock) {
                        if (ownerSession?.closed?.get() != false) {
                            connection.close()
                            return@connect false
                        }
                        connectingSocket = null
                        socket = connection
                        outStream = connection.outputStream
                        _connected.value = true
                    }
                    startDrain(connection, connection.inputStream, family)
                    Log.d(TAG, "SPP connected (attempt ${attempt + 1})")
                    return@connect true
                } catch (e: Exception) {
                    connecting.set(false)
                    timeoutMonitor.interrupt()
                    Log.w(TAG, "SPP connection failed (attempt ${attempt + 1}): ${e.message}")
                    try {
                        connection.close()
                    } catch (closeError: Exception) {
                        Log.w(TAG, "Could not close failed SPP socket: ${closeError.message}")
                    }
                    _connected.value = false
                    if (ownerSession?.closed?.get() != false) return@connect false
                    if (attempt == 0) Thread.sleep(300)
                }
            }
            false
        }

    // Los Buds mandan actualizaciones de estado; hay que leerlas para que no se llene el buffer.
    private fun startDrain(owner: BluetoothSocket, input: InputStream, family: DeviceFamily) {
        Thread {
            val buf = ByteArray(1024)
            val decoder = BudsSppFrameDecoder()
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    decoder.append(buf, n).forEach { frame ->
                        synchronized(stateLock) { if (socket === owner) {
                            decodeNoiseStatus(frame, family)?.let { _noiseStatus.value = it }
                        } }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SPP status stream ended: ${e.message}")
            }
            // Solo si el socket sigue siendo el de este hilo.
            synchronized(stateLock) { if (socket === owner) disconnect() }
        }.apply { isDaemon = true; name = "BudsSppReader" }.start()
    }

    internal fun decodeNoiseStatus(frame: ByteArray, family: DeviceFamily): NoiseStatus? {
        if (frame.size < 7) return null
        val payloadSize = frame.size - 7
        val mode = when (frame[3].toInt() and 0xFF) {
            MSG_EXTENDED_STATUS_UPDATED -> when (family) {
                DeviceFamily.GALAXY_BUDS -> {
                    if (payloadSize <= 7) return null
                    if (frame[11].toInt() == 0) NoiseControl.OFF else NoiseControl.AMBIENT
                }
                DeviceFamily.GALAXY_BUDS_PLUS -> {
                    if (payloadSize <= 8) return null
                    if (frame[12].toInt() == 0) NoiseControl.OFF else NoiseControl.AMBIENT
                }
                DeviceFamily.GALAXY_BUDS_LIVE -> {
                    if (payloadSize <= 12) return null
                    if (frame[16].toInt() == 0) NoiseControl.OFF else NoiseControl.ANC
                }
                else -> {
                    if (payloadSize <= 12) return null
                    frame[16]
                }
            }
            else -> return null
        }
        if (mode !in NoiseControl.OFF..NoiseControl.ADAPTIVE) return null
        if (mode == NoiseControl.ANC && !family.supportsAnc) return null
        if (mode == NoiseControl.AMBIENT && !family.supportsAmbientMode) return null
        if (mode == NoiseControl.ADAPTIVE && !family.supportsAdaptiveMode) return null
        return NoiseStatus(family, mode, System.currentTimeMillis())
    }

    private fun disconnect() {
        try { outStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outStream = null
        _connected.value = false
        _noiseStatus.value = null
    }

    /** Encodes a request using the selected model generation's SPP frame. */
    fun buildPacket(msgId: Int, payload: ByteArray, family: DeviceFamily): ByteArray {
        require(family.budsTransport != null) { "Device family does not use the Galaxy Buds SPP protocol" }
        require(msgId in 0..255)
        require(payload.size <= if (family.budsTransport == BudsControlTransport.LEGACY) 252 else 1020)
        val size = 1 + payload.size + 2
        val crcInput = ByteArray(1 + payload.size)
        crcInput[0] = msgId.toByte()
        payload.copyInto(crcInput, 1)
        val crc = crc16Xmodem(crcInput)

        val legacyHeader = family.budsTransport == BudsControlTransport.LEGACY
        val out = ByteArray(7 + payload.size)
        var i = 0
        out[i++] = (if (legacyHeader) LEGACY_SOM else SOM).toByte()
        if (legacyHeader) {
            out[i++] = 0
            out[i++] = size.toByte()
        } else {
            out[i++] = (size and 0xFF).toByte()
            out[i++] = ((size shr 8) and 0x03).toByte()
        }
        out[i++] = msgId.toByte()
        payload.copyInto(out, i); i += payload.size
        out[i++] = (crc and 0xFF).toByte()
        out[i++] = ((crc shr 8) and 0xFF).toByte()
        out[i] = (if (legacyHeader) LEGACY_EOM else EOM).toByte()
        return out
    }

    private fun write(packet: ByteArray): Boolean {
        val stream = outStream ?: return false
        return try {
            synchronized(writeLock) {
                stream.write(packet)
                stream.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando comando: ${e.message}")
            disconnect()
            false
        }
    }

    /** Envía un mensaje; si falla, reconecta una vez y reintenta. Bloqueante. */
    @SuppressLint("MissingPermission")
    private fun send(msgId: Int, payload: ByteArray): Boolean {
        val family = lastFamily ?: return false
        val packet = buildPacket(msgId, payload, family)
        var ok = write(packet)
        if (!ok) {
            val device = lastDevice ?: return false
            if (!connectToHardware(device, family)) return false
            ok = write(packet)
        }
        if (ok) Log.d(TAG, "Enviado: " + packet.joinToString(" ") { "%02X".format(it) })
        return ok
    }

    fun setNoiseControlMode(mode: Byte, family: DeviceFamily): Boolean {
        if (family.budsTransport == null || lastFamily != family || !family.supportsAnyNoiseControl) return false
        if (mode == NoiseControl.ANC && !family.supportsAnc) return false
        if (mode == NoiseControl.AMBIENT && !family.supportsAmbientMode) return false
        if (mode == NoiseControl.ADAPTIVE && !family.supportsAdaptiveMode) return false
        if (mode !in NoiseControl.OFF..NoiseControl.ADAPTIVE) return false

        return if (family !in listOf(DeviceFamily.GALAXY_BUDS, DeviceFamily.GALAXY_BUDS_PLUS, DeviceFamily.GALAXY_BUDS_LIVE)) {
            send(MSG_NOISE_CONTROLS, byteArrayOf(mode))
        } else {
            val ambient = mode == NoiseControl.AMBIENT
            val anc = mode == NoiseControl.ANC
            if (ambient) {
                if (family.supportsAnc && !send(MSG_SET_NOISE_REDUCTION, byteArrayOf(0.toByte()))) return false
                if (family.supportsAmbientMode && !send(MSG_SET_AMBIENT_MODE, byteArrayOf(1.toByte()))) return false
            } else {
                if (family.supportsAmbientMode && !send(MSG_SET_AMBIENT_MODE, byteArrayOf(0.toByte()))) return false
                if (family.supportsAnc &&
                    !send(MSG_SET_NOISE_REDUCTION, byteArrayOf(if (anc) 1.toByte() else 0.toByte()))
                ) return false
            }
            true
        }
    }

    fun setEqualizerPreset(enabled: Boolean, preset: Int, family: DeviceFamily): Boolean {
        if (lastFamily != family || preset !in 0..4) return false
        val payload = if (family.budsTransport == BudsControlTransport.LEGACY) {
            byteArrayOf(if (enabled) 1.toByte() else 0.toByte(), (preset + 5).toByte())
        } else {
            byteArrayOf(if (enabled) (preset + 1).toByte() else 0.toByte())
        }
        return send(MSG_EQUALIZER, payload)
    }

    private fun serviceUuid(family: DeviceFamily): UUID? =
        when (family.budsTransport) {
            BudsControlTransport.LEGACY -> SPP_LEGACY_UUID
            BudsControlTransport.STANDARD -> SPP_STANDARD_UUID
            BudsControlTransport.MODERN -> SPP_MODERN_UUID
            null -> null
        }

    // CRC-16/XMODEM: equivale a la tabla Crc16Tab de GalaxyBudsClient
    private fun crc16Xmodem(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    internal class BudsSppFrameDecoder {
        private var pending = ByteArray(0)

        fun append(bytes: ByteArray, length: Int): List<ByteArray> {
            if (length <= 0) return emptyList()
            pending += bytes.copyOf(length)
            val frames = mutableListOf<ByteArray>()
            while (pending.isNotEmpty()) {
                val start = pending[0].toInt() and 0xFF
                if (start != LEGACY_SOM && start != SOM) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                if (pending.size < 3) break
                val legacy = start == LEGACY_SOM
                val size = if (legacy) {
                    pending[2].toInt() and 0xFF
                } else {
                    (pending[1].toInt() and 0xFF) or ((pending[2].toInt() and 0x03) shl 8)
                }
                if (size < 3 || size > 1024) {
                    pending = pending.copyOfRange(1, pending.size)
                    continue
                }
                val frameSize = size + 4
                if (pending.size < frameSize) break
                val frame = pending.copyOfRange(0, frameSize)
                val expectedEnd = if (legacy) LEGACY_EOM else EOM
                val crcStart = frameSize - 3
                val crcInput = frame.copyOfRange(3, crcStart)
                val crc = crc16Xmodem(crcInput)
                val receivedCrc = (frame[crcStart].toInt() and 0xFF) or
                    ((frame[crcStart + 1].toInt() and 0xFF) shl 8)
                if ((frame.last().toInt() and 0xFF) == expectedEnd && crc == receivedCrc) {
                    frames += frame
                    pending = pending.copyOfRange(frameSize, pending.size)
                } else {
                    pending = pending.copyOfRange(1, pending.size)
                }
            }
            return frames
        }
    }
}

package com.music.bitchord.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Control SPP de Galaxy Buds2 / Buds3 Pro.
 * Protocolo tomado de GalaxyBudsClient (timschneeb, GPLv3):
 * SppMessage.Encode, Crc16.cs, MsgIds, NoiseControlModes, SetEqualizerEncoder.
 */
object GalaxyBudsManager {
    private const val TAG = "GalaxyBudsManager"

    // Uuids.SppNew en GalaxyBudsClient
    private val SPP_UUID: UUID = UUID.fromString("2e73a4ad-332d-41fc-90e2-16bef06523f2")

    private const val SOM = 0xFD
    private const val EOM = 0xDD
    private const val MSG_NOISE_CONTROLS = 120
    private const val MSG_EQUALIZER = 134

    object NoiseControl {
        const val OFF: Byte = 0
        const val ANC: Byte = 1
        const val AMBIENT: Byte = 2
        /** Solo Buds3 y posteriores. */
        const val ADAPTIVE: Byte = 3
    }

    /** Orden tomado de EqPresetConverter; falta confirmarlo contra el enum EqPresets. */
    object EqPreset {
        const val BASS_BOOST = 0
        const val SOFT = 1
        const val DYNAMIC = 2
        const val CLEAR = 3
        const val TREBLE_BOOST = 4
    }

    private var socket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private var lastDevice: BluetoothDevice? = null
    private val writeLock = Any()

    @Volatile
    var isConnectedToHardware = false
        private set

    /** Bloqueante: llamar desde un hilo de fondo, no desde el principal. */
    @SuppressLint("MissingPermission")
    fun connectToHardware(device: BluetoothDevice): Boolean {
        lastDevice = device
        repeat(2) { attempt ->
            disconnect()
            try {
                val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                outStream = s.outputStream
                isConnectedToHardware = true
                startDrain(s, s.inputStream)
                Log.d(TAG, "SPP conectado (intento ${attempt + 1})")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error al conectar por SPP (intento ${attempt + 1}): ${e.message}")
                disconnect()
                if (attempt == 0) Thread.sleep(400)
            }
        }
        return false
    }

    // Los Buds mandan actualizaciones de estado; hay que leerlas para que no se llene el buffer.
    private fun startDrain(owner: BluetoothSocket, input: InputStream) {
        Thread {
            val buf = ByteArray(1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                }
            } catch (_: Exception) {
            }
            // Solo si el socket sigue siendo el de este hilo.
            if (socket === owner) isConnectedToHardware = false
        }.apply { isDaemon = true; name = "BudsSppReader" }.start()
    }

    fun disconnect() {
        try { outStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        outStream = null
        isConnectedToHardware = false
    }

    /** FD | size lo | size hi | msgId | payload | crc lo | crc hi | DD */
    fun buildPacket(msgId: Int, payload: ByteArray): ByteArray {
        val size = 1 + payload.size + 2
        val crcInput = ByteArray(1 + payload.size)
        crcInput[0] = msgId.toByte()
        payload.copyInto(crcInput, 1)
        val crc = crc16Xmodem(crcInput)

        val out = ByteArray(1 + 2 + 1 + payload.size + 2 + 1)
        var i = 0
        out[i++] = SOM.toByte()
        out[i++] = (size and 0xFF).toByte()
        out[i++] = ((size shr 8) and 0x03).toByte()
        out[i++] = msgId.toByte()
        payload.copyInto(out, i); i += payload.size
        out[i++] = (crc and 0xFF).toByte()
        out[i++] = ((crc shr 8) and 0xFF).toByte()
        out[i] = EOM.toByte()
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
            false
        }
    }

    /** Envía un mensaje; si falla, reconecta una vez y reintenta. Bloqueante. */
    @SuppressLint("MissingPermission")
    private fun send(msgId: Int, payload: ByteArray): Boolean {
        val packet = buildPacket(msgId, payload)
        var ok = write(packet)
        if (!ok) {
            val device = lastDevice ?: return false
            if (!connectToHardware(device)) return false
            ok = write(packet)
        }
        if (ok) Log.d(TAG, "Enviado: " + packet.joinToString(" ") { "%02X".format(it) })
        return ok
    }

    fun setNoiseControlMode(mode: Byte): Boolean =
        send(MSG_NOISE_CONTROLS, byteArrayOf(mode))

    /** Buds2 / Buds3 Pro: 0 = apagado, preset + 1 = preset. */
    fun setEqualizerPreset(enabled: Boolean, preset: Int): Boolean {
        val value = if (!enabled) 0 else (preset + 1)
        return send(MSG_EQUALIZER, byteArrayOf(value.toByte()))
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
}
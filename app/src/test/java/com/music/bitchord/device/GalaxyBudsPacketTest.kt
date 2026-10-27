package com.music.bitchord.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyBudsPacketTest {
    private fun status(mode: Byte) = GalaxyBudsManager.buildPacket(
        97, ByteArray(13).apply { this[12] = mode }, DeviceFamily.GALAXY_BUDS3_PRO,
    )

    @Test
    fun rejectsCorruptedFramesAndRecoversForNextPacket() {
        val valid = status(GalaxyBudsManager.NoiseControl.ANC)
        val broken = valid.clone().apply { this[4] = 99 }
        val bytes = byteArrayOf(0, 1, 2) + broken + valid
        val decoded = GalaxyBudsManager.BudsSppFrameDecoder().append(bytes, bytes.size)
        assertEquals(1, decoded.size)
        assertTrue(valid.contentEquals(decoded.single()))
    }

    @Test
    fun buffersEveryByteAndDecodesMultipleFramesInOneRead() {
        val valid = status(GalaxyBudsManager.NoiseControl.AMBIENT)
        val decoder = GalaxyBudsManager.BudsSppFrameDecoder()
        valid.dropLast(1).forEach { assertTrue(decoder.append(byteArrayOf(it), 1).isEmpty()) }
        assertEquals(1, decoder.append(byteArrayOf(valid.last()), 1).size)
        assertEquals(2, decoder.append(valid + valid, valid.size * 2).size)
    }

    @Test
    fun doesNotTreatWritesOrUnsupportedModesAsConfirmedStatus() {
        val write = GalaxyBudsManager.buildPacket(120, byteArrayOf(1), DeviceFamily.GALAXY_BUDS3_PRO)
        assertNull(GalaxyBudsManager.decodeNoiseStatus(write, DeviceFamily.GALAXY_BUDS3_PRO))
        assertNull(GalaxyBudsManager.decodeNoiseStatus(status(99), DeviceFamily.GALAXY_BUDS3_PRO))
        assertNull(GalaxyBudsManager.decodeNoiseStatus(status(3), DeviceFamily.GALAXY_BUDS2))
        assertNull(GalaxyBudsManager.decodeNoiseStatus(byteArrayOf(), DeviceFamily.GALAXY_BUDS3_PRO))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPayloadsLargerThanLegacyLengthField() {
        GalaxyBudsManager.buildPacket(97, ByteArray(253), DeviceFamily.GALAXY_BUDS)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPayloadsLargerThanModernLengthField() {
        GalaxyBudsManager.buildPacket(97, ByteArray(1021), DeviceFamily.GALAXY_BUDS3_PRO)
    }
    @Test
    fun usesLegacyFrameForOriginalGalaxyBuds() {
        val packet = GalaxyBudsManager.buildPacket(
            msgId = 128,
            payload = byteArrayOf(1),
            family = DeviceFamily.GALAXY_BUDS,
        )

        assertEquals(8, packet.size)
        assertEquals(0xFE.toByte(), packet[0])
        assertEquals(0, packet[1].toInt())
        assertEquals(4, packet[2].toInt())
        assertEquals(128.toByte(), packet[3])
        assertEquals(1, packet[4].toInt())
        assertEquals(0xEE.toByte(), packet.last())
    }

    @Test
    fun usesStandardFrameForLaterGalaxyBuds() {
        val packet = GalaxyBudsManager.buildPacket(
            msgId = 120,
            payload = byteArrayOf(GalaxyBudsManager.NoiseControl.ADAPTIVE),
            family = DeviceFamily.GALAXY_BUDS4_PRO,
        )

        assertEquals(8, packet.size)
        assertEquals(0xFD.toByte(), packet[0])
        assertEquals(4, packet[1].toInt())
        assertEquals(0, packet[2].toInt())
        assertEquals(120, packet[3].toInt())
        assertEquals(GalaxyBudsManager.NoiseControl.ADAPTIVE, packet[4])
        assertEquals(0xDD.toByte(), packet.last())
    }

    @Test
    fun readsConfirmedNoiseModeFromFragmentedDeviceStatus() {
        val payload = ByteArray(13).apply {
            this[12] = GalaxyBudsManager.NoiseControl.ADAPTIVE
        }
        val frame = GalaxyBudsManager.buildPacket(
            msgId = 97,
            payload = payload,
            family = DeviceFamily.GALAXY_BUDS4_PRO,
        )
        val decoder = GalaxyBudsManager.BudsSppFrameDecoder()

        assertEquals(0, decoder.append(frame.copyOfRange(0, 5), 5).size)
        val decoded = decoder.append(frame.copyOfRange(5, frame.size), frame.size - 5)

        assertEquals(1, decoded.size)
        assertEquals(
            GalaxyBudsManager.NoiseControl.ADAPTIVE,
            GalaxyBudsManager.decodeNoiseStatus(decoded.single(), DeviceFamily.GALAXY_BUDS4_PRO)?.mode,
        )
    }

    @Test
    fun decodesLegacyAmbientModeFromConfirmedDeviceStatus() {
        val payload = ByteArray(8).apply { this[7] = 1 }
        val frame = GalaxyBudsManager.buildPacket(
            msgId = 97,
            payload = payload,
            family = DeviceFamily.GALAXY_BUDS,
        )

        assertEquals(
            GalaxyBudsManager.NoiseControl.AMBIENT,
            GalaxyBudsManager.decodeNoiseStatus(frame, DeviceFamily.GALAXY_BUDS)?.mode,
        )
    }

}

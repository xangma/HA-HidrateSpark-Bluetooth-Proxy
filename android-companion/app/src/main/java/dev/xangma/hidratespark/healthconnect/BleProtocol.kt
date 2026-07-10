package dev.xangma.hidratespark.healthconnect

import java.util.UUID
import kotlin.math.roundToInt

object BleProtocol {
    val USER_DATA: UUID = UUID.fromString("bf2d1ba1-c473-49f2-9571-0ce69036c642")
    val SET_POINT: UUID = UUID.fromString("b44b03f0-b850-4090-86eb-72863fb3618d")
    val DEBUG: UUID = UUID.fromString("e3578b0d-caa7-46d6-b7c2-7331c08de044")
    val DATA_POINT: UUID = UUID.fromString("016e11b1-6c8a-4074-9e5a-076053f93784")
    val REFERENCE_SERVICE: UUID = UUID.fromString("45855422-6565-4cd7-a2a9-fe8af41b85e8")
    val CLIENT_CHARACTERISTIC_CONFIG: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val DRAIN_COMMAND = byteArrayOf(0x57)
    const val MAX_IDENTICAL_FRAMES = 5
    private const val MAX_SECONDS_AGO = 10L * 365 * 24 * 60 * 60

    data class HandshakeCommand(val characteristic: UUID, val payload: ByteArray)

    data class ParsedSip(
        val timestampMillis: Long,
        val volumeMl: Int,
        val totalReportedMl: Int,
    )

    val handshakeCommands: List<HandshakeCommand> = listOf(
        command(DEBUG, "2100d1"),
        command(SET_POINT, "92"),
        command(DEBUG, "2200f7"),
        command(SET_POINT, "7700000032d70000"),
        command(SET_POINT, "00341b00e0790000"),
        command(SET_POINT, "02345200c0a80000"),
        command(SET_POINT, "03346e0030c00000"),
        command(SET_POINT, "04348900a0d70000"),
        command(SET_POINT, "0534a50010ef0000"),
        command(SET_POINT, "0634c00080060100"),
        command(SET_POINT, "0734dc00f01d0100"),
        command(SET_POINT, "0834000000000000"),
        command(SET_POINT, "0934000000000000"),
    )

    fun pendingRecords(frame: ByteArray): Int? =
        frame.firstOrNull()?.toInt()?.and(0xff)

    fun parseSipFrame(
        frame: ByteArray,
        bottleSizeMl: Int,
        nowMillis: Long = System.currentTimeMillis(),
    ): ParsedSip? {
        if (frame.size < 9 || pendingRecords(frame) == 0) return null
        val percent = frame[1].toInt() and 0xff
        if (percent == 0) return null
        val totalReported = ((frame[2].toInt() and 0xff) shl 8) or
            (frame[3].toInt() and 0xff)
        val secondsAgo = (5..8).fold(0L) { value, index ->
            (value shl 8) or (frame[index].toLong() and 0xff)
        }
        if (secondsAgo > MAX_SECONDS_AGO) return null
        val volumeMl = (bottleSizeMl * percent / 100.0).roundToInt().coerceAtLeast(1)
        return ParsedSip(
            timestampMillis = nowMillis - secondsAgo * 1_000,
            volumeMl = volumeMl,
            totalReportedMl = totalReported,
        )
    }

    private fun command(characteristic: UUID, hex: String) =
        HandshakeCommand(characteristic, hexToBytes(hex))

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

package dev.xangma.hidratespark.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectSyncTest {
    @Test
    fun recordIdsAreStableAndBounded() {
        val first = RecordIds.forSip("local-event-42")
        assertEquals(first, RecordIds.forSip("local-event-42"))
        assertEquals(32, first.length)
        assertNotEquals(first, RecordIds.forSip("local-event-43"))
    }

    @Test
    fun bottleAddressIsNormalized() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            BottleSettings.normalizeAddress(" aa:bb:cc:dd:ee:ff "),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBottleAddressIsRejected() {
        BottleSettings.normalizeAddress("not-an-address")
    }

    @Test
    fun sipFrameIsDecoded() {
        val now = 1_000_000L
        val sip = BleProtocol.parseSipFrame(
            byteArrayOf(2, 5, 0, 30, 0, 0, 0, 0, 10),
            bottleSizeMl = 600,
            nowMillis = now,
        )
        requireNotNull(sip)
        assertEquals(30, sip.volumeMl)
        assertEquals(30, sip.totalReportedMl)
        assertEquals(now - 10_000, sip.timestampMillis)
    }

    @Test
    fun emptyAndCorruptFramesAreIgnored() {
        assertNull(BleProtocol.parseSipFrame(byteArrayOf(0), 600, 1_000_000))
        assertNull(
            BleProtocol.parseSipFrame(
                byteArrayOf(1, 5, 0, 30, 0, -1, -1, -1, -1),
                600,
                1_000_000,
            ),
        )
    }
}

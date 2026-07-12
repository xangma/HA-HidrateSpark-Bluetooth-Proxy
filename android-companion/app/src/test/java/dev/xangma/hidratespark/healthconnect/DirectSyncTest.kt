package dev.xangma.hidratespark.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun advertisementReportComparesPacketsAroundMarkers() {
        val unchanged = "0201060303AAFE"
        val changed = "0201060303AAFF"
        val capture = AdvertisementCapture(
            requestedDurationMillis = 10_000,
            observations = listOf(
                AdvertisementObservation(1_000, -60, true, unchanged),
                AdvertisementObservation(2_000, -61, true, unchanged),
                AdvertisementObservation(4_000, -58, true, changed),
            ),
        )

        val report = capture.report(listOf(AdvertisementMarker("Sip", 3_000)))

        assertTrue(report.contains("Advertisements: 3"))
        assertTrue(report.contains("Unique raw payloads: 2"))
        assertTrue(report.contains("Sip at 3.000s"))
        assertTrue(report.contains("previous 10s: 2 packets"))
        assertTrue(report.contains("next 10s: 1 packets"))
        assertTrue(report.contains("raw payload changed: true"))
    }

    @Test
    fun emptyAdvertisementReportIsExplicit() {
        val report = AdvertisementCapture(90_000, emptyList()).report(emptyList())

        assertTrue(report.contains("Advertisements: 0"))
        assertTrue(report.contains("No matching advertisements"))
    }

    @Test
    fun presenceReportCountsFirstAndLostCallbacks() {
        val report = PresenceCapture(
            300_000,
            listOf(
                PresenceObservation(1_000, PresenceCapture.EVENT_FIRST_MATCH, "AA:BB:CC:DD:EE:FF"),
                PresenceObservation(40_000, PresenceCapture.EVENT_MATCH_LOST, "AA:BB:CC:DD:EE:FF"),
            ),
        ).report()

        assertTrue(report.contains("FIRST_MATCH: 1"))
        assertTrue(report.contains("MATCH_LOST: 1"))
        assertTrue(report.contains("40.000s MATCH_LOST"))
    }
}

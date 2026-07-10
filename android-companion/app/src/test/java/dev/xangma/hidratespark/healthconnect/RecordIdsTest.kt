package dev.xangma.hidratespark.healthconnect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecordIdsTest {
    @Test
    fun recordIdsAreStableAndBounded() {
        val first = RecordIds.forSip("entry:42")
        assertEquals(first, RecordIds.forSip("entry:42"))
        assertEquals(32, first.length)
        assertNotEquals(first, RecordIds.forSip("entry:43"))
    }

    @Test
    fun baseUrlRequiresHttpsAndDropsTrailingSlash() {
        assertEquals(
            "https://home.example.test",
            ConnectionSettings.normalizeBaseUrl(" https://home.example.test/ "),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun baseUrlRejectsCleartextHttp() {
        ConnectionSettings.normalizeBaseUrl("http://home.example.test")
    }
}

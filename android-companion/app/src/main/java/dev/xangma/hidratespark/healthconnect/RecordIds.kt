package dev.xangma.hidratespark.healthconnect

import java.security.MessageDigest

object RecordIds {
    /** Health Connect upsert key derived from the durable Home Assistant ID. */
    fun forSip(sourceId: String): String = sha256("hidratespark:$sourceId").take(32)

    fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}

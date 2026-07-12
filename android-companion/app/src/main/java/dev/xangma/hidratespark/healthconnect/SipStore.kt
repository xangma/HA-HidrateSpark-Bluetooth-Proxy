package dev.xangma.hidratespark.healthconnect

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.util.UUID

class SipStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE sip_events (
                id TEXT PRIMARY KEY NOT NULL,
                bottle_address TEXT NOT NULL,
                bottle_name TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                volume_ml INTEGER NOT NULL,
                total_reported_ml INTEGER NOT NULL,
                synced INTEGER NOT NULL DEFAULT 0,
                created_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            "CREATE INDEX sip_events_pending ON sip_events(synced, timestamp_ms)",
        )
        database.execSQL(
            "CREATE INDEX sip_events_dedup ON sip_events(bottle_address, timestamp_ms)",
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // The original schema used this column as a MAC key. Bottle MACs
            // rotate, so migrate old rows to the stable advertised-name key.
            database.execSQL(
                "UPDATE $TABLE SET $COLUMN_BOTTLE_ADDRESS = lower(trim($COLUMN_BOTTLE_NAME))",
            )
        }
    }

    /** Persist before acknowledging the frame to the bottle. */
    @Synchronized
    fun recordSip(settings: BottleSettings, parsed: BleProtocol.ParsedSip): Boolean {
        val database = writableDatabase
        return database.transaction {
            // This legacy column now stores the stable identity rather than a
            // private BLE address, preserving deduplication across rotations.
            val bottleIdentity = settings.identity
            if (isDuplicate(database, bottleIdentity, parsed)) {
                false
            } else {
                val values = ContentValues().apply {
                    put(COLUMN_ID, UUID.randomUUID().toString())
                    put(COLUMN_BOTTLE_ADDRESS, bottleIdentity)
                    put(COLUMN_BOTTLE_NAME, settings.name)
                    put(COLUMN_TIMESTAMP_MS, parsed.timestampMillis)
                    put(COLUMN_VOLUME_ML, parsed.volumeMl)
                    put(COLUMN_TOTAL_REPORTED_ML, parsed.totalReportedMl)
                    put(COLUMN_SYNCED, 0)
                    put(COLUMN_CREATED_MS, System.currentTimeMillis())
                }
                check(database.insertOrThrow(TABLE, null, values) != -1L)
                pruneSyncedHistory(database, bottleIdentity)
                true
            }
        }
    }

    @Synchronized
    fun pending(limit: Int = WRITE_BATCH_SIZE): List<Sip> {
        val boundedLimit = limit.coerceIn(1, WRITE_BATCH_SIZE)
        return readableDatabase.query(
            TABLE,
            SIP_COLUMNS,
            "$COLUMN_SYNCED = 0",
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP_MS ASC, $COLUMN_CREATED_MS ASC",
            boundedLimit.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Sip(
                            id = cursor.getString(0),
                            bottleAddress = cursor.getString(1),
                            bottleName = cursor.getString(2),
                            timestampMillis = cursor.getLong(3),
                            volumeMl = cursor.getInt(4),
                            totalReportedMl = cursor.getInt(5),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    fun markSynced(sips: List<Sip>) {
        if (sips.isEmpty()) return
        val ids = sips.map { it.id }
        val placeholders = ids.joinToString(",") { "?" }
        val database = writableDatabase
        database.transaction {
            val values = ContentValues().apply { put(COLUMN_SYNCED, 1) }
            check(
                database.update(
                    TABLE,
                    values,
                    "$COLUMN_ID IN ($placeholders)",
                    ids.toTypedArray(),
                ) == ids.size,
            ) { "Could not commit the Health Connect synchronization state" }
            sips.map { it.bottleAddress }.distinct().forEach { address ->
                pruneSyncedHistory(database, address)
            }
        }
    }

    private fun isDuplicate(
        database: SQLiteDatabase,
        address: String,
        parsed: BleProtocol.ParsedSip,
    ): Boolean {
        val earliest = parsed.timestampMillis - DEDUP_TOLERANCE_MS
        val latest = parsed.timestampMillis + DEDUP_TOLERANCE_MS
        return database.query(
            TABLE,
            arrayOf(COLUMN_ID),
            """
            $COLUMN_BOTTLE_ADDRESS = ?
            AND $COLUMN_VOLUME_ML = ?
            AND $COLUMN_TIMESTAMP_MS BETWEEN ? AND ?
            AND ($COLUMN_TOTAL_REPORTED_ML = ? OR $COLUMN_TOTAL_REPORTED_ML = 0 OR ? = 0)
            """.trimIndent(),
            arrayOf(
                address,
                parsed.volumeMl.toString(),
                earliest.toString(),
                latest.toString(),
                parsed.totalReportedMl.toString(),
                parsed.totalReportedMl.toString(),
            ),
            null,
            null,
            "$COLUMN_TIMESTAMP_MS DESC",
            "1",
        ).use { it.moveToFirst() }
    }

    private fun pruneSyncedHistory(database: SQLiteDatabase, address: String) {
        database.execSQL(
            """
            DELETE FROM $TABLE
            WHERE $COLUMN_ID IN (
                SELECT $COLUMN_ID FROM $TABLE
                WHERE $COLUMN_BOTTLE_ADDRESS = ? AND $COLUMN_SYNCED = 1
                ORDER BY $COLUMN_TIMESTAMP_MS DESC
                LIMIT -1 OFFSET $MAX_RETAINED_SIPS
            )
            """.trimIndent(),
            arrayOf(address),
        )
    }

    companion object {
        const val WRITE_BATCH_SIZE = 200
        private const val DATABASE_NAME = "hidratespark_sips.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE = "sip_events"
        private const val COLUMN_ID = "id"
        private const val COLUMN_BOTTLE_ADDRESS = "bottle_address"
        private const val COLUMN_BOTTLE_NAME = "bottle_name"
        private const val COLUMN_TIMESTAMP_MS = "timestamp_ms"
        private const val COLUMN_VOLUME_ML = "volume_ml"
        private const val COLUMN_TOTAL_REPORTED_ML = "total_reported_ml"
        private const val COLUMN_SYNCED = "synced"
        private const val COLUMN_CREATED_MS = "created_ms"
        private const val DEDUP_TOLERANCE_MS = 5_000L
        private const val MAX_RETAINED_SIPS = 5_000
        private val SIP_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_BOTTLE_ADDRESS,
            COLUMN_BOTTLE_NAME,
            COLUMN_TIMESTAMP_MS,
            COLUMN_VOLUME_ML,
            COLUMN_TOTAL_REPORTED_ML,
        )
    }
}

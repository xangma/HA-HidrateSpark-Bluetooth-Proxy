"""Durable, cursor-based sip export for Health Connect synchronization."""

import asyncio
import unittest

from ha_stub import HomeAssistant, load_state, local_ts

state = load_state()
Sip = state.Sip


def new_bottle():
    return state.BottleState(HomeAssistant(), "entry", 946)


def restart(bottle):
    async def go():
        await bottle.async_save()
        restored = new_bottle()
        restored._store._data = bottle._store._data
        await restored.async_load()
        return restored

    return asyncio.run(go())


class SipJournalTest(unittest.TestCase):
    def test_accepted_sips_are_paged_in_acceptance_order(self):
        bottle = new_bottle()
        newer = local_ts(2026, 6, 18, 12, 0)
        older_replay = local_ts(2026, 6, 17, 12, 0)
        bottle.add_sip(Sip(timestamp=newer, volume_ml=100))
        bottle.add_sip(Sip(timestamp=older_replay, volume_ml=80))

        first, cursor, has_more, truncated = bottle.sip_events_after(0, 1)
        self.assertEqual([event.sequence for event in first], [1])
        self.assertEqual(first[0].timestamp, newer)
        self.assertEqual(cursor, 1)
        self.assertTrue(has_more)
        self.assertFalse(truncated)

        second, cursor, has_more, truncated = bottle.sip_events_after(cursor, 10)
        self.assertEqual([event.sequence for event in second], [2])
        self.assertEqual(second[0].timestamp, older_replay)
        self.assertEqual(cursor, 2)
        self.assertFalse(has_more)
        self.assertFalse(truncated)

    def test_duplicate_sip_is_not_exported_twice(self):
        bottle = new_bottle()
        timestamp = local_ts(2026, 6, 18, 12, 0)
        self.assertTrue(bottle.add_sip(Sip(timestamp=timestamp, volume_ml=100)))
        self.assertFalse(bottle.add_sip(Sip(timestamp=timestamp + 1, volume_ml=100)))
        self.assertEqual(bottle.latest_sip_sequence, 1)
        self.assertEqual(len(bottle.sip_journal), 1)

    def test_journal_and_sequence_survive_restart(self):
        bottle = new_bottle()
        bottle.add_sip(Sip(timestamp=local_ts(2026, 6, 18, 10, 0), volume_ml=100))
        restored = restart(bottle)
        self.assertEqual(restored.sip_journal_id, bottle.sip_journal_id)
        restored.add_sip(Sip(timestamp=local_ts(2026, 6, 18, 11, 0), volume_ml=120))

        events, cursor, has_more, truncated = restored.sip_events_after(0, 10)
        self.assertEqual([event.sequence for event in events], [1, 2])
        self.assertEqual(cursor, 2)
        self.assertFalse(has_more)
        self.assertFalse(truncated)
        self.assertEqual(
            events[0].to_dict("entry", restored.sip_journal_id)["id"],
            f"entry:{restored.sip_journal_id}:1",
        )

    def test_existing_recent_sips_are_migrated(self):
        bottle = new_bottle()
        bottle._store._data = {
            "recent_sips": [
                {
                    "timestamp": local_ts(2026, 6, 18, 10, 0),
                    "volume_ml": 100,
                },
                {
                    "timestamp": local_ts(2026, 6, 18, 11, 0),
                    "volume_ml": 120,
                },
            ]
        }
        asyncio.run(bottle.async_load())

        self.assertEqual(bottle.latest_sip_sequence, 2)
        self.assertEqual([event.volume_ml for event in bottle.sip_journal], [100, 120])

    def test_stale_cursor_reports_retention_gap(self):
        bottle = new_bottle()
        bottle._last_sip_sequence = 9
        bottle._append_sip_event(
            Sip(timestamp=local_ts(2026, 6, 18, 12, 0), volume_ml=100)
        )
        bottle._append_sip_event(
            Sip(timestamp=local_ts(2026, 6, 18, 13, 0), volume_ml=120)
        )

        events, cursor, has_more, truncated = bottle.sip_events_after(0, 10)
        self.assertEqual([event.sequence for event in events], [10, 11])
        self.assertEqual(cursor, 11)
        self.assertFalse(has_more)
        self.assertTrue(truncated)


if __name__ == "__main__":
    unittest.main(verbosity=2)

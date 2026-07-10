"""Persistent state for a HidrateSpark bottle.

Tracks sip dedup, daily/lifetime totals with day rollover, weight-anchored
fill level with auto-calibration on refill, and the sip-exceeds-fill auto
refill heuristic. Persisted via Home Assistant's Store API so values survive
restarts.
"""

from __future__ import annotations

import logging
import time
import uuid
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Optional

from homeassistant.core import HomeAssistant
from homeassistant.helpers.storage import Store
from homeassistant.util import dt as dt_util

from .const import (
    RAW_UNITS_PER_ML,
    SIP_DEDUP_TIMESTAMP_TOLERANCE_S,
    SIP_DEDUP_WINDOW,
    SIP_JOURNAL_MAX_EVENTS,
    STORAGE_KEY_PREFIX,
    STORAGE_VERSION,
)

_LOGGER = logging.getLogger(__name__)


@dataclass
class Sip:
    """A single sip event."""

    timestamp: float  # unix seconds
    volume_ml: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "iso": datetime.fromtimestamp(self.timestamp, tz=timezone.utc).isoformat(),
            "timestamp": self.timestamp,
            "volume_ml": self.volume_ml,
        }


@dataclass(frozen=True)
class SipEvent:
    """A durable, monotonically sequenced sip for external consumers."""

    sequence: int
    timestamp: float
    volume_ml: int

    def to_dict(self, entry_id: str, journal_id: str) -> dict[str, Any]:
        return {
            "id": f"{entry_id}:{journal_id}:{self.sequence}",
            "sequence": self.sequence,
            "iso": datetime.fromtimestamp(
                self.timestamp, tz=timezone.utc
            ).isoformat(),
            "timestamp": self.timestamp,
            "volume_ml": self.volume_ml,
        }


class BottleState:
    """In-memory state with HA-Store-backed persistence."""

    def __init__(
        self,
        hass: HomeAssistant,
        entry_id: str,
        bottle_size_ml: int,
    ) -> None:
        self._hass = hass
        self._store: Store = Store(
            hass, STORAGE_VERSION, f"{STORAGE_KEY_PREFIX}_{entry_id}"
        )

        self.bottle_size_ml = bottle_size_ml
        self.current_fill_ml: int = bottle_size_ml
        self.lifetime_total_ml: int = 0
        self.last_refill_ts: Optional[float] = None
        self.last_seen: Optional[float] = None

        # Sip history (in-memory only, dedup window).
        self.sips: deque[Sip] = deque(maxlen=200)
        self.last_sip: Optional[Sip] = None

        # Durable export feed. Sequence numbers follow acceptance order rather
        # than sip timestamps because buffered bottle records can arrive late.
        self.sip_journal: deque[SipEvent] = deque(maxlen=SIP_JOURNAL_MAX_EVENTS)
        self._last_sip_sequence = 0
        self._sip_journal_id = uuid.uuid4().hex

        # Daily total with day rollover.
        self._today_date: str = ""
        self._total_today_ml: int = 0
        self._sips_today: int = 0
        self._refills_today: int = 0

        # Weight calibration (16-bit raw values). "full" is the bootstrap/refill
        # anchor; "empty" is the learned tare (lightest settled reading), which
        # is the stable zero reference fill is measured up from.
        self.weight_full_raw: Optional[int] = None
        self.weight_empty_raw: Optional[int] = None
        self.weight_raw: Optional[int] = None  # most recent stable u16 reading

    # ----------------------------------------------------------- persistence

    async def async_load(self) -> None:
        data = await self._store.async_load() or {}
        stored_journal_id = str(data.get("sip_journal_id") or "")
        if stored_journal_id:
            self._sip_journal_id = stored_journal_id
        self.current_fill_ml = int(data.get("current_fill_ml") or self.bottle_size_ml)
        self.lifetime_total_ml = int(data.get("lifetime_total_ml") or 0)
        self.last_refill_ts = data.get("last_refill_ts")
        self._today_date = str(data.get("today_date") or "")
        self._total_today_ml = int(data.get("total_today_ml") or 0)
        self._sips_today = int(data.get("sips_today") or 0)
        self._refills_today = int(data.get("refills_today") or 0)
        self.weight_full_raw = data.get("weight_full_raw")
        self.weight_empty_raw = data.get("weight_empty_raw")

        # Restore the recent-sip dedup window. Without this, a HA restart
        # empties the dedup history, and the bottle's initial drain replays its
        # buffered sips as brand-new ones — double-counting into the persisted
        # lifetime/today totals.
        for raw in data.get("recent_sips") or []:
            try:
                self.sips.append(
                    Sip(timestamp=float(raw["timestamp"]), volume_ml=int(raw["volume_ml"]))
                )
            except (KeyError, TypeError, ValueError):
                continue
        if self.sips:
            self.last_sip = max(self.sips, key=lambda s: s.timestamp)
            self.last_seen = self.last_sip.timestamp

        for raw in data.get("sip_journal") or []:
            try:
                event = SipEvent(
                    sequence=int(raw["sequence"]),
                    timestamp=float(raw["timestamp"]),
                    volume_ml=int(raw["volume_ml"]),
                )
            except (KeyError, TypeError, ValueError):
                continue
            if event.sequence > 0 and event.volume_ml > 0:
                self.sip_journal.append(event)

        stored_sequence = int(data.get("last_sip_sequence") or 0)
        journal_sequence = max(
            (event.sequence for event in self.sip_journal), default=0
        )
        self._last_sip_sequence = max(stored_sequence, journal_sequence)

        # Upgrade existing installations without losing the recent buffered
        # history they already persisted for BLE deduplication.
        migrated = not self.sip_journal and bool(self.sips)
        if migrated:
            for sip in self.sips:
                self._append_sip_event(sip)

        # Persist the random stream identity immediately on first setup or
        # upgrade. If the HA Store is ever lost, a new identity prevents a
        # phone cursor from remaining ahead of a reset sequence forever.
        if not stored_journal_id or migrated:
            await self.async_save()

    async def async_save(self) -> None:
        await self._store.async_save(
            {
                "current_fill_ml": self.current_fill_ml,
                "lifetime_total_ml": self.lifetime_total_ml,
                "last_refill_ts": self.last_refill_ts,
                "today_date": self._today_date,
                "total_today_ml": self._total_today_ml,
                "sips_today": self._sips_today,
                "refills_today": self._refills_today,
                "weight_full_raw": self.weight_full_raw,
                "weight_empty_raw": self.weight_empty_raw,
                # Persist the dedup window so replays after a restart are
                # recognised as duplicates rather than re-counted.
                "recent_sips": [
                    {"timestamp": s.timestamp, "volume_ml": s.volume_ml}
                    for s in list(self.sips)[-SIP_DEDUP_WINDOW:]
                ],
                "last_sip_sequence": self._last_sip_sequence,
                "sip_journal_id": self._sip_journal_id,
                "sip_journal": [
                    {
                        "sequence": event.sequence,
                        "timestamp": event.timestamp,
                        "volume_ml": event.volume_ml,
                    }
                    for event in self.sip_journal
                ],
            }
        )

    # --------------------------------------------------------------- mutations

    def set_bottle_size(self, size_ml: int) -> None:
        self.bottle_size_ml = size_ml
        if self.current_fill_ml > size_ml:
            self.current_fill_ml = size_ml

    def _local_date_str(self, ts: Optional[float] = None) -> str:
        """Return YYYY-MM-DD in HA's configured local timezone.

        Using HA's configured zone (Settings -> System -> General) keeps the
        'water today' counter and the new sip/refill counters in sync with
        what the user sees on the wall clock, including DST transitions.
        """
        if ts is None:
            return dt_util.now().strftime("%Y-%m-%d")
        return dt_util.as_local(
            datetime.fromtimestamp(ts, tz=timezone.utc)
        ).strftime("%Y-%m-%d")

    def _maybe_rollover(self, ts: Optional[float] = None) -> None:
        """Reset daily counters if `ts` (or now) falls on a new local date."""
        date_str = self._local_date_str(ts)
        if date_str != self._today_date:
            self._today_date = date_str
            self._total_today_ml = 0
            self._sips_today = 0
            self._refills_today = 0

    def refill(self, source: str, weight_full_raw: Optional[int]) -> None:
        self._maybe_rollover()
        self.current_fill_ml = self.bottle_size_ml
        self.last_refill_ts = time.time()
        # A "calibration" is the one-time bootstrap that establishes the full-
        # weight anchor (bottle assumed full); it isn't a user refill, so it
        # doesn't bump the daily refill counter.
        if source != "calibration":
            self._refills_today += 1
        if weight_full_raw is not None:
            self.weight_full_raw = weight_full_raw
        _LOGGER.info(
            "REFILL (%s): fill=%dml anchor=%s refills_today=%d",
            source,
            self.current_fill_ml,
            self.weight_full_raw,
            self._refills_today,
        )

    def update_fill_from_weight(self, raw: int) -> bool:
        """Recompute current fill from a stable upright 16-bit weight reading.

        Fill is measured up from the bottle's empty weight (tare), not down from
        the "full" anchor: the tare is stable per bottle, whereas "full" varies
        with how full it was actually filled, so anchoring on it leaves phantom
        volume at empty. The tare is learned as the lightest settled reading
        seen; until a real drain has been observed we fall back to estimating
        down from the full anchor so a freshly-set-up (full) bottle still reads
        sensibly. Returns True if current_fill_ml changed.
        """
        self.weight_raw = raw
        if self.weight_full_raw is None:
            # Bootstrap: the first settled reading establishes the full-weight
            # anchor (bottle assumed full at calibration). A real refill
            # (cap open/close + weight jump) re-anchors at the true full later.
            self.weight_full_raw = raw
            self.current_fill_ml = self.bottle_size_ml
            _LOGGER.info("weight calibration: adopted %s as full anchor", raw)
            return True

        full_span = RAW_UNITS_PER_ML * self.bottle_size_ml
        # Learn the empty floor (tare) as the lightest settled reading. Only
        # accept candidates that are plausibly below the full anchor but not more
        # than a bottle's worth below it (which would be the bottle lifted off
        # the puck rather than genuinely empty).
        if (
            self.weight_full_raw - 1.3 * full_span <= raw < self.weight_full_raw
            and (self.weight_empty_raw is None or raw < self.weight_empty_raw)
        ):
            self.weight_empty_raw = raw

        if (
            self.weight_empty_raw is not None
            and self.weight_full_raw - self.weight_empty_raw >= 0.6 * full_span
        ):
            # Enough range observed: measure up from the learned empty floor, so
            # empty reads 0 regardless of how full the last fill actually was.
            new_fill = round((raw - self.weight_empty_raw) / RAW_UNITS_PER_ML)
        else:
            # Not drained enough yet to trust the floor: estimate down from full.
            new_fill = self.bottle_size_ml - round(
                (self.weight_full_raw - raw) / RAW_UNITS_PER_ML
            )
        new_fill = max(0, min(self.bottle_size_ml, new_fill))
        if new_fill != self.current_fill_ml:
            self.current_fill_ml = new_fill
            return True
        return False

    def add_sip(self, sip: Sip) -> bool:
        """Append a sip if it isn't a duplicate. Returns True if accepted."""
        # Dedup against last N sips: same volume within ±2 s timestamp.
        for existing in list(self.sips)[-SIP_DEDUP_WINDOW:]:
            if (
                abs(existing.timestamp - sip.timestamp)
                < SIP_DEDUP_TIMESTAMP_TOLERANCE_S
                and existing.volume_ml == sip.volume_ml
            ):
                return False

        # Day rollover is keyed on wall-clock *now* (in HA's configured local
        # timezone), never on the sip's own timestamp. Buffered sips replayed
        # on reconnect carry old timestamps; keying rollover off them would roll
        # 'today' backwards and zero the daily counters (issue #4).
        self._maybe_rollover()

        self.sips.append(sip)
        self._append_sip_event(sip)
        self.lifetime_total_ml += sip.volume_ml

        # Only count toward 'today' if the sip actually falls on today's local
        # date. Historical/replayed sips still contribute to the lifetime total.
        if self._local_date_str(sip.timestamp) == self._today_date:
            self._total_today_ml += sip.volume_ml
            self._sips_today += 1

        # 'Last sip' / 'last seen' advance forward only, so a replayed old frame
        # can't make the last-sip sensor jump backwards (issue #4).
        if self.last_sip is None or sip.timestamp >= self.last_sip.timestamp:
            self.last_sip = sip
            self.last_seen = sip.timestamp

        # Sip-exceeds-fill: bottle was clearly refilled out-of-band. Only used as
        # a fallback while we have no weight anchor to track fill directly.
        if self.weight_full_raw is None and sip.volume_ml > self.current_fill_ml:
            self.current_fill_ml = max(0, self.bottle_size_ml - sip.volume_ml)
            self.last_refill_ts = sip.timestamp
            _LOGGER.info(
                "REFILL (auto: sip exceeded fill) -> %dml after %dml sip",
                self.current_fill_ml,
                sip.volume_ml,
            )
        elif self.weight_full_raw is None:
            # Sip-decrement fallback while we have no weight anchor.
            self.current_fill_ml = max(0, self.current_fill_ml - sip.volume_ml)

        return True

    def _append_sip_event(self, sip: Sip) -> None:
        """Append one accepted sip to the durable export feed."""
        self._last_sip_sequence += 1
        self.sip_journal.append(
            SipEvent(
                sequence=self._last_sip_sequence,
                timestamp=sip.timestamp,
                volume_ml=sip.volume_ml,
            )
        )

    def sip_events_after(
        self, after: int, limit: int
    ) -> tuple[list[SipEvent], int, bool, bool]:
        """Return a cursor page as events, next cursor, has-more, truncated.

        The caller's cursor may predate the retained journal. In that case the
        page starts at the oldest available event and reports ``truncated`` so
        clients can surface the gap without getting stuck retrying it.
        """
        after = max(0, int(after))
        limit = max(1, int(limit))
        if not self.sip_journal:
            return [], after, False, False

        oldest = self.sip_journal[0].sequence
        effective_after = max(after, oldest - 1)
        truncated = after < oldest - 1
        events = [
            event
            for event in self.sip_journal
            if event.sequence > effective_after
        ][:limit]
        next_after = events[-1].sequence if events else effective_after
        has_more = self.sip_journal[-1].sequence > next_after
        return events, next_after, has_more, truncated

    @property
    def oldest_sip_sequence(self) -> int | None:
        return self.sip_journal[0].sequence if self.sip_journal else None

    @property
    def latest_sip_sequence(self) -> int:
        return self._last_sip_sequence

    @property
    def sip_journal_id(self) -> str:
        return self._sip_journal_id

    @property
    def total_today_ml(self) -> int:
        # Late-day rollover: if no sip has come in yet today, we still want
        # the sensor to read 0 once midnight has passed (in HA's local zone).
        if self._local_date_str() != self._today_date:
            return 0
        return self._total_today_ml

    @property
    def sips_today(self) -> int:
        if self._local_date_str() != self._today_date:
            return 0
        return self._sips_today

    @property
    def refills_today(self) -> int:
        if self._local_date_str() != self._today_date:
            return 0
        return self._refills_today

    @property
    def current_fill_pct(self) -> int:
        if self.bottle_size_ml <= 0:
            return 0
        return round(100 * self.current_fill_ml / self.bottle_size_ml)

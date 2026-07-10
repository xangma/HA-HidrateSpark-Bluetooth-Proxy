"""Authenticated REST feed consumed by the Android Health Connect companion."""

from __future__ import annotations

from aiohttp import web
from homeassistant.components.http import HomeAssistantView
from homeassistant.core import HomeAssistant

from .const import DOMAIN, SIP_API_DEFAULT_LIMIT, SIP_API_MAX_LIMIT


def _coordinators(hass: HomeAssistant):
    """Yield configured bottle coordinators without coupling to HA internals."""
    for value in hass.data.get(DOMAIN, {}).values():
        if all(hasattr(value, attr) for attr in ("entry", "state", "address", "name")):
            yield value


class HidrateSparkBottlesView(HomeAssistantView):
    """List configured bottles and their retained feed bounds."""

    url = "/api/hidratespark/bottles"
    name = "api:hidratespark:bottles"
    requires_auth = True

    async def get(self, request: web.Request) -> web.Response:
        hass: HomeAssistant = request.app["hass"]
        bottles = []
        for coordinator in _coordinators(hass):
            bottles.append(
                {
                    "entry_id": coordinator.entry.entry_id,
                    "name": coordinator.name,
                    "address": coordinator.address,
                    "journal_id": coordinator.state.sip_journal_id,
                    "oldest_sequence": coordinator.state.oldest_sip_sequence,
                    "latest_sequence": coordinator.state.latest_sip_sequence,
                }
            )
        return self.json({"bottles": bottles})


class HidrateSparkSipsView(HomeAssistantView):
    """Page through one bottle's accepted sip journal."""

    url = "/api/hidratespark/bottles/{entry_id}/sips"
    name = "api:hidratespark:bottle-sips"
    requires_auth = True

    async def get(self, request: web.Request, entry_id: str) -> web.Response:
        hass: HomeAssistant = request.app["hass"]
        coordinator = hass.data.get(DOMAIN, {}).get(entry_id)
        if coordinator is None or not hasattr(coordinator, "state"):
            return self.json({"error": "Bottle not found"}, status_code=404)

        try:
            after = int(request.query.get("after", "0"))
            limit = int(request.query.get("limit", str(SIP_API_DEFAULT_LIMIT)))
        except ValueError:
            return self.json(
                {"error": "after and limit must be integers"}, status_code=400
            )
        if after < 0 or limit < 1 or limit > SIP_API_MAX_LIMIT:
            return self.json(
                {
                    "error": (
                        f"after must be non-negative and limit must be 1.."
                        f"{SIP_API_MAX_LIMIT}"
                    )
                },
                status_code=400,
            )

        events, next_after, has_more, truncated = coordinator.state.sip_events_after(
            after, limit
        )
        return self.json(
            {
                "entry_id": entry_id,
                "name": coordinator.name,
                "address": coordinator.address,
                "journal_id": coordinator.state.sip_journal_id,
                "after": after,
                "next_after": next_after,
                "has_more": has_more,
                "truncated": truncated,
                "sips": [
                    event.to_dict(entry_id, coordinator.state.sip_journal_id)
                    for event in events
                ],
            }
        )

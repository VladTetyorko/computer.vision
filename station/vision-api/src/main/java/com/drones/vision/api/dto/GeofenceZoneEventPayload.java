package com.drones.vision.api.dto;

/**
 * The payload of one envelope on the {@code zones} SSE topic (docs/plans/active/
 * LIVE-POLL-RETIREMENT-PLAN.md &sect;3 D2/&sect;4.1, wave L3) — a geofence zone created, updated, or
 * deleted. Mirrors {@link DiscoveryEventPayload} field-for-field.
 *
 * <p>{@link #zone()} is today's {@link GeofenceZoneResponse} shape, verbatim — the same DTO {@code
 * GET /api/geofences} already returns per element. On {@code "DELETED"}, {@link #zone()} is still
 * the zone in full (its last-known state), not merely its id, so a client's own 10s Undo can
 * re-{@code POST} the exact body it just removed.
 *
 * @param action {@code "CREATED"}, {@code "UPDATED"}, or {@code "DELETED"}
 * @param zone   the zone's current (or, for a delete, last-known) state
 */
public record GeofenceZoneEventPayload(String action, GeofenceZoneResponse zone) {
}
